# Jadex v1.3.6

Built from the current Kotlin MainActivity and bundled IDE assets on 2026-09-08.

| Field | Value |
|---|---|
| APK | `Jadex-v1.3.6-installable.apk` |
| Package | `ai.techtroy.jadex` |
| Version | `1.3.6` / code `28` |
| Android | 8.0 (API 26) or newer; target API 35 |
| Size | 20,013,995 bytes (19.1 MiB) |
| Signing | RSA 4096, APK signature v2 + v3 |
| APK SHA-256 | `e2907b9a4c4fe863c6ae529e1495cac18cdd59c9f97a706a5d056836cd759daa` |
| Certificate SHA-256 | `78dd08ba8e1ce5e520606492f1560e6ced744191596ac6b8bc72112749e4f008` |

## ⚠️ Read before installing: this is a fresh signing identity

v1.3.6 is **not** update-compatible with v1.3.5. The `.signing/` directory was not
present in the checkout (it is git-ignored), so the offline builder minted a new
RSA 4096 key. Android rejects an update whose certificate differs from the
installed one with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

| Build | Certificate SHA-256 |
|---|---|
| v1.3.5 | `aa4c15678d59d9e7d0bc269626d6a6e0c6576b09711d9ef6cc7519af65f73ba6` |
| v1.3.6 | `78dd08ba8e1ce5e520606492f1560e6ced744191596ac6b8bc72112749e4f008` |

**To install:** export your projects, uninstall the old Jadex, then install v1.3.6.
Uninstalling erases the app's local data, and IDE files live in browser
localStorage inside the app — back them up first.

**Keep the new key.** `.signing/jadex-release.p12` +
`.signing/jadex-release.properties` now define the Jadex identity going forward.
If they are lost again, the next build cannot update v1.3.6 either. They are
git-ignored on purpose (never commit a private key) — store them somewhere
durable, e.g. a password manager or private backup.

To prevent silent recurrences, `scripts/build-offline-apk.py` now **aborts** when
no keystore is present. Starting a deliberately new identity requires:

```bash
JADEX_ALLOW_NEW_KEY=1 python3 scripts/build-offline-apk.py
```

Verify update compatibility before shipping:

```bash
python3 scripts/check-update-compatible.py \
  --reference artifacts/Jadex-v1.3.5-installable.apk \
  artifacts/Jadex-v1.3.6-installable.apk
```

## Fixes in this build

### Line numbers rendered as `1 2 3` on one wrapped row

The gutter was filled as plain text (`"1\n2\n3\n"`) but `#gutter` never set
`white-space`, so HTML collapsed the newlines to spaces and word-wrapped the run
of numbers inside the 46px column. Code lines stayed one per row; the numbers did
not, so nothing lined up.

The gutter is now built as one `<span class="gl">` element per line, so a line
number is structurally one row tall and cannot collapse or wrap. `white-space: pre`
and `font-variant-numeric: tabular-nums` back it up.

### Root cause: a null crash killed the tail of `app.js`

`app.js` assigned `palQ.oninput`, but `#palette`, `#palette-q` and `#palette-list`
existed only in `app.css` — they were never in `index.html`. The assignment threw
`Cannot set properties of null`, and **every statement after it stopped running**:

- the `⌘` command palette (all 9 commands) — completely dead
- the `Ctrl+P`, `Ctrl+F` and `F5` shortcuts
- the init tail: `applyFont()`, `applyWrap()`, `applyHoriz()`, `renderFiles()`,
  `syncEditor()`

Because init never completed, the gutter was only ever written by a later stray
event, in the collapsed state above. The palette markup was added to
`index.html`, and the palette wiring is now null-safe so one missing node can no
longer take down the rest of the editor.

### Gutter click mapped to the wrong line

The breakpoint handler used `e.offsetY`, which became relative to whichever child
span was clicked once the gutter had element rows. It now measures against the
gutter's own box (accounting for scroll and padding) and clamps to the line count.

### Smaller editor improvements

- The current line is highlighted in the gutter and tracks caret movement.
- Breakpoint dots reserve their column (`visibility: hidden` when off), so numbers
  no longer shift sideways when a breakpoint is toggled.
- Clicking the palette backdrop dismisses it.

## Verification

`scripts/verify-apk.py` reports **0 failures** (1 pre-existing warning about
unreferenced legacy game drawables):

- ZIP layout: `resources.arsc` and `classes.dex` STORED, 285 entries 4-byte aligned
- Signing: v2 + v3 present and verified by `apksigner verify`
- DEX: format 038, adler32 + SHA-1 valid, all 1330 referenced types resolve
- Manifest: `package=ai.techtroy.jadex versionName=1.3.6 versionCode=28`,
  `minSdk=26`, `targetSdk=35`, exported MAIN/LAUNCHER activity present in DEX

The shipped assets were read back out of the built APK to confirm the fixes are
actually packaged (`renderGutter` present in `app.js`, `palette-q` present in
`index.html`, the collapsing `gutter.textContent = g` write gone).

The APK is byte-for-byte the same *size* as v1.3.5 (20,013,995) because the
uncompressed ~19 MiB Pyodide payload dominates and the edited text assets are
small; the SHA-256 differs.

## Install

Download the APK to your Android device, open it, and allow installation from that
browser/file manager when Android prompts you. See the signing note above — you
must uninstall v1.3.5 first.

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
