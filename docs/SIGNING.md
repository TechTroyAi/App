# Production signing

> **Reality check (verified 2026-08-28).** Neither private key below is present in this
> workspace, so no build produced here can update an existing install. Actual certificate
> fingerprints extracted from the APK signing blocks:
>
> | Key | Certificate SHA-256 | Signs | Private key |
> |---|---|---|---|
> | `CN=Blockhold Defense, OU=Game Release` | `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a` | v1.0 | **absent** |
> | `CN=Blockhold Defense Debug, OU=Sideload` | `095f279baadbed4f6daf0ef3799a3f1b6bbe0867712eaf3311d372589978420b` | v1.1, v1.2 | **absent** |
> | `CN=Blockhold Defense, OU=Game Release` | `7e4dcdc2593d754a62fb8e33bde0721b3a485663cbbbe3cd8f316fd81480def9` | v1.4 | **absent** |
> | `CN=Blockhold Defense, OU=Game Release` | `4657880e291d2b83cd976ad9559aff994dd3a3a6a7f121a13d56872df7ab4bd9` | v1.4.1 (discarded, will not open) | **absent** |
> | `CN=Blockhold Defense, OU=Game Release` | `7d596feeb27d6e0b4602bf0636ea551ad734268ee1160c8e50950ee99a778f6d` | **v1.4.1 (current)** | **absent** |
> | per-machine `~/.android/debug.keystore` | varies | `assembleDebug` | local only |
>
> Four distinct release keys have now been used. Every new key costs players a manual
> uninstall and their saved progress — see
> [Keeping the key alive](#keeping-the-key-alive).
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

## Keeping the key alive

Every key so far was lost the same way: it was generated inside an ephemeral build sandbox
(`build-manual/`), which is gitignored *by design* and therefore not restored in the next
session. The next build then had to mint a new key, forcing players to uninstall again.

A sandbox is not a key store. Put the key somewhere that outlives it:

1. **Generate it on a durable machine** (your workstation, not a sandbox):

   ```bash
   ./scripts/make-signing-key.sh
   ```

2. **Back it up in two encrypted places** (password manager attachment, encrypted volume).
   Not email, not a chat message, not this repository.

3. **Store a copy as GitHub repository secrets** so future sandboxes and CI can restore it
   without ever committing it. Run this on the machine holding the keystore:

   ```bash
   gh secret set BLOCKHOLD_KEYSTORE_BASE64  < <(base64 -w0 .signing/blockhold-release.p12)
   gh secret set BLOCKHOLD_STORE_PASSWORD   # paste the keystore password
   gh secret set BLOCKHOLD_KEY_ALIAS        # e.g. blockhold
   ```

   A later sandbox can then recover it without minting a new key:

   ```bash
   gh secret list                                   # confirm the three secrets exist
   gh api repos/TechTroyAi/App/actions/secrets/BLOCKHOLD_KEYSTORE_BASE64 \
     --jq .value? 2>/dev/null || echo "(values are write-only; retrieve from your backup)"
   ```

   Secrets are write-only via the API — treat step 2 as the real backup and the secrets as
   the convenience copy for automation.

4. **Point builds at a durable path** instead of the sandbox. `scripts/make-signing-key.sh`
   honours `BLOCKHOLD_KEYSTORE`, which can live outside the repo:

   ```bash
   export BLOCKHOLD_KEYSTORE="$HOME/.config/blockhold/release.p12"
   ./scripts/make-signing-key.sh            # creates it there, if absent
   ./scripts/make-signing-key.sh --export   # prints base64 + secret values for backup
   ```

Before shipping, confirm the build used the *expected* key rather than a fresh one:

```bash
apksigner verify --print-certs artifacts/Blockhold-Defense-v1.4.1-installable.apk
# compare the certificate SHA-256 against the table above
```

If the fingerprint does not match the current release, you are about to force an uninstall.

## Verify a release

Use Android Build Tools `apksigner`:

```bash
apksigner verify --verbose --print-certs artifacts/Blockhold-Defense-v1.2-release.apk
```

Record the certificate SHA-256 and APK SHA-256 in `artifacts/README.md` for every public release. Future APKs for `ai.techtroy.blockhold` must use the same production key.

## Google Play

For a Play release, enroll in Play App Signing and store the dedicated Blockhold key as the upload key only after confirming the final package ID. Never commit either an upload key or a Play app-signing key. Use CI secret storage when automating release builds.
