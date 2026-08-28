#!/usr/bin/env python3
"""Structural verifier for Blockhold Defense APKs.

Runs the checks that actually catch "installs fine but will not open" defects,
using only the Python standard library (no Android SDK required):

  1. ZIP layout      - stored/deflated state and 4-byte alignment of every
                       uncompressed entry (classes.dex and resources.arsc in
                       particular).
  2. Signing         - v1 (JAR) files and the v2/v3 APK Signing Block.
  3. DEX integrity   - magic, declared size, adler32 checksum, SHA-1 signature,
                       and every referenced type being defined in the payload
                       (catches a stripped Kotlin stdlib -> NoClassDefFoundError).
  4. AndroidManifest - package, versions, SDK levels, launcher activity and the
                       MAIN/LAUNCHER intent filter.
  5. Resources       - the resources.arsc type/key table, plus a cross-check of
                       every drawable/raw name the code asks for by string
                       against what is actually packaged (SpriteCatalog and
                       AudioEngine both use Resources.getIdentifier, so a missing
                       name is a launch-time crash, not a compile error).
  6. Sprite strips   - every loadStrip() PNG must satisfy width % height == 0,
                       which SpriteCatalog asserts with check() at startup.

Usage:
    python3 scripts/verify-apk.py artifacts/Blockhold-Defense-v1.2-installable.apk
    python3 scripts/verify-apk.py --all

Exit code is non-zero if any FAIL-level check trips.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import os
import re
import struct
import sys
import zipfile
import zlib

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "app", "src", "main", "java", "ai", "techtroy", "blockhold")
RES = os.path.join(REPO, "app", "src", "main", "res")

failures: list[str] = []
warnings: list[str] = []


def ok(msg: str) -> None:
    print(f"  \033[32mPASS\033[0m  {msg}")


def warn(msg: str) -> None:
    warnings.append(msg)
    print(f"  \033[33mWARN\033[0m  {msg}")


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  \033[31mFAIL\033[0m  {msg}")


def section(title: str) -> None:
    print(f"\n\033[1m{title}\033[0m")


# --------------------------------------------------------------------------
# binary XML / resources.arsc readers
# --------------------------------------------------------------------------

def _pool(d: bytes, off: int) -> tuple[list[str], int]:
    """Parse a ResStringPool chunk at `off`; returns (strings, chunk_size)."""
    size = struct.unpack_from("<I", d, off + 4)[0]
    count = struct.unpack_from("<I", d, off + 8)[0]
    flags = struct.unpack_from("<I", d, off + 16)[0]
    start = struct.unpack_from("<I", d, off + 20)[0]
    utf8 = bool((flags >> 8) & 1)
    out = []
    for i in range(count):
        so = off + start + struct.unpack_from("<I", d, off + 28 + 4 * i)[0]
        if utf8:
            n = d[so]
            so += 2 if n & 0x80 else 1
            ln = d[so]
            if ln & 0x80:
                ln = ((ln & 0x7F) << 8) | d[so + 1]
                so += 2
            else:
                so += 1
            out.append(d[so:so + ln].decode("utf-8", "replace"))
        else:
            ln = struct.unpack_from("<H", d, so)[0]
            out.append(d[so + 2:so + 2 + ln * 2].decode("utf-16le", "replace"))
    return out, size


def parse_manifest(d: bytes) -> list[tuple[str, dict]]:
    """Return [(element_name, {attr: value})] for a binary AndroidManifest.xml."""
    strs, pool_size = _pool(d, 8)
    o = 8 + pool_size
    resmap: list[int] = []
    if struct.unpack_from("<H", d, o)[0] == 0x0180:
        hs = struct.unpack_from("<H", d, o + 2)[0]
        cs = struct.unpack_from("<I", d, o + 4)[0]
        resmap = list(struct.unpack_from("<%dI" % ((cs - hs) // 4), d, o + hs))

    known = {
        0x0101021B: "versionCode", 0x0101021C: "versionName",
        0x0101020C: "minSdkVersion", 0x01010270: "targetSdkVersion",
        0x01010003: "name", 0x01010010: "exported", 0x01010001: "label",
        0x01010002: "icon", 0x01010000: "theme",
    }
    elements = []
    while o < len(d) - 8:
        t = struct.unpack_from("<H", d, o)[0]
        hs = struct.unpack_from("<H", d, o + 2)[0]
        cs = struct.unpack_from("<I", d, o + 4)[0]
        if t == 0x0102:  # START_ELEMENT
            name = strs[struct.unpack_from("<I", d, o + 20)[0]]
            astart = struct.unpack_from("<H", d, o + 24)[0]
            asize = struct.unpack_from("<H", d, o + 26)[0]
            acount = struct.unpack_from("<H", d, o + 28)[0]
            attrs = {}
            for i in range(acount):
                ao = o + hs + astart + i * asize
                an = struct.unpack_from("<I", d, ao + 4)[0]
                rs = struct.unpack_from("<I", d, ao + 8)[0]
                dt = d[ao + 15]
                dv = struct.unpack_from("<I", d, ao + 16)[0]
                key = strs[an] if an < len(strs) else f"#{an}"
                if an < len(resmap) and resmap[an] in known:
                    key = known[resmap[an]]
                if rs != 0xFFFFFFFF:
                    val = strs[rs]
                elif dt == 3:
                    val = strs[dv]
                elif dt == 0x12:
                    val = bool(dv)
                elif dt == 0x10:
                    val = struct.unpack_from("<i", d, ao + 16)[0]
                elif dt == 0x01:
                    val = f"@0x{dv:08x}"
                else:
                    val = f"0x{dv:08x}"
                attrs[key] = val
            elements.append((name, attrs))
        if cs == 0:
            break
        o += cs
    return elements


def parse_arsc(d: bytes) -> dict[str, dict[str, int]]:
    """Return {type_name: {entry_name: resource_id}} from resources.arsc."""
    gstr, gsz = _pool(d, 12)
    pkg = 12 + gsz
    hs = struct.unpack_from("<H", d, pkg + 2)[0]
    cs = struct.unpack_from("<I", d, pkg + 4)[0]
    pid = struct.unpack_from("<I", d, pkg + 8)[0]
    types, _ = _pool(d, pkg + struct.unpack_from("<I", d, pkg + 268)[0])
    keys, _ = _pool(d, pkg + struct.unpack_from("<I", d, pkg + 276)[0])
    out: dict[str, dict[str, int]] = {}
    o, end = pkg + hs, pkg + cs
    while o < end - 8:
        t = struct.unpack_from("<H", d, o)[0]
        hsz = struct.unpack_from("<H", d, o + 2)[0]
        csz = struct.unpack_from("<I", d, o + 4)[0]
        if t == 0x0201:  # RES_TABLE_TYPE_TYPE
            tid = d[o + 8]
            count = struct.unpack_from("<I", d, o + 12)[0]
            entstart = struct.unpack_from("<I", d, o + 16)[0]
            bucket = out.setdefault(types[tid - 1], {})
            for i in range(count):
                eo = struct.unpack_from("<I", d, o + hsz + 4 * i)[0]
                if eo == 0xFFFFFFFF:
                    continue
                e = o + entstart + eo
                ki = struct.unpack_from("<I", d, e + 4)[0]
                bucket[keys[ki]] = (pid << 24) | (tid << 16) | i
        if csz == 0:
            break
        o += csz
    return out


def dex_types(d: bytes) -> tuple[set[str], set[str]]:
    """Return (referenced_types, defined_classes) for a classes.dex blob."""
    u32 = lambda o: struct.unpack_from("<I", d, o)[0]

    def uleb(o):
        r = s = 0
        while True:
            b = d[o]
            o += 1
            r |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                return r, o

    sio = u32(60)
    tio, tis = u32(68), u32(64)

    def string_at(i):
        off = u32(sio + 4 * i)
        _, off = uleb(off)
        return d[off:d.index(b"\0", off)].decode("utf-8", "replace")

    refs = {string_at(u32(tio + 4 * i)) for i in range(tis)}
    cds, cdo = u32(96), u32(100)
    defined = {string_at(u32(tio + 4 * u32(cdo + 32 * i))) for i in range(cds)}
    return refs, defined


def dex_strings(d: bytes) -> set[str]:
    u32 = lambda o: struct.unpack_from("<I", d, o)[0]

    def uleb(o):
        r = s = 0
        while True:
            b = d[o]
            o += 1
            r |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                return r, o

    sis, sio = u32(56), u32(60)
    out = set()
    for i in range(sis):
        off = u32(sio + 4 * i)
        _, off = uleb(off)
        out.add(d[off:d.index(b"\0", off)].decode("utf-8", "replace"))
    return out


# --------------------------------------------------------------------------
# checks
# --------------------------------------------------------------------------

def check_zip(path: str, z: zipfile.ZipFile) -> None:
    section("1. ZIP layout and alignment")
    with open(path, "rb") as f:
        stored = misaligned = 0
        offenders = []
        for i in z.infolist():
            if i.compress_type != 0:
                continue
            stored += 1
            f.seek(i.header_offset)
            h = f.read(30)
            n, e = struct.unpack("<HH", h[26:30])
            off = i.header_offset + 30 + n + e
            if off % 4:
                misaligned += 1
                offenders.append((i.filename, off))

    try:
        arsc = z.getinfo("resources.arsc")
    except KeyError:
        fail("resources.arsc is missing")
    else:
        if arsc.compress_type != 0:
            fail("resources.arsc is DEFLATED; targetSdk>=30 requires it STORED "
                 "(install is rejected with INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED)")
        else:
            ok("resources.arsc is stored uncompressed")

    dex = z.getinfo("classes.dex")
    dex_stored = dex.compress_type == 0
    ok(f"classes.dex is {'STORED (uncompressed)' if dex_stored else 'DEFLATED'}")

    if misaligned:
        fail(f"{misaligned} of {stored} uncompressed entries are NOT 4-byte aligned "
             f"- this APK was never run through zipalign")
        for name, off in offenders[:5]:
            print(f"          e.g. {name} @ offset {off} (offset % 4 = {off % 4})")
        if dex_stored and any(n == "classes.dex" for n, _ in offenders):
            fail("classes.dex is stored uncompressed AND misaligned; ART cannot mmap it "
                 "directly and falls back to extracting it into memory on every cold start")
    else:
        ok(f"all {stored} uncompressed entries are 4-byte aligned")


def check_signing(path: str) -> None:
    section("2. Signing")
    d = open(path, "rb").read()
    i = d.rfind(b"PK\x05\x06")
    cdoff = struct.unpack_from("<I", d, i + 16)[0]
    v1 = b"META-INF/CERT.RSA" in d[:cdoff] or b"META-INF/CERT.SF" in d[:cdoff]
    if d[cdoff - 16:cdoff] != b"APK Sig Block 42":
        fail("no APK Signing Block - v2/v3 signature absent "
             "(devices on Android 11+ reject targetSdk>=30 APKs signed with v1 only)")
        return
    size = struct.unpack_from("<Q", d, cdoff - 24)[0]
    start = cdoff - 8 - size
    blk = d[start + 8:cdoff - 24]
    names = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}
    found, o = [], 0
    while o < len(blk) - 12:
        ln = struct.unpack_from("<Q", blk, o)[0]
        if ln < 4:
            break
        idv = struct.unpack_from("<I", blk, o + 8)[0]
        if idv in names:
            found.append(names[idv])
        o += 8 + ln
    ok(f"signature schemes present: {'v1, ' if v1 else ''}{', '.join(found) or 'none'}")
    if not found:
        fail("no v2/v3 signature")


def dex_infos(z: zipfile.ZipFile) -> list[tuple[str, bytes]]:
    """Return every dex payload in multidex order, not just classes.dex."""
    infos = [i for i in z.infolist() if re.fullmatch(r"classes(?:\d+)?\.dex", i.filename)]
    infos.sort(key=lambda i: 1 if i.filename == "classes.dex" else int(i.filename[7:-4]))
    return [(i.filename, z.read(i)) for i in infos]


def all_dex_defined(z: zipfile.ZipFile) -> set[str]:
    defined: set[str] = set()
    for _, d in dex_infos(z):
        if d[:4] == b"dex\n":
            _, classes = dex_types(d)
            defined.update(classes)
    return defined


def check_dex(z: zipfile.ZipFile) -> None:
    section("3. DEX integrity")
    payloads = dex_infos(z)
    if not payloads:
        fail("no classes*.dex payload found")
        return

    all_refs: set[str] = set()
    all_defined: set[str] = set()
    for name, d in payloads:
        prefix = f"{name}: "
        if d[:4] != b"dex\n":
            fail(prefix + "bad magic header")
            continue
        declared = struct.unpack_from("<I", d, 32)[0]
        ok(f"{prefix}format {d[4:7].decode()}, {len(d)} bytes")
        if declared != len(d):
            fail(prefix + f"header file_size {declared} != actual {len(d)} (truncated payload)")
        else:
            ok(prefix + "declared size matches payload")

        hdr_sum = struct.unpack_from("<I", d, 8)[0]
        if hdr_sum != (zlib.adler32(d[12:]) & 0xFFFFFFFF):
            fail(prefix + "adler32 checksum mismatch - ART rejects the dex and MainActivity "
                 "fails with ClassNotFoundException")
        else:
            ok(prefix + "adler32 checksum valid")

        if d[12:32] != hashlib.sha1(d[32:]).digest():
            fail(prefix + "SHA-1 signature mismatch - dex payload was modified after generation")
        else:
            ok(prefix + "SHA-1 signature valid")

        refs, defined = dex_types(d)
        all_refs.update(refs)
        all_defined.update(defined)

    dangling = sorted(
        t for t in all_refs
        if t.startswith("L")
        and t not in all_defined
        and not t.startswith(("Landroid", "Ljava", "Ldalvik", "Lorg/xml", "Lorg/json",
                              "Lorg/w3c", "Lorg/apache", "Ljunit", "Lsun/"))
    )
    if dangling:
        fail(f"{len(dangling)} referenced classes are not defined in the bundled DEX files and "
             f"are not framework classes (e.g. {dangling[:4]}) - NoClassDefFoundError at launch")
    else:
        ok(f"all {len(all_refs)} referenced types resolve across {len(payloads)} DEX file(s) "
           f"({len(all_defined)} classes bundled, Kotlin stdlib included)")


def check_manifest(z: zipfile.ZipFile) -> str | None:
    section("4. AndroidManifest")
    els = parse_manifest(z.read("AndroidManifest.xml"))
    by = {}
    for name, attrs in els:
        by.setdefault(name, []).append(attrs)

    man = by.get("manifest", [{}])[0]
    pkg = man.get("package")
    ok(f"package={pkg} versionName={man.get('versionName')} versionCode={man.get('versionCode')}")
    sdk = by.get("uses-sdk", [{}])[0]
    ok(f"minSdkVersion={sdk.get('minSdkVersion')} targetSdkVersion={sdk.get('targetSdkVersion')}")

    launcher = None
    acts = by.get("activity", [])
    actions = [a.get("name") for a in by.get("action", [])]
    cats = [a.get("name") for a in by.get("category", [])]
    if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in cats:
        ok("MAIN/LAUNCHER intent filter present")
    else:
        fail("no MAIN/LAUNCHER intent filter - the app installs but shows no icon to tap")

    if acts:
        a = acts[0]
        nm = str(a.get("name", ""))
        launcher = (pkg + nm) if nm.startswith(".") else nm
        ok(f"launch activity = {launcher}")
        if a.get("exported") is not True:
            fail("launcher activity is not android:exported=true "
                 "(install fails on Android 12+)")
        else:
            ok("launcher activity is exported")
    else:
        fail("no <activity> declared")

    if launcher:
        cls = "L" + launcher.replace(".", "/") + ";"
        defined = all_dex_defined(z)
        if cls in defined:
            ok(f"{launcher} is present in the bundled DEX files")
        else:
            fail(f"{launcher} declared in the manifest but MISSING from all classes*.dex files "
                 f"- guaranteed ClassNotFoundException on launch")
    return launcher


def check_resources(z: zipfile.ZipFile) -> None:
    section("5. Resource table vs. runtime lookups (vs. CURRENT source tree)")
    table = parse_arsc(z.read("resources.arsc"))
    for t, entries in sorted(table.items()):
        ok(f"{t}: {len(entries)} entries")

    drawables = set(table.get("drawable", {}))
    raws = set(table.get("raw", {}))

    sprite_src = open(os.path.join(SRC, "SpriteCatalog.kt")).read()
    wanted_draw = set(re.findall(r'load(?:Strip)?\("([^"]+)"\)', sprite_src))
    audio_src = open(os.path.join(SRC, "AudioEngine.kt")).read()
    wanted_raw = set(re.findall(r'load\("([^"]+)"\)', audio_src))

    missing = sorted(wanted_draw - drawables)
    if missing:
        fail(f"SpriteCatalog asks for {len(missing)} drawable(s) not in the APK: {missing[:6]} "
             f"- check(id != 0) throws \"Missing sprite resource\" during GameView construction")
    else:
        ok(f"all {len(wanted_draw)} SpriteCatalog drawables are packaged")

    missing_raw = sorted(wanted_raw - raws)
    if missing_raw:
        warn(f"AudioEngine sounds not packaged (silently skipped at runtime): {missing_raw}")
    else:
        ok(f"all {len(wanted_raw)} AudioEngine sounds are packaged")

    code_strings = set()
    for _, d in dex_infos(z):
        if d[:4] == b"dex\n":
            code_strings.update(dex_strings(d))
    code_refs = {s for s in code_strings if s in drawables or s in raws}
    orphans = sorted(drawables - code_refs - {"ic_launcher_background", "ic_launcher_foreground"})
    if orphans:
        warn(f"{len(orphans)} packaged drawables are never referenced by code: {orphans[:6]}")
    else:
        ok("no orphaned drawables")


def check_sprite_strips() -> None:
    section("6. Sprite strip geometry (CURRENT source tree)")
    src = open(os.path.join(SRC, "SpriteCatalog.kt")).read()
    strips = re.findall(r'loadStrip\("([^"]+)"\)', src)
    bad, missing = [], []
    for n in strips:
        hits = glob.glob(os.path.join(RES, "drawable*", n + ".png"))
        if not hits:
            missing.append(n)
            continue
        with open(hits[0], "rb") as f:
            head = f.read(24)
        if head[:8] != b"\x89PNG\r\n\x1a\n":
            bad.append((n, "not a PNG", 0))
            continue
        w, h = struct.unpack(">II", head[16:24])
        if h == 0 or w % h != 0:
            bad.append((n, f"{w}x{h}", w % h if h else -1))
    if missing:
        fail(f"loadStrip() names with no PNG in res/: {missing}")
    if bad:
        fail("strips violating check(width % height == 0) - IllegalStateException at startup:")
        for n, dim, rem in bad:
            print(f"          {n}: {dim}")
    if not missing and not bad:
        ok(f"all {len(strips)} sprite strips have square horizontal frames")


def verify(path: str) -> None:
    print(f"\n\033[1m=== {os.path.relpath(path, REPO)} ===\033[0m")
    data = open(path, "rb").read()
    print(f"  size    {len(data):,} bytes")
    print(f"  sha256  {hashlib.sha256(data).hexdigest()}")
    with zipfile.ZipFile(path) as z:
        print(f"  entries {len(z.infolist())}")
        check_zip(path, z)
        check_signing(path)
        check_dex(z)
        check_manifest(z)
        check_resources(z)
    check_sprite_strips()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", nargs="*", help="APK path(s) to verify")
    ap.add_argument("--all", action="store_true", help="verify every APK in artifacts/")
    args = ap.parse_args()

    targets = list(args.apk)
    if args.all or not targets:
        targets = sorted(glob.glob(os.path.join(REPO, "artifacts", "*.apk")))
    for t in targets:
        verify(t)

    print("\n" + "=" * 72)
    if failures:
        print(f"\033[31m{len(failures)} FAILURE(S)\033[0m")
        for f in failures:
            print(f"  - {f}")
    if warnings:
        print(f"\033[33m{len(warnings)} warning(s)\033[0m")
    if not failures:
        print("\033[32mAll structural checks passed.\033[0m")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
