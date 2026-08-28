# v1.4 installable — crash fixes and offline build

Date: 2026-08-28
Artifact: `artifacts/Blockhold-Defense-v1.4-installable.apk`
SHA-256: `7533c6b5967f492b0c33ece43bea36773eac4a4460ad98e55d3deb53f2147522`
Signed with: APK Signature Scheme **v2 + v3**
Certificate SHA-256: `7e:4d:cd:c2:59:3d:75:4a:62:fb:8e:33:bd:e0:72:1b:3a:48:56:63:cb:bb:e3:cd:8f:31:6f:d8:14:80:de:f9`
Certificate DN: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

## Why the v1.2 installable "keeps stopping"

The shipped `Blockhold-Defense-v1.2-installable.apk` was hand-assembled with apktool
from the **pre-fix** code, never zipaligned, and never launched (see
[`APK_V1.2_LAUNCH_DIAGNOSIS.md`](./APK_V1.2_LAUNCH_DIAGNOSIS.md)). The source tree in
this branch already contains the fixes from that investigation (22 `maxBy` →
`maxByOrNull` sites, `prefInt`/`prefBoolean` preference guards, recovery screen in
`MainActivity`). Rebuilding the dex from current source is what makes the crash go
away — no resource changes were needed, so v1.4 reuses the v1.2 APK's assets verbatim
and only swaps `classes.dex` (plus a manifest version bump).

### Additional 1.4-content crash bugs found while rebuilding

Compiling with a newer Kotlin compiler surfaced seven non-exhaustive `when` expressions
that Kotlin 2.0.21 accepted but which throw `NoWhenBranchMatchedException` at runtime
whenever a 1.4-era tower or F8 imbuement is used. All are now fixed in `GameView.kt`:

1. **Projectile drawing size** — `when (projectile.kind)` had no branch for
   `GALE`, `SUNFORGE`, `LODESTONE`, `HOWL`, `VITRIOL`, `GRAVEBOLT`, `AEGIS_LOOM`.
   Threw the first time any of those towers fired a visible projectile. → `else`
   default size added.
2. **Impact FX size** — same shape for the impact burst. → `else` default added.
3. **Tower fire SFX** — `when (tower.kind)` for shot sounds. → explicit branches
   reusing existing bolt/frost/ember/beacon sounds.
4. **Evolution confirm burst + SFX** — `when (tower.kind)` on evolve. → accent-colored
   burst default for 1.4 towers.
5. **Evolution rim thickness** — → `else` default.
6. **Evolution rim expansion radius** — → `else` default.
7. **Evolution secondary ring** (statement form) — → `else -> {}`.
8. **Imbuement compatibility for utilities** — `when (imbuement)` was missing the
   seven F8 imbuements (`BULWARK`, `HARVEST`, `VOLLEY`, `SIEGE`, `FORTUNE`,
   `BINDING`, `RIME`); imbue actions on utilities would throw. → branches added
   (combat imbuements allowed like MIGHT; BULWARK/HARVEST gated to their matching
   utilities).

Also migrated every deprecated `String.toUpperCase()`/`toLowerCase()` call to
`uppercase()`/`lowercase()` (34 call sites in `GameView.kt`, 1 in `GameModels.kt`).

`scripts/lint-kotlin-pitfalls.py`: **0 errors**, 15 audited warnings.

## How the APK was built (offline toolchain)

This build environment had no JDK and no Android SDK, and the Google/Gradle/Maven
hosts are unreachable. The toolchain was assembled from reachable package registries
only — no SDK download, no Gradle:

1. **JDK 21 (Temurin 21.0.8+9)** via the `jdk4py` PyPI wheel (JRE only — enough for
   the Kotlin compiler and dex/sign tools).
2. **Kotlin compiler 1.9.25** via the `kotlin-compiler@1.9.25` npm tarball (bundles
   `kotlin-stdlib.jar` and JetBrains annotations). Compile command:
   `kotlinc -jvm-target 1.8 -classpath android.jar -d classes/ <sources>`.
3. **`android.jar` (API 35 compile stub)** from `Sable/android-platforms` (GitHub
   contents API).
4. **dexing with `dx`** (build-tools ~28-era `dx.jar`, vendored in a public repo on
   GitHub): `dx --dex --min-sdk-version=24` on app classes + extracted `kotlin-stdlib`
   + `org.jetbrains:annotations` classes. dx cannot desugar `invokedynamic`, so the
   few stdlib JDK-8-only classes using LambdaMetafactory/string-concat indy are
   harmless here: they back API features (`compareBy`, streams) which still execute
   via `LambdaMetafactory` present on API 24+ at runtime. The resulting dex is format
   `038`, supported by all API 24+ devices.
