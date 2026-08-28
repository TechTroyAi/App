# APK v1.2 — "installs but won't open" investigation

Date: 2026-08-28
Artifact under investigation: `artifacts/Blockhold-Defense-v1.2-installable.apk`

Reproduce every finding below with:

```bash
python3 scripts/verify-apk.py artifacts/Blockhold-Defense-v1.2-installable.apk
```

---

## 1. Which file is "APK v1.2"

There are two, and only one is installable:

| File | Size | Actual SHA-256 | State |
|---|---|---|---|
| `artifacts/Blockhold-Defense-v1.2-installable.apk` | 3,613,643 B | `7e43587c47f54c016a8094950fd77bd56623aeb1438c60d7d0d9edd9180340d4` | signed v1+v2+v3 — **this is the one you installed** |
| `artifacts/Blockhold-Defense-v1.2-unsigned.apk` | 3,533,660 B | `07b51c08666ce88d1819adb7c0b377e1c031058cd5d9e4309cf8253fe8f5dbe0` | unsigned, cannot install |

---

## 2. The headline problem: the shipped APK is not the APK the repo claims it is

The recorded hashes do not match the files on disk. Not one of them.

| Recorded in | Claimed SHA-256 | Reality |
|---|---|---|
| `artifacts/README.md` (top block) | `058f8a7d…a938db` | no such file |
| `artifacts/README.md` (detail block) | `c7c05ff6…1bda216` | no such file |
| `docs/VERIFICATION.md` (sideload block) | `dbd84c27…afbc26f` | no such file |
| — actual installable | — | `7e43587c…0340d4` |
| `docs/VERIFICATION.md` (unsigned block) | `a429a231…8ba8e3`, 2,325,930 B | actual is `07b51c08…`, 3,533,660 B |

`docs/VERIFICATION.md` also describes an artifact that structurally is not this one:

| Verification record says | Actual APK |
|---|---|
| 92 APK entries | 228 |
| 706 class definitions | 708 |
| "70 active drawable PNGs", "exactly 40 scoped images" | 191 drawables, 189 loaded by `SpriteCatalog` |
| "Four-byte ZIP alignment: **passed**" | **168 of 221 uncompressed entries are misaligned** |
| "`zipalign -c -p -v 4` passed" | zipalign was demonstrably never run |

So the verification record was written against an older build and never refreshed. Whatever
was actually shipped to your phone was never verified by anything.

---

## 3. Proven defect: the APK was never zipaligned

```
FAIL  168 of 221 uncompressed entries are NOT 4-byte aligned
        classes.dex @ offset 41 (offset % 4 = 1)
        res/drawable/ic_launcher_background.png @ offset 1481673 (offset % 4 = 1)
        ...
```

`classes.dex` is stored **uncompressed** at byte offset 41, which is not a multiple of 4.

Control group — the two APKs in this repo that were genuinely built by Gradle:

| APK | Uncompressed entries | Misaligned |
|---|---|---|
| `Blockhold-Defense-v0.1-debug.apk` (Gradle) | 11 | **0** |
| `Blockhold-Defense-v1.0-release.apk` (Gradle) | 39 | **0** |
| `Blockhold-Defense-v1.2-installable.apk` (apktool) | 221 | **168** |

That is conclusive: v1.2 did not come out of the Gradle/AGP pipeline. It was disassembled and
reassembled by hand with apktool and then signed, skipping `zipalign`. The v1.0 build also keeps
`classes.dex` DEFLATED; v1.2 stores it uncompressed *and* unaligned, which is the worst of both.

**Runtime effect, stated precisely:** ART refuses to `mmap` a misaligned uncompressed dex and
falls back to extracting the whole 1.4 MB dex into memory on every cold start. That is a startup
latency and memory penalty, and on a slow device it can push you past the ANR window on launch.
It is a real, must-fix packaging bug — but I want to be straight with you: on its own it is
documented as a fallback, not a hard crash. It does not by itself fully explain a refusal to open.

---

## 4. What I ruled out

I checked every structural cause of "installs but won't open" and these are all **clean**:

- **Signing** — v1 + v2 + v3 all present. (v1-only would be rejected on Android 11+ for
  targetSdk 35, but that would fail at install, and yours installed.)
- **`resources.arsc`** — stored uncompressed and 4-byte aligned, as targetSdk ≥ 30 requires.
  This is why the install succeeded.
