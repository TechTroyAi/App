#!/usr/bin/env python3
"""Zipalign + APK Signature Scheme v2/v3 signer.

Works in this sandbox without a JDK or Android SDK: alignment is rewritten in
pure Python and the RSA work is done with the system OpenSSL binary.

    python3 scripts/sign-apk.py \
        artifacts/Blockhold-Defense-v1.2-unsigned.apk \
        artifacts/Blockhold-Defense-v1.2-arena-signed.apk

A new 4096-bit identity is created under .signing/ on first run. That identity
is intentionally distinct from the v1.0 production cert and the v1.1/v1.2
sideload cert (neither private key is in this repo), so Android will refuse to
install this APK *over* those builds. Uninstall ai.techtroy.blockhold once,
then every later APK signed with this key updates in place.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import secrets
import struct
import subprocess
import sys
import tempfile
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIGNING_DIR = os.path.join(REPO, ".signing")
KEY_PEM = os.path.join(SIGNING_DIR, "blockhold-arena-key.pem")
CERT_PEM = os.path.join(SIGNING_DIR, "blockhold-arena-cert.pem")
CERT_DER = os.path.join(SIGNING_DIR, "blockhold-arena-cert.der")
PUB_DER = os.path.join(SIGNING_DIR, "blockhold-arena-pub.der")
P12 = os.path.join(SIGNING_DIR, "blockhold-release.p12")
PROPS = os.path.join(SIGNING_DIR, "release.properties")
CERT_TXT = os.path.join(REPO, "docs", "blockhold-arena-certificate.txt")

# Distinct from both historical identities:
#   CN=Blockhold Defense, OU=Game Release          (v1.0)
#   CN=Blockhold Defense Debug, OU=Sideload        (v1.1 / v1.2)
DN = (
    "/C=PH/ST=Davao Region/L=Davao City/O=TechTroyAi"
    "/OU=Arena Sideload 2026/CN=Blockhold Defense Arena Key"
)
ALIAS = "blockhold"
VALIDITY_DAYS = 10950  # 30 years
ALGO_RSA_PKCS1_SHA256 = 0x0103
V2_ID = 0x7109871A
V3_ID = 0xF05368C0
MIN_SDK = 24
MAX_SDK = 0x7FFFFFFF
CHUNK = 1024 * 1024
MAGIC = b"APK Sig Block 42"


def die(msg: str, code: int = 1) -> None:
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(code)


def run(args: list[str], data: bytes | None = None) -> bytes:
    try:
        proc = subprocess.run(
            args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
        )
    except FileNotFoundError:
        die(f"required tool not found: {args[0]}")
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace").strip()
        die(f"{' '.join(args)} failed ({proc.returncode}): {err}")
    return proc.stdout


def u32(n: int) -> bytes:
    return struct.pack("<I", n)


def u64(n: int) -> bytes:
    return struct.pack("<Q", n)


def prefixed(blob: bytes) -> bytes:
    return u32(len(blob)) + blob


def prefixed_seq(items: list[bytes]) -> bytes:
    body = b"".join(prefixed(item) for item in items)
    return prefixed(body)


def dos_time(dt: tuple[int, int, int, int, int, int]) -> tuple[int, int]:
    year, month, day, hour, minute, second = dt
    if year < 1980:
        year, month, day = 1980, 1, 1
    return (
        (hour << 11) | (minute << 5) | (second // 2),
        ((year - 1980) << 9) | (month << 5) | day,
    )


def encode_name(info: zipfile.ZipInfo) -> bytes:
    flag_utf8 = bool(info.flag_bits & 0x800)
    try:
        return info.filename.encode("utf-8" if flag_utf8 else "cp437")
    except UnicodeEncodeError:
        return info.filename.encode("utf-8")


def raw_payload(apk: bytes, info: zipfile.ZipInfo) -> bytes:
    off = info.header_offset
    nlen, elen = struct.unpack_from("<HH", apk, off + 26)
    start = off + 30 + nlen + elen
    return apk[start:start + info.compress_size]


def zipalign(src: str) -> bytes:
    """Rewrite `src` as a 4-byte-aligned ZIP without a signing block."""
    original = open(src, "rb").read()
    out = bytearray()
    cd = bytearray()
    count = 0
    with zipfile.ZipFile(src) as zf:
        for info in zf.infolist():
            if info.filename.endswith("/"):
                continue
            name = encode_name(info)
            payload = raw_payload(original, info)
            extra = info.extra or b""
            method = info.compress_type
            crc = info.CRC
            csize = info.compress_size
            usize = info.file_size
            flag = info.flag_bits & ~0x8  # no data descriptor
            if method == 0:
                # Keep extra, then pad so data starts on a 4-byte boundary.
                data_off = len(out) + 30 + len(name) + len(extra)
                pad = (4 - (data_off % 4)) % 4
                extra = extra + b"\x00" * pad
            mtime, mdate = dos_time(info.date_time)
            local = struct.pack(
                "<4sHHHHHIIIHH",
                b"PK\x03\x04",
                20,
                flag,
                method,
                mtime,
                mdate,
                crc,
                csize,
                usize,
                len(name),
                len(extra),
            )
            header_off = len(out)
            out += local + name + extra + payload

            # Central directory extra stays unpadded (matches Android zipalign).
            cd_extra = info.extra or b""
            comment = info.comment or b""
            cd += struct.pack(
                "<4sHHHHHHIIIHHHHHII",
                b"PK\x01\x02",
                0x0317,
                20,
                flag,
                method,
                mtime,
                mdate,
                crc,
                csize,
                usize,
                len(name),
                len(cd_extra),
                len(comment),
                0,
                0,
                info.external_attr,
                header_off,
            )
            cd += name + cd_extra + comment
            count += 1

    cd_off = len(out)
    out += cd
    out += struct.pack(
        "<4sHHHHIIH",
        b"PK\x05\x06",
        0,
        0,
        count,
        count,
        len(cd),
        cd_off,
        0,
    )
    return bytes(out)


def ensure_key() -> dict[str, bytes]:
    os.makedirs(SIGNING_DIR, exist_ok=True)
    os.chmod(SIGNING_DIR, 0o700)
    created = not os.path.exists(KEY_PEM)
    if created:
        print("Generating 4096-bit Arena signing key (OpenSSL)...")
        run(["openssl", "genrsa", "-out", KEY_PEM, "4096"])
        os.chmod(KEY_PEM, 0o600)
        run([
            "openssl", "req", "-new", "-x509",
            "-key", KEY_PEM,
            "-sha256",
            "-days", str(VALIDITY_DAYS),
            "-subj", DN,
            "-out", CERT_PEM,
        ])
        der = run(["openssl", "x509", "-in", CERT_PEM, "-outform", "DER"])
        open(CERT_DER, "wb").write(der)
        pub = run([
            "openssl", "x509", "-in", CERT_PEM, "-pubkey", "-noout",
        ])
        pub_der = run(["openssl", "pkey", "-pubin", "-inform", "PEM", "-outform", "DER"], data=pub)
        open(PUB_DER, "wb").write(pub_der)
        password = secrets.token_urlsafe(18)
        run([
            "openssl", "pkcs12", "-export",
            "-inkey", KEY_PEM,
            "-in", CERT_PEM,
            "-name", ALIAS,
            "-out", P12,
            "-passout", f"pass:{password}",
        ])
        os.chmod(P12, 0o600)
        with open(PROPS, "w") as f:
            f.write("# Generated by scripts/sign-apk.py — never commit this file.\n")
            f.write(f"storePassword={password}\n")
            f.write(f"keyAlias={ALIAS}\n")
            f.write(f"keyPassword={password}\n")
        os.chmod(PROPS, 0o600)
        pem_text = open(CERT_PEM).read()
        os.makedirs(os.path.dirname(CERT_TXT), exist_ok=True)
        open(CERT_TXT, "w").write(pem_text)
        print(f"  wrote {KEY_PEM}")
        print(f"  wrote {P12}")
        print(f"  wrote {PROPS}")
        print(f"  public cert copied to {os.path.relpath(CERT_TXT, REPO)}")
    else:
        print(f"Reusing existing Arena key: {KEY_PEM}")

    cert_der = open(CERT_DER, "rb").read() if os.path.exists(CERT_DER) else run(
        ["openssl", "x509", "-in", CERT_PEM, "-outform", "DER"]
    )
    if not os.path.exists(CERT_DER):
        open(CERT_DER, "wb").write(cert_der)
    pub = run(["openssl", "x509", "-in", CERT_PEM, "-pubkey", "-noout"])
    pub_der = open(PUB_DER, "rb").read() if os.path.exists(PUB_DER) else run(
        ["openssl", "pkey", "-pubin", "-inform", "PEM", "-outform", "DER"], data=pub
    )
    if not os.path.exists(PUB_DER):
        open(PUB_DER, "wb").write(pub_der)
    return {"key_pem": open(KEY_PEM, "rb").read(), "cert_der": cert_der, "pub_der": pub_der, "pub_pem": pub}


def fingerprint(cert_der: bytes) -> str:
    return hashlib.sha256(cert_der).hexdigest()


def chunked_digest(parts: list[bytes]) -> bytes:
    digests = []
    for part in parts:
        if not part:
            continue
        off = 0
        n = len(part)
        while off < n:
            chunk = part[off:off + CHUNK]
            digests.append(hashlib.sha256(b"\xa5" + u32(len(chunk)) + chunk).digest())
            off += CHUNK
    count = len(digests)
    return hashlib.sha256(b"\x5a" + u32(count) + b"".join(digests)).digest()


def openssl_sign(key_pem_path: str, data: bytes) -> bytes:
    return run(["openssl", "dgst", "-sha256", "-sign", key_pem_path], data=data)


def openssl_verify(pub_pem: bytes, data: bytes, signature: bytes) -> None:
    with tempfile.TemporaryDirectory() as td:
        pub_path = os.path.join(td, "pub.pem")
        sig_path = os.path.join(td, "sig.bin")
        open(pub_path, "wb").write(pub_pem)
        open(sig_path, "wb").write(signature)
        run(["openssl", "dgst", "-sha256", "-verify", pub_path, "-signature", sig_path], data=data)


def find_eocd(apk: bytes) -> int:
    pos = apk.rfind(b"PK\x05\x06")
    if pos < 0:
        die("EOCD not found")
    return pos


def strip_signing_block(apk: bytes) -> bytes:
    eocd = find_eocd(apk)
    cd_off = struct.unpack_from("<I", apk, eocd + 16)[0]
    if apk[cd_off - 16:cd_off] != MAGIC:
        return apk
    size = struct.unpack_from("<Q", apk, cd_off - 24)[0]
    start = cd_off - 8 - size
    cd = apk[cd_off:eocd]
    eocd_bytes = bytearray(apk[eocd:])
    struct.pack_into("<I", eocd_bytes, 16, start)
    return apk[:start] + cd + bytes(eocd_bytes)


def build_signing_block(v2_value: bytes, v3_value: bytes) -> bytes:
    pairs = b""
    for idv, value in ((V2_ID, v2_value), (V3_ID, v3_value)):
        pair = u32(idv) + value
        pairs += u64(len(pair)) + pair
    # size field excludes itself but includes the trailing size + magic (24 bytes)
    inner = pairs + u64(0) + MAGIC  # placeholder size, patched below
    # Actual layout: size | pairs | size | magic
    # size = len(pairs) + 8 + 16 = len(pairs) + 24
    size = len(pairs) + 24
    return u64(size) + pairs + u64(size) + MAGIC


def build_v2_signer(signed_data: bytes, signature: bytes, pub_der: bytes) -> bytes:
    sig_item = u32(ALGO_RSA_PKCS1_SHA256) + prefixed(signature)
    signer = prefixed(signed_data) + prefixed_seq([sig_item]) + prefixed(pub_der)
    return prefixed_seq([signer])


def build_v3_signer(signed_data: bytes, signature: bytes, pub_der: bytes) -> bytes:
    sig_item = u32(ALGO_RSA_PKCS1_SHA256) + prefixed(signature)
    signer = (
        prefixed(signed_data)
        + u32(MIN_SDK)
        + u32(MAX_SDK)
        + prefixed_seq([sig_item])
        + prefixed(pub_der)
    )
    return prefixed_seq([signer])


def sign_apk(aligned: bytes, key: dict[str, bytes]) -> bytes:
    aligned = strip_signing_block(aligned)
    eocd = find_eocd(aligned)
    cd_off = struct.unpack_from("<I", aligned, eocd + 16)[0]
    cd_size = struct.unpack_from("<I", aligned, eocd + 12)[0]
    entries = aligned[:cd_off]
    central = aligned[cd_off:eocd]
    eocd_bytes = bytearray(aligned[eocd:])
    if cd_size != len(central):
        die(f"central directory size mismatch: header {cd_size} vs {len(central)}")

    # Digest EOCD as if the CD started at the signing-block insertion point
    # (which is the current CD offset of an unsigned/aligned APK).
    eocd_for_digest = bytearray(eocd_bytes)
    struct.pack_into("<I", eocd_for_digest, 16, cd_off)
    digest = chunked_digest([entries, central, bytes(eocd_for_digest)])

    digest_item = u32(ALGO_RSA_PKCS1_SHA256) + prefixed(digest)
    certs = prefixed_seq([key["cert_der"]])
    digests = prefixed_seq([digest_item])
    attrs = prefixed(b"")  # empty additional-attributes sequence

    v2_signed = digests + certs + attrs
    v3_signed = digests + certs + u32(MIN_SDK) + u32(MAX_SDK) + attrs

    v2_sig = openssl_sign(KEY_PEM, v2_signed)
    v3_sig = openssl_sign(KEY_PEM, v3_signed)
    openssl_verify(key["pub_pem"], v2_signed, v2_sig)
    openssl_verify(key["pub_pem"], v3_signed, v3_sig)

    block = build_signing_block(
        build_v2_signer(v2_signed, v2_sig, key["pub_der"]),
        build_v3_signer(v3_signed, v3_sig, key["pub_der"]),
    )
    if len(block) % 8:
        # Spec does not require 8-byte alignment, but keep the block even.
        pass

    new_cd_off = cd_off + len(block)
    struct.pack_into("<I", eocd_bytes, 16, new_cd_off)
    return entries + block + central + bytes(eocd_bytes)


def self_check(apk: bytes, key: dict[str, bytes]) -> None:
    eocd = find_eocd(apk)
    cd_off = struct.unpack_from("<I", apk, eocd + 16)[0]
    if apk[cd_off - 16:cd_off] != MAGIC:
        die("self-check: signing magic missing")
    size = struct.unpack_from("<Q", apk, cd_off - 24)[0]
    start = cd_off - 8 - size
    if struct.unpack_from("<Q", apk, start)[0] != size:
        die("self-check: signing block size mismatch")
    # Recompute content digest against the three regions.
    eocd_for_digest = bytearray(apk[eocd:])
    struct.pack_into("<I", eocd_for_digest, 16, start)
    digest = chunked_digest([apk[:start], apk[cd_off:eocd], bytes(eocd_for_digest)])

    pairs = apk[start + 8:cd_off - 24]
    o = 0
    found = []
    while o + 12 <= len(pairs):
        ln = struct.unpack_from("<Q", pairs, o)[0]
        idv = struct.unpack_from("<I", pairs, o + 8)[0]
        val = pairs[o + 12:o + 8 + ln]
        found.append(idv)
        if idv == V2_ID:
            # signers seq -> signer -> signed_data starts with digests
            seq_len = struct.unpack_from("<I", val, 0)[0]
            signer_len = struct.unpack_from("<I", val, 4)[0]
            signer = val[8:8 + signer_len]
            sd_len = struct.unpack_from("<I", signer, 0)[0]
            sd = signer[4:4 + sd_len]
            dig_seq_len = struct.unpack_from("<I", sd, 0)[0]
            item_len = struct.unpack_from("<I", sd, 4)[0]
            item = sd[8:8 + item_len]
            algo = struct.unpack_from("<I", item, 0)[0]
            dlen = struct.unpack_from("<I", item, 4)[0]
            stored = item[8:8 + dlen]
            if algo != ALGO_RSA_PKCS1_SHA256:
                die(f"self-check: unexpected algo 0x{algo:04X}")
            if stored != digest:
                die("self-check: content digest does not match signing block")
            # verify RSA over signed-data
            sigs_off = 4 + sd_len
            sigs_len = struct.unpack_from("<I", signer, sigs_off)[0]
            sigs = signer[sigs_off + 4:sigs_off + 4 + sigs_len]
            sitem_len = struct.unpack_from("<I", sigs, 0)[0]
            sitem = sigs[4:4 + sitem_len]
            slen = struct.unpack_from("<I", sitem, 4)[0]
            signature = sitem[8:8 + slen]
            openssl_verify(key["pub_pem"], sd, signature)
        o += 8 + ln
    if V2_ID not in found or V3_ID not in found:
        die(f"self-check: expected v2 and v3, found {[hex(x) for x in found]}")
    print("  cryptographic self-check: v2 digest + RSA signature OK")


def subject_line() -> str:
    out = run(["openssl", "x509", "-in", CERT_PEM, "-noout", "-subject", "-nameopt", "RFC2253"])
    return out.decode().strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", nargs="?", default=os.path.join(REPO, "artifacts", "Blockhold-Defense-v1.2-unsigned.apk"))
    ap.add_argument("dst", nargs="?", default=os.path.join(REPO, "artifacts", "Blockhold-Defense-v1.2-arena-signed.apk"))
    args = ap.parse_args()
    src = os.path.abspath(args.src)
    dst = os.path.abspath(args.dst)
    if not os.path.isfile(src):
        die(f"input APK not found: {src}")

    print(f"==> aligning {os.path.relpath(src, REPO)}")
    aligned = zipalign(src)
    # Alignment check on the in-memory ZIP.
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tmp:
        tmp.write(aligned)
        tmp_path = tmp.name
    try:
        with zipfile.ZipFile(tmp_path) as zf, open(tmp_path, "rb") as f:
            bad = 0
            stored = 0
            for info in zf.infolist():
                if info.compress_type != 0:
                    continue
                stored += 1
                f.seek(info.header_offset)
                hdr = f.read(30)
                n, e = struct.unpack("<HH", hdr[26:30])
                off = info.header_offset + 30 + n + e
                if off % 4:
                    bad += 1
                    print(f"  misaligned {info.filename} @ {off}")
            if bad:
                die(f"{bad} of {stored} uncompressed entries still misaligned")
            print(f"  {stored} uncompressed entries 4-byte aligned")
    finally:
        os.unlink(tmp_path)

    key = ensure_key()
    fp = fingerprint(key["cert_der"])
    print(f"==> signing with Arena key SHA-256 {fp}")
    print(f"    {subject_line()}")
    signed = sign_apk(aligned, key)
    self_check(signed, key)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "wb").write(signed)
    print(f"==> wrote {os.path.relpath(dst, REPO)} ({len(signed):,} bytes)")
    print(f"    sha256 {hashlib.sha256(signed).hexdigest()}")
    print()
    print("Install notes")
    print("-------------")
    print("Package is still ai.techtroy.blockhold. Android will refuse this APK as an")
    print("update over v1.0 / v1.1 / v1.2 because those used different certificates.")
    print("Uninstall Blockhold Defense once, then install this file. Later builds")
    print("signed with .signing/blockhold-release.p12 will update in place.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
