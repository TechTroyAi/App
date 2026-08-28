# Production signing

> **Reality check (verified 2026-08-28).** Neither private key below is present in this
> workspace, so no build produced here can update an existing install. Actual certificate
> fingerprints extracted from the APK signing blocks:
>
> | Key | Certificate SHA-256 | Signs | Private key |
> |---|---|---|---|
> | `CN=Blockhold Defense, OU=Game Release` | `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a` | v1.0 | **absent** |
> | `CN=Blockhold Defense Debug, OU=Sideload` | `095f279baadbed4f6daf0ef3799a3f1b6bbe0867712eaf3311d372589978420b` | v1.1, v1.2 | **absent** |
> | per-machine `~/.android/debug.keystore` | varies | `assembleDebug` | local only |
>
> Note `artifacts/README.md` records the sideload certificate as `dbcf288d…371da55a`, which
> does not match the `095f279b…` actually embedded in the APK — another stale record.
>
> **Consequence:** installing any newly built APK requires uninstalling Blockhold Defense
> once. Android only accepts an update signed by the identical key, so no choice of new key
> avoids that. Generate the permanent key with `scripts/make-signing-key.sh`; after the
> one-time uninstall, every later build updates in place.

## Permanent update identity

Production APKs are signed with a dedicated Blockhold Defense release key. The private keystore and its passwords must remain outside Git. Losing that key prevents direct updates to installations signed with it.

The local build workflow expects private material under `.signing/`, which is ignored by Git:

```text
.signing/blockhold-release.p12
.signing/release.properties
```

The public certificate is safe to audit at `docs/blockhold-release-certificate.txt`. The private handoff bundle is generated as `.signing/Blockhold-Defense-signing-backup.zip` and must never be added to Git. The bundle is currently absent from this workspace, so `artifacts/Blockhold-Defense-v1.2-unsigned.apk` remains an unsigned signing input; creating a replacement key would break update compatibility.

Back up the signing bundle in at least two encrypted, access-controlled locations. Do not email it, add it to a source archive, paste its password into an issue, or commit it to this repository.

## Verify a release

Use Android Build Tools `apksigner`:

```bash
apksigner verify --verbose --print-certs artifacts/Blockhold-Defense-v1.2-release.apk
```

Record the certificate SHA-256 and APK SHA-256 in `artifacts/README.md` for every public release. Future APKs for `ai.techtroy.blockhold` must use the same production key.

## Google Play

For a Play release, enroll in Play App Signing and store the dedicated Blockhold key as the upload key only after confirming the final package ID. Never commit either an upload key or a Play app-signing key. Use CI secret storage when automating release builds.
