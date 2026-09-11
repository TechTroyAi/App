# Aureus code signing

The Windows `Aureus.exe` is signed by CI (`.github/workflows/windows-exe.yml`)
with a **self-signed** code-signing certificate stored at
`.signing/aureus-codesign.p12` (password: `aureus-sign`, also referenced in the workflow). This follows the same
"commit the signing material" convention the repo already uses for the APK
key (see `SIGNING.md`).

## Identity

- Subject: `CN=Aureus, OU=Aureus Release, O=Made by Troy, L=Davao City, ST=Davao Region, C=PH`
- Algorithm: RSA 4096, SHA-256
- Key usage: `digitalSignature` · Extended key usage: `codeSigning`
- Validity: 2026-09-11 → 2036-09-08
- Cert SHA-256: `62:19:1b:dd:ad:b8:5a:90:a7:e7:23:56:51:c0:8c:16:ec:2a:0c:bd:1c:01:a5:b9:28:94:d4:9a:18:4b:cf:d2`

## What it does and does not do

- ✅ Gives the exe a stable, named publisher in Properties → Digital
  Signatures, and lets you verify the binary has not been tampered with.
- ❌ Does **not** clear the SmartScreen "unknown publisher" warning. Windows
  only trusts certificates that chain to a public CA, and SmartScreen also
  weighs reputation. If you ever want that, buy a code-signing cert from a
  public CA and swap the `.p12` — the CI step needs no other change.

## Rotating the key

1. `openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \`
   `-keyout .signing/aureus-codesign.key -out .signing/aureus-codesign.crt \`
   `-subj "/CN=Aureus/OU=Aureus Release/O=Made by Troy/L=Davao City/ST=Davao Region/C=PH" \`
   `-addext "keyUsage=digitalSignature" -addext "extendedKeyUsage=codeSigning" -addext "basicConstraints=CA:FALSE"`
2. `openssl pkcs12 -export -out .signing/aureus-codesign.p12 \`
   `-inkey .signing/aureus-codesign.key -in .signing/aureus-codesign.crt -passout pass:aureus-sign`
3. Commit the new `.p12` and update the fingerprints recorded here and in
   `artifacts/README.md`.

The raw `.key` is gitignored; only the `.p12` (which contains it) and the
public `.crt` are meant to live in the repo.
