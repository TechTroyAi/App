#!/usr/bin/env python3
"""Structural verifier for Notebook by Troy APKs (ai.techtroy.notebook).

The Notebook counterpart of scripts/verify-apk.py (which is Blockhold-specific in
its resource checks). It reuses that script's ZIP / signing / DEX / manifest
parsers and adds the checks that matter for *this* app - the ones that catch
"installs fine but crashes when you tap X" defects - using only the Python
standard library (no Android SDK required):

  1. ZIP layout      - resources.arsc stored, every uncompressed entry 4-byte
                       aligned (zipalign contract).
  2. Signing         - v2/v3 APK Signing Block present, and the signer
                       certificate's SHA-256 matches the documented Notebook
                       release key (so an update will install over v1.0.0).
  3. DEX integrity   - magic, sizes, adler32, SHA-1, every referenced type
                       defined somewhere in classes*.dex.
  4. AndroidManifest - package, versionName/versionCode (cross-checked against
                       notebook/build.gradle.kts), SDK levels, exported
                       launcher, and EVERY declared activity / service /
                       receiver / provider present in the DEX (a missing widget
                       receiver is a crash the moment the widget is added).
                       Also asserts the offline promise: no INTERNET permission.
  5. Resources       - app package id 0x7f, every layout and xml file in
                       notebook/src/main/res packaged, launcher mipmaps present,
                       manifest icon/theme references resolve.
  6. Source classes  - every top-level class/object/interface declared in
                       notebook/src/main/java is defined in the DEX files
                       (catches a module built from a stale tree).

Usage:
    python3 scripts/verify-notebook-apk.py artifacts/Notebook-by-Troy-v1.0.0.apk
    python3 scripts/verify-notebook-apk.py --allow-unsigned notebook/build/outputs/apk/release/*.apk
    python3 scripts/verify-notebook-apk.py            # every artifacts/Notebook-by-Troy-*.apk

Exit code is non-zero if any FAIL-level check trips.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import importlib.util
import os
import re
import struct
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE = os.path.join(REPO, "notebook")
SRC = os.path.join(MODULE, "src", "main", "java")
RES = os.path.join(MODULE, "src", "main", "res")
PACKAGE = "ai.techtroy.notebook"
LAUNCHER = PACKAGE + ".ui.HomeActivity"

# SHA-256 of the Notebook release certificate (docs/notebook-release-certificate.txt,
# docs/SIGNING.md). Every Notebook release must be signed with this key.
RELEASE_CERT_SHA256 = "b2c9ed0a42e6572990de3972a4576135137ddbfea63a5897aac0bebcf25c3c4f"

# Reuse the battle-tested parsers from the Blockhold verifier instead of forking them.
_spec = importlib.util.spec_from_file_location("verify_apk", os.path.join(REPO, "scripts", "verify-apk.py"))
V = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(V)  # type: ignore[union-attr]
ok, warn, fail, section = V.ok, V.warn, V.fail, V.section


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def _u32(d: bytes, o: int) -> int:
    return struct.unpack_from("<I", d, o)[0]


def signer_certificates(path: str) -> list[bytes]:
    """Return the DER certificates of the first signer in the v2 (or v3) signing block."""
    d = open(path, "rb").read()
    eocd = d.rfind(b"PK\x05\x06")
    cdoff = _u32(d, eocd + 16)
    if d[cdoff - 16:cdoff] != b"APK Sig Block 42":
        return []
    size = struct.unpack_from("<Q", d, cdoff - 24)[0]
    start = cdoff - 8 - size
    blk = d[start + 8:cdoff - 24]
    pairs: dict[int, bytes] = {}
    o = 0
    while o < len(blk) - 12:
        ln = struct.unpack_from("<Q", blk, o)[0]
        if ln < 4:
            break
        pairs[_u32(blk, o + 8)] = blk[o + 12:o + 8 + ln]
        o += 8 + ln
    certs: list[bytes] = []
    for scheme in (0x7109871A, 0xF05368C0):  # v2, then v3
        v = pairs.get(scheme)
        if not v:
            continue
        # signers := len-prefixed sequence of (len-prefixed signer)
        signers = v[4:4 + _u32(v, 0)]
        signer = signers[4:4 + _u32(signers, 0)]
        signed = signer[4:4 + _u32(signer, 0)]
        p = 4 + _u32(signed, 0)               # skip digests
        cert_seq = signed[p + 4:p + 4 + _u32(signed, p)]
        q = 0
        while q + 4 <= len(cert_seq):
            ln = _u32(cert_seq, q)
            certs.append(cert_seq[q + 4:q + 4 + ln])
            q += 4 + ln
        if certs:
            break
    return certs


def gradle_versions() -> tuple[str | None, int | None]:
    try:
        g = open(os.path.join(MODULE, "build.gradle.kts")).read()
    except OSError:
        return None, None
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', g)
    vc = re.search(r"versionCode\s*=\s*(\d+)", g)
    return (vn.group(1) if vn else None), (int(vc.group(1)) if vc else None)


def source_classes() -> set[str]:
    """Top-level class/object/interface declarations in the module, as JVM descriptors."""
    decl = re.compile(
        r"^(?:(?:public|internal|private|abstract|open|data|enum|sealed|annotation|final|inner|value)\s+)*"
        r"(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
    out: set[str] = set()
    for path in glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True):
        text = open(path, encoding="utf-8").read()
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        for line in text.splitlines():
            m = decl.match(line)
            if m:
                out.add("L" + pkg.group(1).replace(".", "/") + "/" + m.group(1) + ";")
    return out


# --------------------------------------------------------------------------
# Notebook-specific checks
# --------------------------------------------------------------------------

def check_certificate(path: str, allow_unsigned: bool, expected: str | None) -> None:
    certs = signer_certificates(path)
    if not certs:
        (warn if allow_unsigned else fail)("no signer certificate found (APK is unsigned)")
        return
    fp = hashlib.sha256(certs[0]).hexdigest()
    ok(f"signer certificate SHA-256: {':'.join(fp[i:i + 2] for i in range(0, 64, 2)).upper()}")
    if expected:
        if fp == expected.replace(":", "").lower():
            ok("certificate matches the documented Notebook release key - updates will install over it")
        else:
            fail("certificate does NOT match the documented Notebook release key - Android will refuse "
                 "to update an existing install (INSTALL_FAILED_UPDATE_INCOMPATIBLE)")


def check_notebook_manifest(z: zipfile.ZipFile) -> tuple[dict, list]:
    section("4b. Notebook manifest contract (vs. CURRENT source tree)")
    els = V.parse_manifest(z.read("AndroidManifest.xml"))
    by: dict[str, list[dict]] = {}
    for name, attrs in els:
        by.setdefault(name, []).append(attrs)
    man = by.get("manifest", [{}])[0]
    pkg = man.get("package")
    if pkg == PACKAGE:
        ok(f"package is {PACKAGE}")
    else:
        fail(f"package is {pkg}, expected {PACKAGE}")

    vn, vc = gradle_versions()
    if vn and vc is not None:
        if man.get("versionName") == vn and man.get("versionCode") == vc:
            ok(f"versionName {vn} / versionCode {vc} match notebook/build.gradle.kts")
        else:
            warn(f"APK is {man.get('versionName')} ({man.get('versionCode')}) but build.gradle.kts says "
                 f"{vn} ({vc}) - artifact predates the current source")

    sdk = by.get("uses-sdk", [{}])[0]
    if sdk.get("minSdkVersion") == 24 and sdk.get("targetSdkVersion") == 35:
        ok("minSdk 24 (Android 7.0) / targetSdk 35")
    else:
        fail(f"unexpected SDK levels: {sdk}")

    launcher = None
    for a in by.get("activity", []):
        nm = str(a.get("name", ""))
        full = pkg + nm if nm.startswith(".") else nm
        if full == LAUNCHER:
            launcher = a
    if launcher is None:
        fail(f"{LAUNCHER} is not declared")
    elif launcher.get("exported") is True:
        ok(f"{LAUNCHER} declared and exported")
    else:
        fail(f"{LAUNCHER} is not android:exported=true")

    defined = V.all_dex_defined(z)
    missing, total = [], 0
    for kind in ("activity", "service", "receiver", "provider"):
        for c in by.get(kind, []):
            nm = str(c.get("name", ""))
            if not nm:
                continue
            full = pkg + nm if nm.startswith(".") else nm
            total += 1
            if "L" + full.replace(".", "/") + ";" not in defined:
                missing.append(f"{kind} {full}")
    if missing:
        fail(f"{len(missing)} manifest component(s) are missing from the DEX: {missing[:5]} "
             f"- ClassNotFoundException the moment Android instantiates them")
    else:
        ok(f"all {total} declared components (activities, services, receivers, provider) exist in the DEX")

    perms = sorted(str(p.get("name", "")).split(".")[-1] for p in by.get("uses-permission", []))
    if "INTERNET" in perms or "ACCESS_NETWORK_STATE" in perms:
        fail("INTERNET permission requested - Notebook is meant to be offline by construction")
    else:
        ok("no INTERNET permission - the app cannot talk to the network")
    ok(f"{len(perms)} permissions: {', '.join(perms)}")

    icon = man.get("icon")
    app = by.get("application", [{}])[0]
    return app, list(V.dex_infos(z))


def check_notebook_resources(z: zipfile.ZipFile, app: dict) -> None:
    section("5. Resource table vs. notebook/src/main/res (CURRENT source tree)")
    table = V.parse_arsc(z.read("resources.arsc"))
    ids: set[int] = set()
    for entries in table.values():
        ids.update(entries.values())
    pkg_ids = {i >> 24 for i in ids}
    if pkg_ids == {0x7F}:
        ok(f"resource table: {len(ids)} entries, all in app package 0x7f")
    else:
        fail(f"unexpected resource package ids {sorted(hex(p) for p in pkg_ids)}")

    for typ in ("layout", "xml", "anim", "menu"):
        want = {os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(RES, typ, "*.xml"))}
        if not want:
            continue
        have = set(table.get(typ, {}))
        missing = sorted(want - have)
        if missing:
            fail(f"{typ}: {len(missing)} source file(s) not in the APK: {missing[:6]}")
        else:
            ok(f"{typ}: all {len(want)} source files packaged ({len(have)} total incl. libraries)")

    mip = table.get("mipmap", {})
    if {"ic_launcher", "ic_launcher_round"} <= set(mip):
        ok("launcher mipmaps ic_launcher + ic_launcher_round present (adaptive icon)")
    else:
        fail(f"launcher mipmaps missing: have {sorted(mip)}")

    for key in ("icon", "theme"):
        ref = app.get(key)
        if isinstance(ref, str) and ref.startswith("@0x"):
            rid = int(ref[3:], 16)
            if rid in ids:
                ok(f"application {key} {ref} resolves in the resource table")
            else:
                fail(f"application {key} {ref} does not resolve - install-time parse failure")

    strings = table.get("string", {})
    for s in ("app_name", "easter_egg"):
        if s in strings:
            ok(f"string/{s} present")
        else:
            fail(f"string/{s} missing")

    for x in ("widget_sticky_info", "widget_list_info", "widget_new_info", "file_paths", "shortcuts"):
        if x not in table.get("xml", {}):
            fail(f"xml/{x} missing - widget / FileProvider / shortcuts metadata would fail to inflate")


def check_source_classes(z: zipfile.ZipFile) -> None:
    section("6. Source classes vs. DEX (CURRENT source tree)")
    want = source_classes()
    defined = V.all_dex_defined(z)
    missing = sorted(want - defined)
    if not want:
        warn("no Kotlin sources found under notebook/src/main/java - skipped")
    elif missing:
        fail(f"{len(missing)} of {len(want)} source classes are not in the DEX: {missing[:6]} "
             f"- the APK was built from a different source tree")
    else:
        ok(f"all {len(want)} top-level classes declared in notebook/src/main/java are in the DEX")
    app_classes = sum(1 for c in defined if c.startswith("L" + PACKAGE.replace(".", "/") + "/"))
    ok(f"{app_classes} app classes bundled in total (incl. nested/lambda classes)")


def verify(path: str, allow_unsigned: bool, cert: str | None) -> None:
    print(f"\n\033[1m=== {os.path.relpath(path, REPO)} ===\033[0m")
    data = open(path, "rb").read()
    print(f"  size    {len(data):,} bytes")
    print(f"  sha256  {hashlib.sha256(data).hexdigest()}")
    with zipfile.ZipFile(path) as z:
        print(f"  entries {len(z.infolist())}")
        V.check_zip(path, z)
        if allow_unsigned:
            # CI builds unsigned (signing happens locally): report a missing signature as a
            # warning instead of a failure, without touching the shared verifier module.
            real_fail = V.fail
            V.fail = lambda msg: warn("(unsigned build accepted) " + msg)
            try:
                V.check_signing(path)
            finally:
                V.fail = real_fail
        else:
            V.check_signing(path)
        check_certificate(path, allow_unsigned, cert)
        V.check_dex(z)
        V.check_manifest(z)
        app, _ = check_notebook_manifest(z)
        check_notebook_resources(z, app)
        check_source_classes(z)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", nargs="*", help="APK path(s); default: artifacts/Notebook-by-Troy-*.apk")
    ap.add_argument("--allow-unsigned", action="store_true",
                    help="accept an unsigned APK (CI builds unsigned; signing happens locally)")
    ap.add_argument("--cert-sha256", default=RELEASE_CERT_SHA256,
                    help="expected signer certificate SHA-256 ('' to skip the comparison)")
    args = ap.parse_args()
    targets = list(args.apk) or sorted(glob.glob(os.path.join(REPO, "artifacts", "Notebook-by-Troy-*.apk")))
    if not targets:
        print("no APKs to verify")
        return 1
    for t in targets:
        verify(t, args.allow_unsigned, args.cert_sha256 or None)

    print("\n" + "=" * 72)
    if V.failures:
        print(f"\033[31m{len(V.failures)} FAILURE(S)\033[0m")
        for f in V.failures:
            print(f"  - {f}")
    if V.warnings:
        print(f"\033[33m{len(V.warnings)} warning(s)\033[0m")
    if not V.failures:
        print("\033[32mAll Notebook structural checks passed.\033[0m")
    return 1 if V.failures else 0


if __name__ == "__main__":
    sys.exit(main())
