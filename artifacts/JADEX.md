# Jadex v1.3.7

Built from the same Kotlin `MainActivity` and IDE assets as v1.3.6, version-stamped
`1.3.7` / `29` and signed with a freshly minted release key, on 2026-09-08.

| Field | Value |
|---|---|
| APK | `Jadex-v1.3.7-installable.apk` |
| Package | `ai.techtroy.jadex` |
| Version | `1.3.7` / code `29` |
| Android | 8.0 (API 26) or newer; target API 35 |
| Size | 20,013,995 bytes (19.1 MiB) |
| Signing | RSA 4096, APK signature v2 + v3 |
| APK SHA-256 | `5233f26daf0bb1335a61467d2e810445a8887f37df2bdf8870de41976f313f5f` |
| Certificate SHA-256 | `4d7ee970ce07b661a563164f505355901c61a4f2a577bab7db3e3237e519afcc` |

## What is and is not in this build

**No app behaviour changed since v1.3.6.** v1.3.7 is a version bump plus a new signing
identity: `app/build.gradle.kts` went `1.3.6`/`28` → `1.3.7`/`29` and everything else in
the editor, the Kotlin host and the Pyodide payload is byte-identical to v1.3.6
(all seven `assets/ide/*` files were read back out of the finished APK and SHA-256
compared against the source tree — all `MATCH-SOURCE`).

**In particular, this APK does not fix a `TypeError` in your own `.py` files.** The
`"text" + 17` concatenation that fails lives in a user file (`Day01.py`), which is stored
*on the device* in the WebView's `localStorage` under `jadex-files-v1` (exported files go
to `filesDir/jadex`, see `MainActivity.kt`). It is not in this repository, so no rebuild of
the app can change it. The fix is in your code — convert the number to text:

```python
age = 17
name = "Troy"
print("I am " + name + " and I am " + str(age) + " years old")
```

Equivalent: `print(f"I am {name} and I am {age} years old")`, or
`print("I am", name, "and I am", age, "years old")` (comma form uses `print`'s own
separator, no conversion needed).

### Why it runs under Subset but fails under CPython

`age = 17` is a number, and `+` between text and a number has no single right answer — so
CPython refuses to guess and raises `TypeError: can only concatenate str (not "int") to str`.

Jadex's fallback engine does not: `python.js` evaluates `BinOp` on top of JavaScript, where
`"a" + 17` coerces the number and yields `"a17"`:

```js
case "+": return (Array.isArray(l) && Array.isArray(r)) ? l.concat(r) : l + r;
```

So the same file runs under Subset and dies under real CPython. That difference is a
portability trap, not a licence to keep writing `+`. Two options if you want the app to
stop hiding it: make the Subset engine raise `TypeError` for `str + int` (one case in
`python.js`), or leave it permissive and always test lesson files with the CPython chip
active. Not done unilaterally here — a strict Subset would break any existing user file
that relies on the coercion.

## ⚠️ Read before installing: this is a *third* fresh signing identity

Android accepts an update only if it is signed by the identical certificate. `.signing/` is
git-ignored and build sandboxes do not persist, so the key minted for v1.3.6 was not present
here either, and `scripts/build-offline-apk.py` refused to build silently — it required an
explicit `JADEX_ALLOW_NEW_KEY=1`, which was given.

| Build | Certificate SHA-256 |
|---|---|
| v1.3.5 | `aa4c15678d59d9e7d0bc269626d6a6e0c6576b09711d9ef6cc7519af65f73ba6` |
| v1.3.6 | `78dd08ba8e1ce5e520606492f1560e6ced744191596ac6b8bc72112749e4f008` |
| **v1.3.7** | `4d7ee970ce07b661a563164f505355901c61a4f2a577bab7db3e3237e519afcc` |

```
$ python3 scripts/check-update-compatible.py \
    --reference artifacts/Jadex-v1.3.6-installable.apk \
    artifacts/Jadex-v1.3.7-installable.apk
  FAIL  certificate mismatch — expected 78dd08ba…   (as above)
  PASS  versionCode 29 > installed 28
NOT UPDATE-COMPATIBLE
```

**To install:** export your projects out of the app first — uninstalling erases the app's
data, and that is where `Day01.py` and every other lesson live. Then uninstall Jadex and
install v1.3.7. After this install, v1.3.7's key becomes the identity every later build must
reuse.