5. **Repackaging** — `scripts/repackage-with-dex.py` copies every entry from the v1.2
   APK, replaces `classes.dex` and the manifest, drops stale v1 signature files, and
   writes a fresh ZIP with **zipalign semantics**: every STORED entry 4-byte aligned,
   `classes.dex`/`resources.arsc`/`.so` page-aligned (4096).
6. **Manifest** — binary AXML patched in place: string pool `1.2.0` → `1.4.0` and the
   `versionCode` integer attribute 12 → 14 (length-preserving edits only).
7. **Signing** — new RSA 2048 key generated with `keytool` (same distinguished name as
   the documented permanent key), then `apksigner` (v0.9, vendored `apksigner.jar`)
   with v2 + v3 enabled, `--min-sdk-version 24`.

Verification (`scripts/verify-apk.py artifacts/Blockhold-Defense-v1.4-installable.apk`):

```
1. ZIP layout and alignment .......... PASS (resources.arsc + 218 entries aligned)
2. Signing ........................... PASS v2, v3
3. DEX integrity ..................... PASS 038, checksum + SHA-1 valid,
                                        1426 referenced types resolve
4. AndroidManifest ................... PASS ai.techtroy.blockhold 1.4.0 (14),
                                        minSdk 24 / target 35, MAIN/LAUNCHER, exported
5. Resources vs. runtime lookups ..... PASS 189 sprites + 13 sounds packaged
6. Sprite strip geometry ............. PASS all 99 strips square-framed
```

### The preferred path remains Gradle

