# Production signing

Blockhold Defense uses the unique Android application ID `ai.techtroy.blockhold`.

## Permanent update identity

Production APKs are signed with a dedicated Blockhold Defense release key. The private keystore and its passwords must remain outside Git. Losing that key prevents direct updates to installations signed with it.

The local build workflow expects private material under `.signing/`, which is ignored by Git:

```text
.signing/blockhold-release.p12
.signing/release.properties
```

The public certificate is safe to audit at `docs/blockhold-release-certificate.txt`. The private handoff bundle is generated as `.signing/Blockhold-Defense-signing-backup.zip` and must never be added to Git.

Back up the signing bundle in at least two encrypted, access-controlled locations. Do not email it, add it to a source archive, paste its password into an issue, or commit it to this repository.

## Verify a release

Use Android Build Tools `apksigner`:

```bash
apksigner verify --verbose --print-certs artifacts/Blockhold-Defense-v1.0-release.apk
```

Record the certificate SHA-256 and APK SHA-256 in `artifacts/README.md` for every public release. Future APKs for `ai.techtroy.blockhold` must use the same production key.

## Google Play

For a Play release, enroll in Play App Signing and store the dedicated Blockhold key as the upload key only after confirming the final package ID. Never commit either an upload key or a Play app-signing key. Use CI secret storage when automating release builds.
