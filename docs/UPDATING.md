# Shipping Jadex 1.4.0 as an update

## The one rule

Android installs an APK over an existing app only when **both** are true:

1. it is signed with the **identical** certificate, and
2. its `versionCode` is **higher** than the installed one.

Fail either and the install is rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
and the only way through is uninstall-then-install — which wipes app data.

## Where this build stands

| Requirement | Status |
| --- | --- |
| `versionCode` 25 > installed 24 | **done** (`1.4.0`, was `1.3.0` / 24) |
| `applicationId` `ai.techtroy.jadex` unchanged | **done** |
| Signed with the Jadex certificate `8b912d1e…` | **needs the private key** |

The installed `Jadex-v1.3.0-installable.apk` is signed with:

```
C=PH, ST=Northern Mindanao, L=Cagayan de Oro,
O=TechTroyAi, OU=Python Studio, CN=Jadex
SHA-256  8b912d1ecc75af8abd9becb2489a8c6a95b7e9dbf3c3c9ac01658918e88f6129
expires  2056-08-30
```

That private key is **not in this repository** and is not in Git history. It was
generated in an earlier build sandbox, and sandboxes do not persist. Without it,
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

## If the key is gone

Then this release cannot be an in-place update, and no build configuration
changes that. Choose one:

**A. One final uninstall, then never again.** Mint a permanent key, back it up
outside any sandbox, and load it into repository secrets. You uninstall Jadex
once; every release after this updates in place forever.

```bash
./scripts/make-signing-key.sh
```

Your Python files live in the WebView's `localStorage` plus `filesDir/jadex`, so
copy anything you care about out of the app before uninstalling.

**B. Install side by side.** Change `applicationId` to e.g.
`ai.techtroy.jadex.next` and the new build installs as a second app, leaving
1.3.0 untouched. Useful for comparing the two, but it is a different app to
Android and does not share the old app's files.

## Verifying before you install

```bash
python3 scripts/check-update-compatible.py path/to/new.apk
```

```
=== app-release.apk ===
  schemes: v2, v3
  cert:    8b912d1e…
  PASS  signing certificate matches the installed app
  PASS  versionCode 25 > installed 24

UPDATE-COMPATIBLE
```

Anything other than `UPDATE-COMPATIBLE` means the device will refuse it, and the
script's exit code is non-zero so CI stops too.

## Getting the APK

GitHub Actions builds one on every push to this branch. Open the run, and
download the `Jadex-v1.4.0-release` artifact (or `Jadex-v1.4.0-debug`, which is
signed with a throwaway key and never updates an install):

```bash
gh run list --branch arena/01a07df2-app
gh run download <run-id> -n Jadex-v1.4.0-release
```
