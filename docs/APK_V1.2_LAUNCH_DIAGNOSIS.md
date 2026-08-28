# APK v1.2 — "installs but won't open" investigation

Date: 2026-08-28
Artifact under investigation: `artifacts/Blockhold-Defense-v1.2-installable.apk`
Reported by: player upgrading from v1.1 — "Blockhold Defense keeps stopping"

Reproduce every packaging finding below with:

```bash
python3 scripts/verify-apk.py artifacts/Blockhold-Defense-v1.2-installable.apk
```

---

## 0. Try this first (30 seconds, no tools)

**Settings → Apps → Blockhold Defense → Storage → Clear data**, then open the app.

The reporter upgraded from v1.1, which left its saved run and best-score keys behind in
`SharedPreferences("blockhold_infinite_progress")`. v1.2 reads six of those keys **in the
`GameView` constructor**, before the first frame is ever drawn:

```kotlin
private var bestDailyScore = preferences.getInt("best_daily_score",
                                 preferences.getInt("best_challenge_score", 0))
```

`SharedPreferences.getInt` throws `ClassCastException` if an earlier build wrote that key with a
different type. Thrown there, it kills the activity before anything renders — which is exactly
"installed fine, won't open", and exactly why a fresh install would be unaffected while an
upgrade over v1.1 dies. Clearing data removes the old keys.

If clearing data fixes it, that was the cause. If it does not, the new on-screen crash reporter
(section 6) will print the real reason on the next build.

---

## 1. Confirmed code defects in v1.2

### 1a. 22 Kotlin empty-collection crashes (the "can't play" half)

The game logic was written against Kotlin ≤1.3 collection semantics but this project compiles
with **Kotlin 2.0.21** (`build.gradle.kts`). In Kotlin 1.7 the stdlib reintroduced `maxBy`,
`minBy`, `max()` and `min()` as **non-null, throwing** functions — `maxBy` is literally annotated
`@JvmName("maxByOrThrow")`. Twenty-two call sites still assume the old nullable behaviour:

```kotlin
// before — throws NoSuchElementException whenever no Cache Depot is built, i.e. always at first
val depot = utilities.filter { it.kind == UtilityKind.CACHE_DEPOT }.maxBy { it.level }
return min(20, 4 + if (depot == null) 0 else ...)
```

Every one of these sites already null-checks the result (`if (x == null)`, `?.let`, `?: return 1f`,
`?: 0`), so the compiler only emits "unnecessary safe call" / "elvis always returns left operand"
**warnings** — it builds cleanly and then throws at runtime the moment the collection is empty.

Worst offenders, all on the normal play path with a fresh board:

| Site | Empty when | Blast radius |
|---|---|---|
| `cacheCapacity()`, `cacheStorageDiscount()` | no Cache Depot built | 8 + 2 call sites |
| `workshopLevel()` | no Forge Workshop built | 6 call sites |
| `recyclingMultiplier()` | no Salvage Yard built | 4 call sites |
| `surveyPreviewText()` | no Surveyor Station | every wave preview |
| `updateCorruptions()` | no towers / no utilities placed | every frame during a Hex Bloom |
| Hex Weaver + Carrion Hulk targeting | all towers disabled | mid-wave |
| `nextTrapId` / `nextCorruptionId` in `loadSavedRun()` | no traps saved | every "Continue Run" |

**Fixed:** all 22 converted to `maxByOrNull` / `minByOrNull` / `maxOrNull`, restoring the
semantics the surrounding null checks were written for. No other bytes in `GameView.kt` changed.

### 1b. Unguarded preference reads on the upgrade path

**Fixed:** added `prefInt` / `prefBoolean` helpers that swallow `ClassCastException` and fall
back to the default, and routed the eight constructor-time and title-screen reads through them.
A future version changing a key's type can no longer brick the app.

### 1c. The APK was never zipaligned

168 of 221 uncompressed entries are misaligned, including `classes.dex` (stored uncompressed at
offset 41). Control group:

| APK | Uncompressed entries | Misaligned |
|---|---|---|
| `v0.1-debug.apk` (Gradle) | 11 | **0** |
| `v1.0-release.apk` (Gradle) | 39 | **0** |
| `v1.2-installable.apk` (apktool) | 221 | **168** |

Conclusive: v1.2 never came out of the Gradle/AGP pipeline — it was disassembled and reassembled
with apktool, then signed, skipping `zipalign`. ART cannot `mmap` a misaligned uncompressed dex
and re-extracts all 1.4 MB on every cold start. That is a startup latency and memory penalty that
can push a slow device past the launch ANR window. To be precise: it is documented as a fallback
rather than a hard failure, so on its own it does not fully explain the crash — but it must not
ship again.

---

## 2. The APK's structure is otherwise sound

Everything else checks out, which is what pointed the investigation at runtime code rather than
packaging:

- **Signing** — v1 + v2 + v3 all present.
- **`resources.arsc`** — stored uncompressed and 4-byte aligned, as targetSdk ≥ 30 requires.
  This is why the install itself succeeded.
- **DEX integrity** — magic `dex\n037`, declared size matches, adler32 valid, SHA-1 valid.
- **Missing classes** — all 969 referenced types resolve against 708 bundled classes; the Kotlin
  stdlib is fully embedded.
- **Manifest** — `ai.techtroy.blockhold` 1.2.0 (12), minSdk 24, targetSdk 35, MAIN/LAUNCHER filter
  present, `android:exported="true"` present, and `MainActivity` confirmed present in `classes.dex`.
