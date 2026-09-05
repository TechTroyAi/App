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

### The current key (v1.4.1)

| Field | Value |
|---|---|
| Alias | `blockhold` |
| Store type | **JKS** (not PKCS12) |
| Certificate SHA-256 | `7d:59:6f:ee:b2:7d:6e:0b:46:02:bf:06:36:ea:55:1a:d7:34:26:8e:e1:16:0c:8e:50:95:0e:e9:9a:77:8f:6d` |
| Signs | v1.4.1, APK SHA-256 `789c964ad702a28531386e2e75f610a87c260275a47c53bfb4caf42fba6c7c96` |
| Password | set when the key was generated; kept in gitignored `.signing/release.properties` inside the build sandbox. **Retrieve it from that file — do not paste it into issues, PRs or chat.** |

A base64 copy lives at `.signing/blockhold-key.b64` (gitignored). That path is inside the
build sandbox and **will vanish when the sandbox does** — move it somewhere durable first.

Restore it on any machine:

```bash
mkdir -p ~/.config/blockhold && chmod 700 ~/.config/blockhold
base64 -d .signing/blockhold-key.b64 > ~/.config/blockhold/release.jks
chmod 600 ~/.config/blockhold/release.jks
```

Always confirm you restored the *right* key before using it — signing with the wrong one is
what forces players to uninstall:

```bash
keytool -list -v -keystore ~/.config/blockhold/release.jks -alias blockhold | grep SHA256:
# must print 7D:59:6F:EE:B2:7D:6E:0B:46:02:BF:06:36:EA:55:1A:D7:34:26:8E:E1:16:0C:8E:50:95:0E:E9:9A:77:8F:6D
```

Point a build at it:

- **Offline pipeline** (`apksigner`) — JKS works directly:

  ```bash
  apksigner sign --ks ~/.config/blockhold/release.jks --ks-type JKS \
    --ks-key-alias blockhold --min-sdk-version 24 \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --in unsigned.apk --out signed.apk
  ```

- **Gradle** — `app/build.gradle.kts` hardcodes `storeType = "PKCS12"` and
  `.signing/blockhold-release.p12`, so convert first:

  ```bash
  keytool -importkeystore -srckeystore ~/.config/blockhold/release.jks -srcstoretype JKS \
          -destkeystore .signing/blockhold-release.p12 -deststoretype PKCS12
  export BLOCKHOLD_STORE_PASS='<the password from .signing/release.properties>'
  ./gradlew assembleRelease
  ```

Pass the password via `BLOCKHOLD_STORE_PASS` or `--ks-pass`; never hardcode it.

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

---

## Notebook by Troy (`ai.techtroy.notebook`)

The Notebook app has its **own** release key, separate from the Blockhold key. Never sign one app
with the other's key.

| | |
|---|---|
| Keystore | `.signing/notebook-release.p12` (PKCS12, gitignored) |
| Passwords | `.signing/notebook.properties` (`storePassword`, `keyAlias=notebook`, `keyPassword`) |
| Key | RSA 4096, SHA256withRSA, valid 2026-09-05 → 2056-08-28 |
| Subject | `CN=Notebook by Troy, OU=Apps, O=TechTroy AI, L=Cagayan de Oro, ST=Northern Mindanao, C=PH` |
| Certificate SHA-256 | `B2:C9:ED:0A:42:E6:57:29:90:DE:39:72:A4:57:61:35:13:7D:DB:FE:A6:3A:58:97:AA:C0:BE:BC:F2:5C:3C:4F` |
| Public certificate | [`docs/notebook-release-certificate.txt`](notebook-release-certificate.txt) |
| First release signed | `artifacts/Notebook-by-Troy-v1.0.0.apk` (v1.0.0, versionCode 1) |

### The key survives the sandbox this time

Every Blockhold key was lost because it only ever lived in an ephemeral sandbox. The Notebook key
is stored **in the repository, encrypted**, so any future session (or CI) can restore it:

