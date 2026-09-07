# Jadex v1.3.5

Built from the current Kotlin MainActivity and bundled IDE assets on 2026-09-08.

| Field | Value |
|---|---|
| APK | `Jadex-v1.3.5-installable.apk` |
| Package | `ai.techtroy.jadex` |
| Version | `1.3.5` / code `27` |
| Android | 8.0 (API 26) or newer; target API 35 |
| Size | 20,013,995 bytes (19.1 MiB) |
| Signing | RSA 4096, APK signature v2 + v3 |
| APK SHA-256 | `574143a08d9dc4ea874ff847462e89ceca99497ac9395d70199e8b9ba6b362f1` |
| Certificate SHA-256 | `aa4c15678d59d9e7d0bc269626d6a6e0c6576b09711d9ef6cc7519af65f73ba6` |

## Editor-first / lazy CPython changes

- No full-screen startup overlay. JX breathes once in the title bar; by Troy stays.
- Opening/viewing/editing a file never starts WASM. First Run or REPL starts the
  CPython worker, with a jade `Loading CPython…` status and one terminal line.
- Warm runs reuse the same interpreter. Debug continues to use the subset engine.
- Startup has a 12-second deadline. Failure/timeout terminates the worker and lets
  the pending Run use the subset; the chip says `Subset · tap Run again for CPython`.
- Next Run retries cleanly. Worker crashes release the busy state and show an error;
  partially executed code is not automatically replayed (to avoid duplicate effects).
- Run snapshots the code and files before waiting, so typing during startup does
  not change the pending program.
- Android `adjustResize` / 45% IME cap and editor keyboard layout are unchanged.
- Internal code increased to 27. This replaces the earlier code-26 build using
  the same signing key, so that particular build can update in place.

## Install

Download the APK to your Android device, open it, and allow installation from that
browser/file manager when Android prompts you.

**Back up/export your projects first.** The retained v1.3.0 APK uses certificate
`8b912d1ecc75af8abd9becb2489a8c6a95b7e9dbf3c3c9ac01658918e88f6129`.
Its private key was unavailable, so this build uses a new key and cannot update
that installation in place. Uninstalling the old app erases its local data.
For an in-place upgrade instead, rebuild with the original signing key.

## Build this APK

The standard Android SDK download was inaccessible in this environment, so this
artifact uses the repository's offline pipeline: kotlinc, dx, apktool, alignment,
and apksigner. It recompiles `MainActivity.kt`, bundles Kotlin's runtime, copies
the current IDE assets/resources, and uses the retained Blockhold v1.4.3 APK as
a resource-table template. Old unused game resources remain in the APK.

Prerequisites: Python 3 with venv support, npm, and network access for initial
tool downloads from PyPI, npm and GitHub. From the repository root:

```bash
python3 -m venv ~/.local/build-venv
~/.local/build-venv/bin/pip install jdk4py==21.0.8.2
npm install --prefix ~/.local kotlin-compiler@1.9.25
export PATH="$HOME/.local/node_modules/kotlin-compiler/bin:$PATH"
~/.local/build-venv/bin/python scripts/build-offline-apk.py
```

Output: `artifacts/Jadex-v1.3.5-installable.apk`.
Version metadata comes from `app/build.gradle.kts`. The minimum SDK was raised
from 24 to 26 because the bundled Kotlin runtime contains invokedynamic bytecode
and this dx-based pipeline does not desugar it for Android 7. Do not lower the
manifest minimum without switching to a desugaring toolchain.

The builder reuses `.signing/jadex-release.p12` and
`.signing/jadex-release.properties`; if absent it creates a new signing identity
with a random password. These files are ignored by Git. Back them up securely
outside this sandbox for future update compatibility; never commit them.
The legacy Gradle release configuration still points to Blockhold signing files;
use the offline command above to reproduce this Jadex signing workflow.

## Verification

- Kotlin compilation and DEX generation succeeded.
- APK structural checks passed: manifest, launcher, DEX checksums/type resolution,
  resource table, and ZIP alignment. One warning reports unused legacy drawables.
- `apksigner verify --verbose --print-certs` verified both v2 and v3 signatures.
- Startup lifecycle and Run/fallback tests passed (`node scripts/test-cpython-startup.js`):
  idle startup, shared boot, warm reuse, stale messages, timeout, boot failure,
  runtime crash, retry, unsupported Worker, and typing during a pending Run.
- All five worker-protocol tests passed (`node scripts/test-worker-protocol.js`).
- Kotlin pitfall lint: zero errors, ten warnings in legacy game sources.
- Browser smoke testing was blocked by the Chromium download failing.
- No Android device/emulator runtime test was performed. Static verification does
  not establish device-specific WebView/Python runtime behavior.

The under-one-second editor target still requires measurement on a real Android
device; these changes remove interpreter and overlay waits but do not prove a
specific cold-start time.
