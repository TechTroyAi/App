#!/usr/bin/env python3
"""Length-preserving binary-AXML version bump for AndroidManifest.xml.

Used by the offline APK pipeline (no Android SDK): replaces the `versionName`
string-pool entry and the `versionCode` integer attribute in place, without
changing the file size, so the patched manifest can be swapped straight into
a repackaged APK.

Constraints enforced:
  * the new versionName must encode to the SAME number of UTF-16 code units as
    the old one (otherwise the string pool would shift and every offset after
    it would be wrong);
  * exactly one string-pool entry may match the old versionName;
  * the versionCode attribute must be an integer-typed attribute on the
    <manifest> element.

Usage:
    python3 scripts/bump-manifest-version.py <manifest-in> <manifest-out> \
        --old-name 1.4.1 --new-name 1.4.2 --old-code 15 --new-code 16
"""
from __future__ import annotations

import argparse
import struct
import sys


def string_pool(d: bytes) -> tuple[list[str], int, int, bool]:
    """Returns (strings, strings_start_abs_offset, count, utf8)."""
    off = 8
    size = struct.unpack_from("<I", d, off + 4)[0]
    count = struct.unpack_from("<I", d, off + 8)[0]
    flags = struct.unpack_from("<I", d, off + 16)[0]
    start = struct.unpack_from("<I", d, off + 20)[0]
    utf8 = bool((flags >> 8) & 1)
    out = []
    offsets = []
    for i in range(count):
        so = off + start + struct.unpack_from("<I", d, off + 28 + 4 * i)[0]
        offsets.append(so)
        if utf8:
            n = d[so]
            so2 = so + (2 if n & 0x80 else 1)
            ln = d[so2]
            so2 += 2 if ln & 0x80 else 1
            out.append(d[so2:so2 + ln].decode("utf-8", "replace"))
        else:
            ln = struct.unpack_from("<H", d, so)[0]
            out.append(d[so + 2:so + 2 + ln * 2].decode("utf-16le", "replace"))
    return out, offsets, count, utf8


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--old-name", required=True)
    ap.add_argument("--new-name", required=True)
    ap.add_argument("--old-code", type=int, required=True)
    ap.add_argument("--new-code", type=int, required=True)
    args = ap.parse_args()

    d = bytearray(open(args.src, "rb").read())
    strings, offsets, count, utf8 = string_pool(d)

    if utf8:
        old_b, new_b = args.old_name.encode("utf-8"), args.new_name.encode("utf-8")
        if len(old_b) != len(new_b):
            sys.exit(f"error: UTF-8 pool lengths differ ({len(old_b)} vs {len(new_b)}); "
                     "this patcher is length-preserving only")
    else:
        if len(args.old_name) != len(args.new_name):
            sys.exit(f"error: UTF-16 pool lengths differ "
                     f"({len(args.old_name)} vs {len(args.new_name)} chars); "
                     "this patcher is length-preserving only")

    hits = [i for i, s in enumerate(strings) if s == args.old_name]
    if len(hits) != 1:
        sys.exit(f"error: expected exactly one string-pool entry {args.old_name!r}, found {len(hits)}")
    idx = hits[0]
    so = offsets[idx]
    if utf8:
        n = d[so]
        data_off = so + (2 if n & 0x80 else 1)
        ln = d[data_off]
        data_off += 2 if ln & 0x80 else 1
        d[data_off:data_off + len(old_b)] = new_b
    else:
        d[so + 2:so + 2 + len(args.new_name) * 2] = args.new_name.encode("utf-16le")
    print(f"string pool [{idx}]: {args.old_name!r} -> {args.new_name!r}")

    # Walk chunks to the <manifest> START_ELEMENT and patch versionCode.
    strs_no_off = strings
    o = 8 + struct.unpack_from("<I", d, 12)[0]
    if struct.unpack_from("<H", d, o)[0] == 0x0180:  # skip resource-id map
        cs = struct.unpack_from("<I", d, o + 4)[0]
        o += cs
    name_idx_version_code = strs_no_off.index("versionCode")
    patched = False
    while o < len(d) - 8:
        t = struct.unpack_from("<H", d, o)[0]
        hs = struct.unpack_from("<H", d, o + 2)[0]
        cs = struct.unpack_from("<I", d, o + 4)[0]
        if t == 0x0102:  # START_ELEMENT
            name = strs_no_off[struct.unpack_from("<I", d, o + 20)[0]]
            if name == "manifest":
                astart = struct.unpack_from("<H", d, o + 24)[0]
                asize = struct.unpack_from("<H", d, o + 26)[0]
                acount = struct.unpack_from("<H", d, o + 28)[0]
                for i in range(acount):
                    ao = o + hs + astart + i * asize
                    an = struct.unpack_from("<I", d, ao + 4)[0]
                    if an != name_idx_version_code:
                        continue
                    dt = d[ao + 15]
                    dv = struct.unpack_from("<I", d, ao + 16)[0]
                    if dt not in (0x10, 0x11):
                        sys.exit(f"error: versionCode typed value is not an integer (dt=0x{dt:02x})")
                    if dv != args.old_code:
                        sys.exit(f"error: versionCode is {dv}, expected {args.old_code}")
                    struct.pack_into("<I", d, ao + 16, args.new_code)
                    patched = True
                break
        if cs == 0:
            break
        o += cs
    if not patched:
        sys.exit("error: versionCode attribute not found on <manifest>")
    print(f"versionCode: {args.old_code} -> {args.new_code}")

    if len(d) != len(open(args.src, "rb").read()):
        sys.exit("error: output size changed")
    open(args.dst, "wb").write(d)
    print(f"OK: {args.dst} ({len(d)} bytes, size unchanged)")


if __name__ == "__main__":
    main()