```text
notebook/signing/notebook-signing.tar.gz.enc   AES-256-CBC, PBKDF2 (600 000 iterations)
```

The archive contains `notebook-release.p12` and `notebook.properties`. The **passphrase is not in
the repository** — Troy holds it (it was handed over in the chat where v1.0.0 was built, and should
live in a password manager). Without the passphrase the file is just noise; with it:

```bash
# restore the private key into the gitignored .signing/ directory
mkdir -p .signing
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
  -in notebook/signing/notebook-signing.tar.gz.enc -pass pass:"$PASSPHRASE" | tar -xzf - -C .signing
chmod 600 .signing/notebook-release.p12 .signing/notebook.properties

# sanity check: must print B2:C9:ED:0A:...:5C:3C:4F
keytool -list -v -keystore .signing/notebook-release.p12 -alias notebook \
  -storepass "$(sed -n 's/^storePassword=//p' .signing/notebook.properties)" | grep SHA256:
```

To re-encrypt after rotating the passwords (or if the bundle must be regenerated):

```bash
tar -C .signing -czf /tmp/notebook-signing.tar.gz notebook-release.p12 notebook.properties
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -in /tmp/notebook-signing.tar.gz \
  -out notebook/signing/notebook-signing.tar.gz.enc -pass pass:"$PASSPHRASE"
rm /tmp/notebook-signing.tar.gz
```

Optionally also store it as GitHub secrets so CI can sign directly (the workflow already looks for
them): `NOTEBOOK_KEYSTORE_BASE64` = `base64 -w0 .signing/notebook-release.p12`,
`NOTEBOOK_STORE_PASSWORD`, `NOTEBOOK_KEY_ALIAS` = `notebook`. Setting repository secrets requires
an admin account; the automation token cannot.

### How a Notebook release is produced

1. Push to a branch — the **Notebook APK** workflow (`.github/workflows/notebook.yml`) lints,
   runs unit tests, builds `:notebook:assembleRelease` with AGP 8.7.3 / Kotlin 2.0.21, verifies
   the result structurally, uploads it as an Actions artifact and posts a commit comment
   (`### Notebook CI report`) containing the APK's SHA-256 and a git **blob SHA**.
2. Fetch the unsigned APK from the blob (works even where Actions artifacts are unreachable):
   `gh api repos/TechTroyAi/App/git/blobs/<apk-blob> -H "Accept: application/vnd.github.raw" > unsigned.apk`
3. Sign with the release key (the CI build is already zipaligned by AGP; do not re-zip it):

   ```bash
   PW=$(sed -n 's/^storePassword=//p' .signing/notebook.properties)
   java -jar apksigner.jar sign --ks .signing/notebook-release.p12 --ks-type PKCS12 \
     --ks-key-alias notebook --ks-pass pass:"$PW" --key-pass pass:"$PW" \
     --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
     --out artifacts/Notebook-by-Troy-vX.Y.Z.apk unsigned.apk
   ```

4. Verify and record:

   ```bash
   java -jar apksigner.jar verify --print-certs artifacts/Notebook-by-Troy-vX.Y.Z.apk
   python3 scripts/verify-notebook-apk.py artifacts/Notebook-by-Troy-vX.Y.Z.apk   # checks the certificate too
   ```

   then add the hash to `artifacts/README.md` and commit (the `.gitignore` whitelists
   `artifacts/Notebook-by-Troy-*.apk`). Bump `versionCode`/`versionName` in
   `notebook/build.gradle.kts` for every release — Android will not install an update with the
   same or lower `versionCode`.

If `.signing/notebook.properties` is present locally, `./gradlew :notebook:assembleRelease`
signs directly and steps 2–3 collapse into one.

If the key is ever truly lost (bundle *and* passphrase), users must uninstall v1.0.0 — losing their
notes unless they exported a backup from Settings first — before a build signed with a new key
can be installed. Do not let that happen.
