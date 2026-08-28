# Production signing

## Current Arena key (2026-08-28)

A new identity was generated in this workspace because **neither historical private key is present**. It is deliberately a different distinguished name so it cannot be mistaken for v1.0 or the old sideload cert.

| Key | Certificate SHA-256 | Signs | Private key |
| --- | --- | --- | --- |
| `CN=Blockhold Defense Arena Key, OU=Arena Sideload 2026` | `aa7ea2f6c74ca8adaaaaf05e64837ab9a7aa798e1c2dd9fe29a117c75bb0e178` | v1.2 Arena-signed | `.signing/` (gitignored) |
| `CN=Blockhold Defense, OU=Game Release` | `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a` | v1.0 | **absent** |
| `CN=Blockhold Defense Debug, OU=Sideload` | `095f279baadbed4f6daf0ef3799a3f1b6bbe0867712eaf3311d372589978420b` | v1.1, v1.2-installable | **absent** |

Public certificate: `docs/blockhold-arena-certificate.txt`.

**Consequence:** the Arena-signed APK cannot update an existing `ai.techtroy.blockhold` install. Uninstall once, then install `artifacts/Blockhold-Defense-v1.2-arena-signed.apk`. After that, every APK signed with `.signing/blockhold-release.p12` updates in place.

## How the Arena APK was signed

This sandbox has no JDK and cannot complete a TLS handshake to `dl.google.com`, Maven Central, or Debian mirrors, so `keytool` / `apksigner` / Gradle were not available. `scripts/sign-apk.py` zip-aligns with the Python standard library and signs APK Signature Scheme **v2 + v3** using OpenSSL (`RSASSA-PKCS1-v1_5` + SHA-256, 4096-bit RSA).

```bash
python3 scripts/sign-apk.py \
    artifacts/Blockhold-Defense-v1.2-unsigned.apk \
    artifacts/Blockhold-Defense-v1.2-arena-signed.apk
python3 scripts/verify-apk.py artifacts/Blockhold-Defense-v1.2-arena-signed.apk
```

On first run the script writes (all gitignored except the public cert):

```text
.signing/blockhold-arena-key.pem
.signing/blockhold-arena-cert.pem
.signing/blockhold-release.p12
.signing/release.properties
docs/blockhold-arena-certificate.txt
```

`app/build.gradle.kts` already points at `.signing/blockhold-release.p12` + `release.properties`. A later `./gradlew assembleRelease` on a machine with JDK 17 will reuse this key.

## Permanent update identity

Losing `.signing/blockhold-release.p12` forces another uninstall on every device. Back the PKCS12 and `release.properties` up in at least two encrypted locations. Do not email it, commit it, or paste the password into an issue.

If you still have the original v1.0 backup zip and want production-key updates instead, restore it over `.signing/` *before* anyone installs the Arena-signed APK.

## Verify a release

```bash
python3 scripts/verify-apk.py artifacts/Blockhold-Defense-v1.2-arena-signed.apk
# optional, if Android build-tools are on PATH:
apksigner verify --verbose --print-certs artifacts/Blockhold-Defense-v1.2-arena-signed.apk
```

Record the certificate SHA-256 and APK SHA-256 in `artifacts/README.md` for every public release.

## Google Play

For a Play release, enroll in Play App Signing and store this Arena key as the upload key only after confirming the final package ID. Never commit either an upload key or a Play app-signing key.