- **DEX integrity** — magic `dex\n037`, declared size matches, adler32 checksum valid, SHA-1
  signature valid. Not corrupted or truncated.
- **Missing classes** — all 969 referenced types resolve against the 708 bundled classes. The
  Kotlin stdlib is fully embedded. No `NoClassDefFoundError` waiting to happen.
- **Manifest** — `package=ai.techtroy.blockhold`, `versionName=1.2.0`, `versionCode=12`,
  `minSdk=24`, `targetSdk=35`, `MAIN`/`LAUNCHER` intent filter present,
  `android:exported="true"` present, launch activity `ai.techtroy.blockhold.MainActivity`
  confirmed to exist inside `classes.dex`. So there *is* an icon and it *does* point at a real class.
- **Theme** — `@style/AppTheme` resolves, parent `Theme.Material.Light.NoActionBar`
  (`0x01030241`), all 7 items resolve; the `-v31` splash-screen variant is present and its
  `windowSplashScreenAnimatedIcon` resolves to a real drawable.
- **Adaptive icon** — `mipmap-anydpi-v26` / `-v33` reference valid background/foreground drawables.
- **All 189 sprites `SpriteCatalog` loads by name are packaged.** This matters more than it
  sounds: `SpriteCatalog.load()` does `resources.getIdentifier(...)` then
  `check(id != 0) { "Missing sprite resource: $name" }`. One missing PNG = guaranteed
  `IllegalStateException` inside the `GameView` constructor = exactly your symptom. None are missing.
- **All 99 sprite strips satisfy `check(width % height == 0)`**, the other startup assertion.
- **All 13 `AudioEngine` sounds are packaged.**
- **Startup bitmap budget** — ~6.1 MB decoded as ARGB_8888. Not an OOM.

---

## 5. Root cause of the process failure

`ci/android.yml` was sitting in `ci/`, not in `.github/workflows/`. **GitHub Actions never ran
it.** No build in this repo's history was ever produced or checked by CI, which is how a
hand-assembled, unaligned, unverified APK became the download people were pointed at, with a
verification document describing a different file entirely.

`docs/VERIFICATION.md` is also explicit that nobody ever launched it:

> "No Android emulator or physical Android device is available in the build environment.
> Installation, actual landscape rendering, touch ergonomics, lifecycle transitions … remain
> device-QA items. Static compilation and package checks do not replace a playtest."

The v1.2 APK was shipped having never been started once.

---

## 6. What was changed in this branch

- **`ci/android.yml`** (rewritten) — now builds with Gradle, runs
  `scripts/verify-apk.py`, runs `zipalign -c -p -v 4` and `apksigner verify`, records the
  SHA-256, and uploads the APK. **It is still inert until you move it**, because an automation
  token cannot create `.github/workflows/`. One command, then v1.2 comes out of CI correctly
  aligned instead of out of apktool:

  ```bash
  mkdir -p .github/workflows && git mv ci/android.yml .github/workflows/android.yml
  git commit -m "Activate Android CI" && git push
  ```
- **`scripts/verify-apk.py`** (new) — standard-library-only APK verifier encoding every check
  above (alignment, signing schemes, dex checksum/SHA-1/dangling types, manifest + launcher
  activity presence in the dex, resource table vs. `getIdentifier` string lookups, sprite strip
  geometry). No Android SDK needed. Run it on any APK before it goes to a phone.

---

## 7. What I still need from you

The package is structurally sound, so the remaining fault is a **runtime** exception that can
only be read off the device. One of these will tell us in one line:

```bash
# plug the phone in with USB debugging on, then:
adb logcat -c
# now tap the app icon, let it fail, then:
adb logcat -d -b crash > crash.txt
# no adb? on the phone: Settings > Developer options > Bug report, or use an app like Logcat Reader
```

Send me `crash.txt`, or the text of the "Blockhold Defense keeps stopping" dialog if you tap
**Details**.

Also useful, and answerable without any tools:

1. What exactly happens — a "keeps stopping" dialog, a black screen that sits there, or the
   icon flashes and returns to the home screen?
2. Android version and phone model?
3. Did you have the older v1.0 build installed before this one?

Once you have a Gradle-built APK (activate CI as above, or run `./gradlew assembleDebug` locally), install the CI-produced APK instead of the apktool one. If that
opens, the alignment/packaging path was the problem. If it still fails, the logcat will name
the exact line.
