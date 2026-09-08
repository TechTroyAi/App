# Shipping a Jadex release as an update

## The one rule

Android installs an APK over an existing app only when **both** are true:

1. it is signed with the **identical** certificate, and
2. its `versionCode` is **higher** than the installed one.

Fail either and the install is rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
and the only way through is uninstall-then-install — which wipes app data.

## Where v1.3.7 stands

| Requirement | Status |
| --- | --- |
| `versionCode` 29 > installed 28 | **done** (`1.3.7`, was `1.3.6` / 28) |
| `applicationId` `ai.techtroy.jadex` unchanged | **done** |
| Signed with the certificate of the installed build | **no — fresh key** |

v1.3.7 is signed with `4d7ee970…`, minted because `.signing/` was not in the checkout.
The installed v1.3.6 carries `78dd08ba…`, v1.3.5 carried `aa4c1567…`, and the older
`Jadex-v1.3.0-installable.apk` carries:

```
C=PH, ST=Northern Mindanao, L=Cagayan de Oro,
O=TechTroyAi, OU=Python Studio, CN=Jadex
SHA-256  8b912d1ecc75af8abd9becb2489a8c6a95b7e9dbf3c3c9ac01658918e88f6129
expires  2056-08-30
```

None of those earlier private keys are in this repository or in Git history. They were
generated in earlier build sandboxes, and sandboxes do not persist. Without the key,
no machine — this one or GitHub Actions — can produce an APK that updates your
install in place. This is a property of Android's signature check, not a
limitation of the build scripts.

## If you still have the key

Add it once to GitHub repository secrets and every future build updates cleanly:

```bash
base64 -w0 jadex-release.p12    # copy the output
```

Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
| --- | --- |
| `BLOCKHOLD_KEYSTORE_BASE64` | the base64 blob above |
| `BLOCKHOLD_STORE_PASSWORD` | keystore password |
| `BLOCKHOLD_KEY_ALIAS` | key alias (default `blockhold`) |

Push, and `.github/workflows/android.yml` restores the key, builds
`assembleRelease`, and runs `scripts/check-update-compatible.py`, which fails the
build if the certificate or versionCode would not update your install.

Locally the same thing works via `.signing/`:

```text
.signing/blockhold-release.p12
.signing/release.properties     # storePassword=…  keyAlias=…  keyPassword=…
```

```bash
./gradlew assembleRelease
python3 scripts/check-update-compatible.py app/build/outputs/apk/release/app-release.apk
```

Note the two different conventions in this repo: `app/build.gradle.kts` and the CI workflow
read `.signing/blockhold-release.p12` + `.signing/release.properties`, while the offline
Jadex builder (`scripts/build-offline-apk.py`, which produced every `Jadex-v*-installable.apk`
in `artifacts/`) reads `.signing/jadex-release.p12` + `.signing/jadex-release.properties`,
alias `jadex`. Keep **the same key pair** in both places or the two pipelines will sign with
different certificates and the device will refuse one of them.

## If the key is gone

Then this release cannot be an in-place update, and no build configuration
changes that. Choose one:

**A. One final uninstall, then never again.** Mint a permanent key, back it up
outside any sandbox, and load it into repository secrets. You uninstall Jadex
once; every release after this updates in place forever.

```bash
./scripts/make-signing-key.sh
```

v1.3.7 is already this option: its key is `.signing/jadex-release.p12` (alias
`jadex`, cert `4d7ee970…`), and keeping *that* file is what stops the cycle. Rescue
it from a build environment — and print the exact secret values — with:

```bash
BLOCKHOLD_KEYSTORE=.signing/jadex-release.p12 \
BLOCKHOLD_PROPS=.signing/jadex-release.properties \
BLOCKHOLD_KEY_ALIAS=jadex \
  ./scripts/make-signing-key.sh --export
```

Your Python files live in the WebView's `localStorage` plus `filesDir/jadex`, so
copy anything you care about out of the app before uninstalling.

**B. Install side by side.** Change `applicationId` to e.g.
`ai.techtroy.jadex.next` and the new build installs as a second app, leaving
v1.3.6 untouched. Useful for comparing the two, but it is a different app to
Android and does not share the old app's files.

## Verifying before you install

```bash
python3 scripts/check-update-compatible.py \
  --reference artifacts/Jadex-v1.3.6-installable.apk \
  artifacts/Jadex-v1.3.7-installable.apk
```

```
=== artifacts/Jadex-v1.3.7-installable.apk ===
  schemes: v2, v3
  cert:    4d7ee970ce07b661a563164f505355901c61a4f2a577bab7db3e3237e519afcc
  FAIL  certificate mismatch — expected 78dd08ba8e1ce5e520606492f1560e6ced744191596ac6b8bc72112749e4f008
        Android will refuse this as an update
        (INSTALL_FAILED_UPDATE_INCOMPATIBLE)
  PASS  versionCode 29 > installed 28

NOT UPDATE-COMPATIBLE
```

That is v1.3.7's real output: the version bump passes, the certificate does not, because
the key had to be minted again. Once the stored key matches the installed build the middle
line reads `PASS  signing certificate matches the installed app` and the run ends in
`UPDATE-COMPATIBLE`.

Anything other than `UPDATE-COMPATIBLE` means the device will refuse it, and the
script's exit code is non-zero so CI stops too.

## Getting the APK

GitHub Actions builds one on every push to this branch. Artifact names are read from
`versionName` in `app/build.gradle.kts`, so for this release open the run and download the
`Jadex-v1.3.7-release` artifact (or `Jadex-v1.3.7-debug`, which is signed with a throwaway
key and never updates an install):

```bash
gh run list --branch arena/01a07fdd-app
gh run download <run-id> -n Jadex-v1.3.7-release
```

Until the signing secrets below exist, that release artifact is **unsigned** (CI warns and
builds with `-PskipSigning=true`) and Android will not install it at all. The installable,
signed build for v1.3.7 is the one committed in `artifacts/`.
