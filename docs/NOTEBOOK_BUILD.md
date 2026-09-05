# Notebook by Troy — how the installable APK is built

This is the record of how `artifacts/Notebook-by-Troy-v1.0.0.apk` was produced and how to produce
the next one. It is the Notebook counterpart of `APK_V1.4_BUILD.md` (Blockhold), but the pipeline
is different: **Notebook is built by the real Gradle/AGP toolchain in GitHub Actions**, not by the
offline `dx`/`apktool` route, because it depends on AndroidX + Material 3 (Kotlin 2.0 `invokedynamic`
bytecode that the offline `dx` cannot consume).

## Pipeline at a glance

```
push to branch
   │
   ▼
.github/workflows/notebook.yml   "Notebook APK"  (ubuntu-latest, Temurin JDK 17)
   ├─ scripts/lint-kotlin-pitfalls.py <notebook sources>
   ├─ ./gradlew :notebook:testDebugUnitTest
   ├─ ./gradlew :notebook:assembleRelease           AGP 8.7.3 · Kotlin 2.0.21 · compileSdk 35
   ├─ scripts/verify-notebook-apk.py --allow-unsigned <built apk>
   ├─ scripts/verify-notebook-apk.py                (committed artifacts, strict: signature + cert)
   ├─ upload-artifact  Notebook-by-Troy-<sha>
   └─ commit comment "### Notebook CI report" with apk-sha256 + apk-blob (git blob of the APK)
   │
   ▼  (local, with the gitignored release key)
gh api …/git/blobs/<apk-blob>  →  unsigned.apk
apksigner sign  (v2 + v3, key: .signing/notebook-release.p12, alias notebook)
apksigner verify --print-certs
scripts/verify-notebook-apk.py artifacts/Notebook-by-Troy-vX.Y.Z.apk   → 0 failures
commit artifacts/Notebook-by-Troy-vX.Y.Z.apk + artifacts/README.md
```

Why the blob detour: the sandbox that drives this repository can reach `api.github.com` but not the
Actions artifact storage host, so the workflow also stores the APK as a git blob (not in the tree)
and publishes its SHA in a commit comment. Anyone with a normal network can simply download the
Actions artifact instead.

## v1.0.0 record

| Item | Value |
|---|---|
| Source commit | `711689c` (branch `arena/01a070f5-app`, PR #18) |
| CI run | [33991897355](https://github.com/TechTroyAi/App/actions/runs/33991897355) — build ✅ tests ✅ |
| Unsigned output | `notebook-release-unsigned.apk`, 5,775,343 bytes, SHA-256 `a9c94276d027ba3d5a4a09ea15cc05c1a5a0278f954f3e154c93ff317dc31d04` |
| Signed artifact | `artifacts/Notebook-by-Troy-v1.0.0.apk`, 5,784,827 bytes, SHA-256 `eb23c9d5bc4258032c36fdc4ddaaea63ab564178d11be71228a0170928ae8a9a` |
| Signing | apksigner 0.9 (from the `@postar/apktool-node` npm package), v2 + v3, no v1 (minSdk 24 does not need it) |
| Certificate | SHA-256 `B2:C9:ED:0A:42:E6:57:29:90:DE:39:72:A4:57:61:35:13:7D:DB:FE:A6:3A:58:97:AA:C0:BE:BC:F2:5C:3C:4F` — `docs/notebook-release-certificate.txt` |
| Contents | 1,066 entries · `classes.dex` 9,320,864 B + `classes2.dex` 895,368 B · 7,935 classes (762 app) · 5,778 resources · 16 activities · 2 services · 6 receivers · 1 provider |
| Verifier | `python3 scripts/verify-notebook-apk.py` → **0 failures** (zip alignment, v2/v3 + certificate match, DEX checksums, all referenced types resolve, all manifest components and all 73 source classes in DEX, all layouts/xml/anims packaged, adaptive launcher icon, no INTERNET) |

`verify-apk.py --all` (the Blockhold verifier) deliberately skips Notebook artifacts; its sprite and
audio checks are game-specific.

## Building locally instead

With an Android SDK and JDK 17 installed:

```bash
./gradlew :notebook:assembleRelease
# → notebook/build/outputs/apk/release/notebook-release.apk        (signed, if .signing/notebook.properties exists)
# → notebook/build/outputs/apk/release/notebook-release-unsigned.apk (otherwise)
python3 scripts/verify-notebook-apk.py notebook/build/outputs/apk/release/*.apk
```

`notebook/build.gradle.kts` reads `.signing/notebook.properties` (`storePassword`, `keyAlias`,
`keyPassword`) and signs the release build directly when the file is present. Restoring the key
from the encrypted bundle is described in `docs/SIGNING.md`.

## Releasing an update

1. Bump `versionCode` (integer, must increase) and `versionName` in `notebook/build.gradle.kts`.
2. Push; wait for the **Notebook APK** workflow to go green.
3. Sign the CI output with the release key (see `docs/SIGNING.md` → *How a Notebook release is produced*).
4. `python3 scripts/verify-notebook-apk.py artifacts/Notebook-by-Troy-vX.Y.Z.apk` must print
   *All Notebook structural checks passed* — in particular the certificate match, otherwise the
   update will not install over the previous version.
5. Add the row to `artifacts/README.md`, commit the APK (whitelisted by `.gitignore`), open the PR.

## Things that bit us (so they do not bite again)

- **Offline builds are a dead end for this module.** `dx` rejects Kotlin 2.0 / AndroidX class files;
  a JVM-runnable D8 salvaged from the Termux package (`d8-termux` → enjarify) got as far as dexing
  the app classes but crashed on AndroidX's `MethodParameters` attributes. Use CI.
- **Class-file version.** The app compiles with `jvmTarget = 17`; any tool older than 2021 will
  choke on version-61 class files. AGP/R8 are fine with it.
- **Signing is deliberately not in CI.** Repository secrets need an admin account (the automation
  token gets HTTP 403). The workflow supports `NOTEBOOK_KEYSTORE_BASE64` / `NOTEBOOK_STORE_PASSWORD`
  / `NOTEBOOK_KEY_ALIAS` if Troy ever adds them; until then sign locally.
- **The key must outlive the sandbox.** It is committed encrypted at
  `notebook/signing/notebook-signing.tar.gz.enc`; the passphrase lives with Troy, not in Git.
- **Never re-zip a signed or aligned APK** with a generic zip tool — it destroys the 4-byte alignment
  of `resources.arsc` and the v2/v3 signature. Sign the CI output as-is.
