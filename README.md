# Blockhold Defense

**Blockhold Defense: Pathforge Defense** is an original native Kotlin tower-defense game for Android. Forge the enemy route through a block world, build a defensive line, upgrade towers, and protect the core through five increasingly dangerous waves.

This repository contains the first playable vertical slice (`v0.1.0`), its complete source, original synthesized sound effects, reproducible asset tooling, and an installable APK.

## Install the first playable build

Download [`artifacts/Blockhold-Defense-v0.1-debug.apk`](artifacts/Blockhold-Defense-v0.1-debug.apk) to an Android phone and open it.

If Android asks for permission, allow **Install unknown apps** for the browser or file manager you used. The APK is debug-signed for direct testing and may trigger the normal Play Protect sideload warning.

### APK details

| Item | Value |
| --- | --- |
| Game | Blockhold Defense |
| Application ID | `ai.techtroy.app` |
| Version | `0.1.0` (`2`) |
| Minimum Android | Android 7.0 / API 24 |
| Target SDK | API 35 |
| Orientation | Landscape |
| Permissions | None requested |
| SHA-256 | `b22b15123d176550c0e52e6093ce7e9c5a84b258eea21c4843a543d7b123fa47` |

## How to play

1. From the gate on the left, drag through adjacent blocks until the path reaches the glowing core on the right.
2. Backtrack over the previous block to undo a path segment, or use **Reset** before the first wave.
3. Select a defense from the bottom bar and tap the battlefield to build.
4. Towers require free terrain blocks. Spike traps must be placed directly on the route.
5. Tap an existing tower during the build phase to upgrade or recycle it.
6. Press **Start Wave** and survive all five waves.

### Defenses

- **Bolt Tower — 70 blocks:** fast, reliable single-target damage
- **Frost Prism — 90 blocks:** slows enemies while dealing damage
- **Core Cannon — 125 blocks:** slower explosive shots with area damage
- **Spike Bed — 45 blocks:** damages every enemy that crosses its path block

### Enemies

- **Mosser:** balanced frontline creature
- **Runner:** fragile but fast
- **Brute:** slow, armored, and damaging to the core
- **The Overgrowth:** final-wave boss

## First-version feature set

- Player-forged route with backtracking and route-length limit
- Twelve-by-seven adaptive battlefield
- Three animated tower families with three upgrade levels
- Placeable path traps
- Five authored waves and a boss encounter
- Automatic targeting, projectiles, splash damage, slowing, and health states
- Block economy, tower recycling, core health, scoring, and saved best score
- Pause, retry, main-menu, and sound controls
- Procedural block artwork, characters, particles, screen shake, and UI animation
- Ten original synthesized sound effects
- Fullscreen landscape layout that scales across Android phones
- No third-party game engine or runtime dependency

## Original assets

All current visuals are drawn at runtime using native Android Canvas geometry. The sound effects are generated from oscillators and deterministic noise—no third-party samples are included.

Regenerate the complete sound pack with:

```bash
python3 tools/generate_sfx.py
```

## Build from source

### Requirements

- JDK 17
- Android SDK Platform 35 and Android Build Tools
- Internet access for the first Gradle dependency download

Android Studio can open this repository directly. From a terminal:

```bash
./gradlew assembleDebug
```

The Gradle output is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To build and refresh the convenient artifact:

```bash
./scripts/build-apk.sh
```

On Windows, run `gradlew.bat assembleDebug` and copy the resulting APK manually.

### Enable GitHub Actions

The APK workflow recipe is stored at `ci/android.yml`. Copy it to `.github/workflows/android.yml` from a GitHub account or App with workflow-write permission to activate automatic APK artifacts.

## Project map

```text
app/src/main/java/ai/techtroy/app/MainActivity.kt  Immersive Android host
app/src/main/java/ai/techtroy/app/GameView.kt      Game loop, input, simulation, rendering
app/src/main/java/ai/techtroy/app/GameModels.kt    Gameplay entities and balance values
app/src/main/java/ai/techtroy/app/AudioEngine.kt   Native SoundPool playback
app/src/main/res/raw/                              Original generated sound effects
tools/generate_sfx.py                              Reproducible sound synthesizer
artifacts/Blockhold-Defense-v0.1-debug.apk         Installable first version
```

## Release note

This first APK is a debug testing build. A production release will require a private release keystore stored outside Git, secure CI configuration, expanded device testing, and a signed Android App Bundle or release APK.