If you would rather keep v1.3.6 and its data untouched, build a side-by-side variant instead:
change `applicationId` to e.g. `ai.techtroy.jadex.next` and both installs coexist (different
apps to Android, no shared files).

## Do not lose this key a third time

The fix for the recurring "new certificate every release" problem is to get the key out of
the sandbox and into CI, so every future build reuses it. The v1.3.6 keystore was lost for
exactly this reason.

```bash
# from the repo root, with .signing/jadex-release.p12 present:
BLOCKHOLD_KEYSTORE=.signing/jadex-release.p12 \
BLOCKHOLD_PROPS=.signing/jadex-release.properties \
BLOCKHOLD_KEY_ALIAS=jadex \
  ./scripts/make-signing-key.sh --export
```

That prints the base64 keystore plus the alias and password to paste into repository secrets
(`BLOCKHOLD_KEYSTORE_BASE64`, `BLOCKHOLD_STORE_PASSWORD`, `BLOCKHOLD_KEY_ALIAS`), which
`.github/workflows/android.yml` restores before `assembleRelease`. `scripts/make-signing-key.sh`
needed three fixes to make this path work, all in this release:

- `--export` was unreachable for any existing key: the "keystore already exists, refusing to
  overwrite" guard ran *before* the `--export` branch, so the script aborted on precisely the
  keystore you wanted to export. The guard now skips itself in export mode.
- Alias and subject DN were hardcoded to Blockhold (`blockhold`), so the export check could
  never open the Jadex key (alias `jadex`). Both are now overridable via `BLOCKHOLD_KEY_ALIAS`
  and `BLOCKHOLD_KEY_DNAME`, and `BLOCKHOLD_PROPS` points at the properties file.
- `--export` demanded a password nobody had recorded for a build-generated key; it now reads
  `storePassword=` from the sibling properties file before falling back to prompting.

Never commit the key: `.gitignore` already excludes `.signing/`, `*.p12`, `*.jks`, `*.keystore`,
`*.pem` and `*.key`.

## Verification

`scripts/verify-apk.py` reports **0 failures** (1 pre-existing warning about unreferenced
legacy game drawables):

- ZIP layout: `resources.arsc` and `classes.dex` STORED, 285 uncompressed entries 4-byte aligned, 304 entries total
- Signing: v2 + v3 present, independently re-verified with `apksigner verify --print-certs`
- DEX: format 038, 2,405,600 bytes, adler32 + SHA-1 valid, all 1330 referenced types resolve (1016 classes, Kotlin stdlib bundled)
- Manifest: `package=ai.techtroy.jadex versionName=1.3.7 versionCode=29`, `minSdk=26`, `targetSdk=35`, exported MAIN/LAUNCHER activity present in DEX
- Assets: 5 Pyodide entries (13,780,118 bytes uncompressed) plus all seven IDE files, each matching the source tree hash

Size is byte-for-byte identical to v1.3.6 (20,013,995) because only two manifest strings
changed and the ~19 MiB uncompressed Pyodide payload dominates; the SHA-256 differs.

## Install

Download the APK to your Android device, open it, allow installation from that browser/file
manager when Android prompts. Uninstall v1.3.6 first (see the signing note above).

## Build this APK

The standard Android SDK download is unavailable in this environment, so the artifact uses the
repository's offline pipeline: kotlinc, dx, apktool, alignment, apksigner. It recompiles
`MainActivity.kt`, bundles Kotlin's runtime, copies the current IDE assets/resources, and uses
the retained Blockhold v1.4.3 APK as a resource-table template. Old unused game resources
therefore remain in the APK.

Prerequisites: Python 3 with venv support, npm, and network access to PyPI, npm and GitHub.
From the repository root:

```bash
python3 -m venv ~/.local/build-venv
~/.local/build-venv/bin/pip install jdk4py==21.0.8.2
npm install --prefix ~/.local kotlin-compiler@1.9.25
export PATH="$HOME/.local/node_modules/kotlin-compiler/bin:$PATH"
python3 scripts/build-offline-apk.py          # reuses .signing/jadex-release.p12 if present
```

A build with no keystore aborts on purpose; `JADEX_ALLOW_NEW_KEY=1` opts into a new identity.
`kotlinc` needs `java` on `PATH` — put the jdk4py bin directory on it too, as the CI workflow does.
