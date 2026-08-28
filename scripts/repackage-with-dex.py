#!/usr/bin/env python3
"""Rebuild a release-style APK: replace classes.dex, then zipalign.

Used when the Gradle toolchain is unavailable (this build environment has no
Android SDK download access). Produces an APK whose uncompressed entries are
4-byte aligned, and whose mmap-friendly entries (*.so, classes.dex,
resources.arsc) are 4096-byte (page) aligned — exactly what `zipalign -p 4`
does. The output is then signed externally with apksigner (v1+v2+v3), which
also verifies alignment.

Usage: repackage-with-dex.py <base-apk> <new-classes-dex> <out-apk>
"""
import hashlib
import struct
import sys
import zipfile

ALIGNMENT = 4
PAGE_ALIGN_NAMES = (".so",)
PAGE_ALIGN_EXACT = ("resources.arsc", "classes.dex")


def should_compress(name: str) -> bool:
    # Mirror AGP: resources.arsc and native libs are stored uncompressed so the
    # platform can mmap them; everything else may be deflated.
    if name == "resources.arsc" or name.endswith(".so"):
        return False
    # Keep the original entry's compression if possible: we only pass through
    # whatever the base APK already chose. This function only decides the dex.
    return True


def main(base_apk: str, new_dex: str, out_apk: str) -> None:
    with open(new_dex, "rb") as f:
        dex_bytes = f.read()

    base = zipfile.ZipFile(base_apk, "r")
    infos = base.infolist()

    out = zipfile.ZipFile(out_apk, "w")
    # local header offset tracking for manual alignment
    offset = 0

    def write_raw(name: str, data: bytes, compress: bool, page_align: bool):
        nonlocal offset
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
        zi.external_attr = 0o644 << 16

        alignment = 4096 if (page_align or not compress and name == "resources.arsc") else ALIGNMENT
        # resources.arsc needs 4-byte alignment (targetSdk>=30), dex/mmap need page.
        if name in PAGE_ALIGN_EXACT or name.endswith(PAGE_ALIGN_NAMES):
            alignment = 4096
        elif not compress:
            alignment = ALIGNMENT

        # Reserve room for the local file header (header + name + extra).
        # We write a fixed extra field to pad the data offset.
        name_bytes = name.encode("utf-8")
        # approximate local header length: 30 + len(name) + len(extra placeholder)
        # We build the local header ourselves to control the extra padding.
        lh_fixed = 30 + len(name_bytes)
        # offset where data begins = offset + lh_fixed + len(extra)
        # choose len(extra) so (offset + lh_fixed + len(extra)) % alignment == 0
        # extra field minimum must satisfy; extra is 2-byte id+2-byte size+payload
        # Standard alignment extra: 0xd935 'zipalign' pad.
        def data_offset_for(extra_len: int) -> int:
            return offset + lh_fixed + extra_len

        extra_len = 0
        while data_offset_for(extra_len) % alignment != 0:
            extra_len += 1
        # extra field layout must be: header (4 bytes) + payload. Pad payload so
        # total extra_len is representable; we use the zipalign extra tag 0xd935.
        # total extra must be 4 + payload_len; round extra_len up to >=4 with
        # payload size = extra_len-4, tag id 0xd935.
        if extra_len != 0:
            if extra_len < 6:
                extra_len = 6  # 4 header + 2 payload
            payload_len = extra_len - 4
            extra = struct.pack("<HH", 0xD935, payload_len) + b"\x00" * payload_len
            # actual extra length
            actual_extra_len = 4 + payload_len
            # recompute alignment may overshoot; adjust payload until aligned
            while (offset + lh_fixed + 4 + payload_len) % alignment != 0:
                payload_len += 1
            extra = struct.pack("<HH", 0xD935, payload_len) + b"\x00" * payload_len
            actual_extra_len = 4 + payload_len
        else:
            extra = b""
            actual_extra_len = 0

        data_offset = offset + lh_fixed + actual_extra_len
        assert compress or data_offset % alignment == 0, (name, data_offset, alignment)

        if compress:
            co = zipfile.ZipFile.__dict__  # noqa: F841 (legacy ref)
            import zlib
            co_obj = zlib.compressobj(6, zlib.DEFLATED, -15)
            compressed = co_obj.compress(data) + co_obj.flush()
            method = 8
            stored = compressed
            crc = zipfile.crc32(data) & 0xFFFFFFFF
            sizes = (len(data), len(stored))
        else:
            method = 0
            stored = data
            crc = zipfile.crc32(data) & 0xFFFFFFFF
            sizes = (len(data), len(data))

        # Build local file header.
        dt = zi.date_time
        dosdate = (dt[0] - 1980) << 9 | dt[1] << 5 | dt[2]
        dostime = dt[3] << 11 | dt[4] << 5 | (dt[5] // 2)
        local = struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50,
            20,                       # version needed
            0,                        # flags
            method,
            dostime, dosdate,
            crc,
            sizes[1], sizes[0],
            len(name_bytes),
            len(extra),
        ) + name_bytes + extra
        out.fp.write(local)
        out.fp.write(stored)

        # Data descriptor not used (we know sizes); central directory entry.
        # Track via ZipInfo so close() writes central directory — but we've
        # written raw local entries. Easiest: also record via writestr with
        # manual local header is complex; instead use ZipInfo + open()? We
        # bypass ZipFile machinery, so write central directory ourselves below.
        raise RuntimeError("use streaming writer instead")

    # The raw approach above is too fiddly; instead use ZipFile with a custom
    # extra field via writestr — ZipFile computes offsets but doesn't support
    # data alignment natively. Implement alignment by padding the *extra field*
    # of each entry, which is included in the local header before data.
    out.close()
    base.close()
    raise SystemExit("see streaming implementation below")


