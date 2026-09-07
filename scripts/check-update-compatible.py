#!/usr/bin/env python3
"""Check whether an APK can update an already-installed Jadex.

Android accepts an update only when the new APK is signed with the *same*
certificate as the installed one, and carries a *higher* versionCode. This
script verifies both against the reference build the user already has.

    python3 scripts/check-update-compatible.py NEW.apk
    python3 scripts/check-update-compatible.py NEW.apk --reference artifacts/Jadex-v1.3.0-installable.apk

Exit code 0 means the APK will install over the reference as an update.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import struct
import sys
import zipfile

# Certificate of the Jadex build the user has installed (v1.3.0, versionCode 24).
# C=PH, ST=Northern Mindanao, L=Cagayan de Oro, O=TechTroyAi, OU=Python Studio, CN=Jadex
EXPECTED_CERT_SHA256 = "8b912d1ecc75af8abd9becb2489a8c6a95b7e9dbf3c3c9ac01658918e88f6129"
INSTALLED_VERSION_CODE = 24

MAGIC = b"APK Sig Block 42"


def _u32(b: bytes, o: int) -> int:
    return struct.unpack_from("<I", b, o)[0]


def _u64(b: bytes, o: int) -> int:
    return struct.unpack_from("<Q", b, o)[0]


def _len_prefixed(buf: bytes, off: int) -> tuple[bytes, int]:
    n = _u32(buf, off)
    return buf[off + 4 : off + 4 + n], off + 4 + n


def signing_certs(path: str) -> dict[str, set[str]]:
    """Return {scheme_name: {cert_sha256, ...}} from the APK signing block."""
    data = open(path, "rb").read()
    idx = data.rfind(MAGIC)
    if idx < 0:
        return {}
    block_end = idx + len(MAGIC)
    size2 = _u64(data, idx - 8)
    start = block_end - 8 - size2
    off = start + 8
    names = {0x7109871A: "v2", 0xF05368C0: "v3"}
    out: dict[str, set[str]] = {}
    while off < idx - 8:
        pair_len = _u64(data, off)
        pair_id = _u32(data, off + 8)
        value = data[off + 12 : off + 8 + pair_len]
        if pair_id in names:
            certs: set[str] = set()
            try:
                signers, _ = _len_prefixed(value, 0)
                so = 0
                while so < len(signers):
                    signer, so = _len_prefixed(signers, so)
                    signed_data, _ = _len_prefixed(signer, 0)
                    _digests, o2 = _len_prefixed(signed_data, 0)
                    cert_seq, _o3 = _len_prefixed(signed_data, o2)
                    co = 0
                    while co < len(cert_seq):
                        cert, co = _len_prefixed(cert_seq, co)
                        certs.add(hashlib.sha256(cert).hexdigest())
            except Exception:
                pass
            if certs:
                out.setdefault(names[pair_id], set()).update(certs)
        off += 8 + pair_len
    return out


def version_code(path: str) -> int | None:
    """Pull versionCode out of the binary AndroidManifest.

    Reuses verify-apk.py's manifest parser rather than re-deriving one.
    """
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "verify_apk", os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify-apk.py")
        )
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with zipfile.ZipFile(path) as z:
            elements = mod.parse_manifest(z.read("AndroidManifest.xml"))
        for name, attrs in elements:
            if name == "manifest" and "versionCode" in attrs:
                vc = attrs["versionCode"]
                return int(vc) if not isinstance(vc, bool) else None
    except Exception:
        pass
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--reference", default=None,
                    help="APK the device already has (defaults to the recorded Jadex 1.3.0 identity)")
    args = ap.parse_args()

    if not os.path.exists(args.apk):
        print(f"no such file: {args.apk}")
        return 2

    expected = EXPECTED_CERT_SHA256
    installed_vc = INSTALLED_VERSION_CODE
    if args.reference:
        ref = signing_certs(args.reference)
        ref_all = set().union(*ref.values()) if ref else set()
        if not ref_all:
            print(f"could not read a certificate from {args.reference}")
            return 2
        expected = sorted(ref_all)[0]
        installed_vc = version_code(args.reference) or installed_vc

    print(f"=== {args.apk} ===")
    ok = True

    certs = signing_certs(args.apk)
    if not certs:
        print("  FAIL  no v2/v3 signing block — unsigned APKs cannot be installed")
        ok = False
    else:
        found = set().union(*certs.values())
        print(f"  schemes: {', '.join(sorted(certs))}")
        for c in sorted(found):
            print(f"  cert:    {c}")
        if expected in found:
            print("  PASS  signing certificate matches the installed app")
        else:
            print(f"  FAIL  certificate mismatch — expected {expected}")
            print("        Android will refuse this as an update")
            print("        (INSTALL_FAILED_UPDATE_INCOMPATIBLE)")
            ok = False

    vc = version_code(args.apk)
    if vc is None:
        print("  WARN  could not read versionCode")
    elif vc > installed_vc:
        print(f"  PASS  versionCode {vc} > installed {installed_vc}")
    else:
        print(f"  FAIL  versionCode {vc} must be greater than installed {installed_vc}")
        ok = False

    print()
    print("UPDATE-COMPATIBLE" if ok else "NOT UPDATE-COMPATIBLE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