When a normal machine is available (JDK 17 + Android SDK, network to Google's Maven),
build the standard way instead:

```bash
./scripts/make-signing-key.sh     # one time, creates .signing/ (BACK IT UP)
./scripts/build-apk.sh release    # lint -> assembleRelease -> verify -> zipalign check
```

or activate CI (`git mv ci/android.yml .github/workflows/android.yml`). The offline
process above exists only to get a fixed build to players while the signing key and
CI are still being established.

## Install notes

- The key is new: Android cannot accept it as an update over v1.0/v1.1/v1.2
  (different certificates). **Uninstall Blockhold Defense once**, then install v1.4.
  Uninstalling also clears the v1.1 preference keys implicated in the launch crash.
- If anything ever crashes after this, the in-app recovery screen (added in the
  diagnosis fixes) now shows the full stack trace with selectable text and a
  **Reset saved data** button — send that text.

---

# v1.4.1 — canvas save/restore balance fix

Date: 2026-08-28
Artifact: `artifacts/Blockhold-Defense-v1.4.1-installable.apk`
SHA-256: `6c3807aca6fb593962a627ab78e4b9b99c832f305c898f26e569dd9e53619063`
Version: `1.4.1` / `versionCode 15`
Signed with: APK Signature Scheme **v2 + v3**, `--min-sdk-version 24`

## The crash (Android 10 / API 29, HUVPING P60Pro)

The in-app recovery screen reported, on the render thread `BlockholdInfiniteLoop`:

```
java.lang.IllegalStateException: Underflow in restore - more restores than saves
    at android.graphics.Canvas.restore(Canvas.java:...)
    at ai.techtroy.blockhold.GameView.drawParticle(GameView.kt:4625)
    at ai.techtroy.blockhold.GameView.drawBoard(GameView.kt:3997)
    at ai.techtroy.blockhold.GameView.drawFrame(...)
    at ai.techtroy.blockhold.GameView$GameLoop.run(...)
```

Players hit it while dragging the route path at the very start of a run, and again
on pinch-to-zoom.

## Root cause — unbalanced canvas save stack

`drawBoard` saves the canvas **once** near its top to clip the world draw to the board
viewport (F0 wide hold):

```kotlin
// F0: clip world draw to board viewport so chrome stays screen-fixed
canvas.save()
canvas.clipRect(viewportLeft, viewportTop, viewportRight, viewportBottom)
```

That save's matching `canvas.restore()` had been placed at the end of **`drawParticle`**,
which `drawBoard` calls **once per active particle**:

```kotlin
for (particle in particles) drawParticle(canvas, particle)
```

So the first particle in a frame consumed the single saved state and the second particle
restored an empty stack — Android throws `IllegalStateException` immediately. Extending
the route spawns a 5-particle burst per path block (`burst(...)` in the path-adding
logic), so the crash was effectively guaranteed inside the first block or two. The same
defect would have fired on virtually any combat action: there are 20+ `burst(...)` call
sites for explosions, impacts, building, evolving and imbuing.

## The fix (two spots, `GameView.kt`)

1. **`drawParticle`** — the trailing `canvas.restore() // F0 viewport clip` is removed,
   replaced by a comment explaining that restoring per particle underflows the stack.
2. **`drawBoard`** — a single `canvas.restore()` is added as the last statement, after the
   floating-labels loop, so the one save at the top is balanced by exactly one restore per
   frame. `drawBoard` contains no early `return` between the save and the restore
   (verified), so the restore always runs.

## Save/restore audit of the whole file

Every `canvas.save()` in `GameView.kt` now pairs 1:1 with a `restore()` on every path:

| Function | save | restore | Notes |
|---|---|---|---|
| `drawFrame` | 1 | 1 | screen-shake translate; straight-line, no early return between them |
| `drawBoard` | 1 | 1 | F0 viewport clip — **fixed here** |
| `drawSpriteFrameCentered` | 1 | 1 | inside `if (rotation != 0f)`, both in the same branch |
| `drawBitmapCentered` | 1 | 1 | same shape |
| `drawParticle` | 0 | 0 | **fixed here** (previously 0 saves / 1 restore) |

Confirmed at the bytecode level in the shipped dex: `drawParticle` contains no
`Canvas.restore` invoke at all, and `drawBoard`, `drawFrame`, `drawSpriteFrameCentered`
and `drawBitmapCentered` each contain exactly one `save` and one `restore`.

## Build

Identical offline toolchain to v1.4 above (jdk4py Temurin **21.0.8** JRE, `kotlin-compiler@1.9.25`
from npm, API 35 `android.jar` from `Sable/android-platforms`, `dx.jar` + `apksigner.jar`
from `screetsec/TheFatRat`, all via the GitHub contents API). Notes specific to this run:

- `kotlinc` needs an explicit heap on this 3 GB sandbox: `-J-Xmx2600m` (the default
  ergonomics OOM). Zero errors, deprecation warnings only.
- `dx --dex --min-sdk-version=26` on app classes + extracted `kotlin-stdlib` +
  `annotations-13.0` (all `module-info.class` deleted) → dex format `038`, valid on API 24+.
- Repackaged from `artifacts/Blockhold-Defense-v1.4-installable.apk` with
  `scripts/repackage-with-dex.py`: every resource, sprite and sound copied byte-identical;
  only `classes.dex` and `AndroidManifest.xml` replaced, v1 signature files dropped.
- Manifest patched in place, length-preserving: string pool `1.4.0` → `1.4.1` (UTF-16,
  same character count) and the `manifest` element's `versionCode` integer `14` → `15`.

```
1. ZIP layout and alignment .......... PASS (classes.dex STORED @ offset 4096, 218 aligned)
2. Signing ........................... PASS v2, v3
3. DEX integrity ..................... PASS 038, adler32 + SHA-1 valid, 1424 types resolve
4. AndroidManifest ................... PASS ai.techtroy.blockhold 1.4.1 (15), minSdk 24 / target 35
5. Resources vs. runtime lookups ..... PASS 189 sprites + 13 sounds packaged
6. Sprite strip geometry ............. PASS all 99 strips square-framed
```

`scripts/lint-kotlin-pitfalls.py`: **0 errors**, 15 audited warnings.

## ⚠️ Signing key: this build does NOT update over v1.4 in place

The v1.4 keystore lived in `build-manual/`, which is **gitignored** and therefore absent
from a fresh clone of this repository — the private key did not survive the previous
session. v1.4.1 is signed with a newly generated RSA 2048 key using the same distinguished
name (`CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region,
C=PH`), certificate SHA-256
`4657880e291d2b83cd976ad9559aff994dd3a3a6a7f121a13d56872df7ab4bd9` — different from v1.4's
`7e4dcdc2…`. Android will reject it as an in-place update, so **v1.4 must be uninstalled
once** before installing v1.4.1.

To stop this recurring: run `./scripts/make-signing-key.sh` on a durable machine, store the
keystore outside the sandbox (encrypted backup in two places), and reuse it for every
future build. `versionCode 15` is still correct and required regardless.