- **Theme / adaptive icon** — `AppTheme` and both `mipmap-anydpi` variants resolve fully.
- **All 189 sprites and 13 sounds are packaged.** This matters: `SpriteCatalog.load()` does
  `getIdentifier` then `check(id != 0)`, so one missing PNG would throw in the `GameView`
  constructor. None are missing.
- **All 99 sprite strips satisfy `check(width % height == 0)`.**
- **Startup bitmap budget** ≈ 6.1 MB as ARGB_8888. Not an OOM.

---

## 3. The records do not describe the shipped file

| Recorded in | Claimed SHA-256 | Reality |
|---|---|---|
| `artifacts/README.md` (top block) | `058f8a7d…a938db` | no such file |
| `artifacts/README.md` (detail block) | `c7c05ff6…1bda216` | no such file |
| `docs/VERIFICATION.md` (sideload block) | `dbd84c27…afbc26f` | no such file |
| — actual installable | — | `7e43587c…0340d4` |
| `docs/VERIFICATION.md` (unsigned block) | `a429a231…`, 2,325,930 B | `07b51c08…`, 3,533,660 B |

`docs/VERIFICATION.md` also describes a structurally different artifact — 92 entries vs 228,
706 classes vs 708, "70 active drawable PNGs" vs 191 — and claims
"Four-byte ZIP alignment: **passed**" and "`zipalign -c -p -v 4` passed", both false.

---

## 4. Root cause of the process failure

`ci/android.yml` was sitting in `ci/`, not `.github/workflows/`. **GitHub Actions never ran it.**
Nothing in this repo's history was ever built or verified by CI, which is how a hand-assembled,
unaligned APK became the advertised download with a verification document describing a different
file.

`docs/VERIFICATION.md` is also explicit that nobody ever launched it:

> "No Android emulator or physical Android device is available in the build environment.
> Installation, actual landscape rendering, touch ergonomics, lifecycle transitions … remain
> device-QA items. Static compilation and package checks do not replace a playtest."

The v1.2 APK shipped having never been started once. The Kotlin 1.7 collection-semantics
regression is precisely the class of bug that compiles clean, passes every static check in that
document, and dies on first contact with a device.

---

## 5. Changes in this branch

### Code fixes
- **`GameView.kt`** — 22 `maxBy`/`minBy`/`max()` calls converted to their `…OrNull` forms;
  `prefInt`/`prefBoolean` guards added around preference reads; `cancelReforge()` no longer
  wipes `pathCells` when `reforgeOriginalPath` is empty (an empty route makes every later frame
  throw on `pathCells.first()`/`last()`).
- **`MainActivity.kt`** — startup is now fault-tolerant (see below).

### Audited and confirmed safe (no change needed)
- All 7 `!!` sites are inside a matching `!= null` guard or immediately follow the assignment.
- All 9 `SpriteCatalog.getValue` calls — every enum constant is mapped, now enforced by lint.
- All 6 `pathCells.first()/last()` calls — every `pathCells.clear()` immediately repopulates.
- `waveQueue[spawnIndex]` is bounded by `spawnIndex < waveQueue.size`.
- `CraftedItem.values().getOrNull(index)` in the workshop already uses the safe accessor.

### Tooling
- **`scripts/verify-apk.py`** (new) — standard-library-only APK verifier: ZIP alignment, signing
  schemes, dex checksum/SHA-1/dangling type refs, manifest + launcher class presence in the dex,
  resource table vs. `getIdentifier` string lookups, and sprite strip geometry. No Android SDK
  required.
- **`scripts/lint-kotlin-pitfalls.py`** (new) — fails the build on `maxBy`/`minBy`/`max()`/`min()`
  and friends, warns on `first()`/`last()`/`reduce`/`getValue`/raw preference reads, and proves
  every enum constant is mapped in each `SpriteCatalog` map so the nine `getValue` calls cannot
  throw. Currently: **0 errors**, 15 audited warnings.
- **`ci/android.yml`** (rewritten) — runs the lint, builds with Gradle, runs the verifier, runs
  `zipalign -c -p -v 4` and `apksigner verify`, records the SHA-256, uploads the APK.
  **Still inert until moved**, because an automation token cannot create `.github/workflows/`:

  ```bash
  mkdir -p .github/workflows && git mv ci/android.yml .github/workflows/android.yml
  git commit -m "Activate Android CI" && git push
  ```
- Stale-record warnings added to `docs/VERIFICATION.md` and `artifacts/README.md`.

---

## 6. No more blind crashes

`MainActivity` now:

1. Installs a `Thread.setDefaultUncaughtExceptionHandler` that persists the stack trace, so a
   crash on the **render thread** — where most of the bugs in section 1a live — is captured
   instead of vanishing into "keeps stopping".
2. Wraps `GameView` construction in `try/catch`, so a startup failure shows a screen instead of
   killing the process.
3. Shows a recovery screen with the full trace (device, Android version, app version, selectable
   text so it can be long-pressed and copied) plus two buttons: **Continue / Try again** and
   **Reset saved data**.

That last button is the built-in fix for the section 0 scenario, and the copyable trace means the
next report arrives with the exact line number attached and no `adb` needed.

---

## 7. Next steps

1. **Right now:** clear app data on the phone and reopen v1.2.
2. **Then:** activate CI with the two commands in section 5, or build locally with
   `./gradlew assembleDebug`. Install *that* APK — it will be properly aligned and will contain
   the 22 crash fixes and the crash reporter.
3. If it still misbehaves, the recovery screen will show the exact exception; send that text.
4. Re-run `python3 scripts/verify-apk.py --all` and rewrite `docs/VERIFICATION.md` from its actual
   output rather than by hand.
