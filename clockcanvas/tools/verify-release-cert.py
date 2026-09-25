#!/usr/bin/env python3
"""Check that an APK was signed with the ClockCanvas release certificate.

The certificate SHA-256, not the key, is public information, so `artifacts/
ClockCanvas-release.cert` records it and this script compares. It answers the one
question that decides whether a build can be installed over an existing ClockCanvas:
same key = update, different key = users must uninstall (losing their designs).

    python3 tools/verify-release-cert.py artifacts/ClockCanvas-v1.0.0-installable.apk
    python3 tools/verify-release-cert.py --record            # refresh the record file
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

MODULE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECORD = os.path.join(MODULE, "artifacts", "ClockCanvas-release.cert")
TOOLS = os.environ.get("CC_TOOLS", os.path.join(os.path.expanduser("~"), ".cc-tools"))


def java() -> str:
    path = os.path.join(TOOLS, "java.path")
    if os.path.exists(path):
        found = open(path).read().strip()
        if os.path.exists(found):
            return found
    return "java"


def cert_of(apk: str) -> str:
    signer = os.path.join(TOOLS, "apksigner.jar")
    if os.path.exists(signer):
        out = subprocess.run(
            [java(), "-jar", signer, "verify", "--print-certs", apk],
            capture_output=True, text=True,
        )
        text = out.stdout + out.stderr
    else:
        keytool = os.path.join(os.path.dirname(java()), "keytool")
        text = subprocess.run([keytool, "-printcert", "-jarfile", apk], capture_output=True, text=True).stdout
    match = re.search(r"(?:certificate )?SHA-?256:\s*([0-9a-fA-F]{64})", text)
    if not match:
        # apksigner prints "Signer #1 certificate SHA-256 digest: <hex>".
        match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]{64})", text)
    if not match:
        raise SystemExit("could not read a certificate from " + apk + "\n" + text[-800:])
    return match.group(1).lower()


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--record"]
    if not args:
        raise SystemExit(__doc__)
    apk = args[0]
    if not os.path.exists(apk):
        raise SystemExit("no such APK: " + apk)
    actual = cert_of(apk)
    if "--record" in sys.argv:
        os.makedirs(os.path.dirname(RECORD), exist_ok=True)
        with open(RECORD, "w") as handle:
            handle.write(actual + "\n")
        print("recorded " + actual + " -> " + os.path.relpath(RECORD, MODULE))
        return 0
    if not os.path.exists(RECORD):
        print("WARN  no certificate record at artifacts/ClockCanvas-release.cert; run --record")
        print("      actual: " + actual)
        return 0
    expected = open(RECORD).read().strip().lower()
    if expected == actual:
        print("PASS  signed with the recorded release certificate (" + actual[:16] + "…)")
        return 0
    print("FAIL  signing certificate does not match the milestone record")
    print("      expected " + expected)
    print("      actual   " + actual)
    print("      This APK cannot update an installed ClockCanvas built with the recorded key:")
    print("      users would have to uninstall first, which deletes their saved designs.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