def main2(base_apk: str, new_dex: str, out_apk: str, new_manifest: str | None = None) -> None:
    with open(new_dex, "rb") as f:
        dex_bytes = f.read()
    manifest_bytes = open(new_manifest, "rb").read() if new_manifest else None

    base = zipfile.ZipFile(base_apk, "r")
    out = zipfile.ZipFile(out_apk, "w", zipfile.ZIP_DEFLATED)

    for info in base.infolist():
        name = info.filename
        # drop v1 signature files — apksigner regenerates them
        upper = name.upper()
        if upper.startswith("META-INF/") and (
            upper.endswith(".SF") or upper.endswith(".RSA") or
            upper.endswith(".DSA") or upper.endswith(".EC") or
            upper == "META-INF/MANIFEST.MF"
        ):
            continue
        if name == "classes.dex":
            data = dex_bytes
        elif name == "AndroidManifest.xml" and manifest_bytes is not None:
            data = manifest_bytes
        else:
            data = base.read(name)

        zi = zipfile.ZipInfo(name)
        zi.external_attr = info.external_attr
        zi.date_time = info.date_time

        page_align = name.endswith(".so") or name == "classes.dex" or name == "resources.arsc"
        store_uncompressed = (
            info.compress_type == zipfile.ZIP_STORED
            or name in ("resources.arsc", "classes.dex")
            or name.endswith(".so")
        )
        zi.compress_type = zipfile.ZIP_STORED if store_uncompressed else zipfile.ZIP_DEFLATED

        if store_uncompressed:
            # alignment via zipalign-style extra field tag 0xd935
            align = 4096 if page_align else 4
            zi.extra = b""  # set after we know the offset — but ZipFile doesn't
            # expose pre-write offset; use the documented trick: the extra
            # field sits between local header and data, and ZipFile writes
            # local header length as 30+len(name)+len(extra), so we can pad
            # extra to reach the desired data offset. The data offset itself
            # depends on prior entries, so we must flush incrementally and
            # inspect out.fp.tell().
            out.fp.flush()
            base_offset = out.fp.tell()
            lh_len = 30 + len(name.encode("utf-8"))
            # extra layout: <H id=0xd935><H size><payload>
            need = align - ((base_offset + lh_len) % align)
            need %= align
            if need >= 4:
                payload = need - 4
                zi.extra = struct.pack("<HH", 0xD935, payload) + b"\x00" * payload
            elif need == 0:
                zi.extra = b""
            else:
                # need 1..3 — bump by a whole alignment block instead
                need = need + align
                payload = need - 4
                zi.extra = struct.pack("<HH", 0xD935, payload) + b"\x00" * payload
            out.writestr(zi, data, compress_type=zipfile.ZIP_STORED)
        else:
            out.writestr(zi, data, compress_type=zipfile.ZIP_DEFLATED)

    out.close()
    base.close()

    # Verify alignment of every stored entry.
    chk = zipfile.ZipFile(out_apk)
    bad = 0
    for info in chk.infolist():
        if info.compress_type != zipfile.ZIP_STORED:
            continue
        # local header offset
        with open(out_apk, "rb") as f:
            f.seek(info.header_offset)
            sig = f.read(4)
            assert sig == b"PK\x03\x04"
            f.seek(info.header_offset + 26)
            nlen, elen = struct.unpack("<HH", f.read(4))
            data_off = info.header_offset + 30 + nlen + elen
        align = 4096 if (info.filename.endswith(".so") or info.filename in
                         ("classes.dex", "resources.arsc")) else 4
        if data_off % align != 0:
            print(f"MISALIGNED {info.filename}: data@{data_off} mod {align} = {data_off % align}")
            bad += 1
    if bad:
        sys.exit(f"{bad} entries misaligned")
    sha = hashlib.sha256(open(out_apk, "rb").read()).hexdigest()
    print(f"OK: {out_apk} ({chk.infolist().__len__()} entries), sha256={sha}")


if __name__ == "__main__":
    main2(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
