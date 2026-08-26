# Blockhold Defense

**Blockhold Defense: Endless Pathforge** is an original, offline native-Kotlin tower-defense game for Android. Forge the only enemy route from the entrance gate to the protected core, build a mixed defense, and survive an endless procedural assault.

The game uses its own identity and mechanics. It does not include Minecraft names, artwork, sounds, characters, maps, UI, or code.

## Install

The current release artifact is `artifacts/Blockhold-Defense-v1.0-release.apk`.

On an Android phone, download the APK, open it, and allow **Install unknown apps** for the browser or file manager if Android requests it. The APK is signed with the dedicated Blockhold Defense release identity for direct installation and future updates.

| Item | Value |
| --- | --- |
| Public name | Blockhold Defense |
| Mode | Endless Pathforge |
| Application ID | `ai.techtroy.blockhold` |
| Version | `1.0.0` (`10`) |
| Minimum Android | Android 7.0 / API 24 |
| Target SDK | API 35 |
| Orientation | Landscape |
| Permissions | None requested |
| Network requirement | None; fully offline |

See `artifacts/README.md` for the final APK hash and signing-certificate fingerprint, and `docs/VERIFICATION.md` for the complete static verification record.

## How to play

1. Drag from the gate on the left through adjacent blocks until the route reaches the glowing core on the right.
2. Backtrack over the previous block to undo a segment. The route locks when it reaches the core.
3. Switch between **Towers** and **Traps** in the bottom build bar.
4. Put towers on free terrain and traps directly on empty route blocks.
5. Tap any existing defense between waves to upgrade, overcharge, or recycle it.
6. Start the next wave. Combat, targeting, status effects, and projectiles are automatic.
7. Continue until the core is destroyed. A checkpoint is saved between waves and can be resumed from the title screen.

## Defenses

### Towers

- **Bolt Tower — 70 Blocks:** fast single-target fire
- **Frost Prism — 90 Blocks:** slows targets and pierces part of their armor
- **Core Cannon — 125 Blocks:** heavy area damage
- **Ember Forge — 145 Blocks:** splash damage plus burning damage over time
- **Resonance Beacon — 170 Blocks:** long-range arcs that chain through groups

### Traps

- **Spike Bed — 45 Blocks:** direct route damage
- **Root Snare — 65 Blocks:** damage plus a long slow
- **Ember Rune — 85 Blocks:** impact and burn damage
- **Arc Plate — 110 Blocks:** chain damage plus a short stun
- **Crusher Block — 145 Blocks:** very heavy damage and a full stun

Towers and traps have three normal levels. After level three, repeatable **Overcharge** ranks provide a continuing long-run currency sink with escalating costs.

## Enemy roster

### Regular threats

- **Mosser:** balanced frontline unit
- **Runner:** fragile, high-speed attacker
- **Brute:** slow unit with high health and core damage
- **Shellback:** armored against ordinary damage
- **Splitling:** divides into two fast minions when destroyed

### Elite threats

Elites recur approximately every five waves:

- **Ironhide Champion:** extreme armor
- **Blink Stalker:** periodically jumps forward on the route
- **Rootcaller:** heals nearby enemies
- **Hex Weaver:** temporarily disables towers
- **Siege Colossus:** huge health and core damage

### Recurring boss

**The Overgrowth** returns every ten waves. Each recurrence gains a mutation tier, stronger scaling, regeneration, summoned Splitlings, and eventually multi-tower sabotage.

## Endless director

The bounded-memory wave director generates swarm, rush, armored, regeneration, sabotage, siege, split, mixed, elite, and boss encounters. Enemy health, speed, rewards, elite density, and boss mutation tiers continue scaling without storing an infinite authored wave list.

A run records:

- Forged route
- Towers, levels, and Overcharge ranks
- Traps, levels, and Overcharge ranks
- Blocks, core health, score, and completed wave
- Best score and highest wave

Checkpoints are written only between waves. Leaving during a live wave returns **Continue** to the last safe build checkpoint.

## Visual and audio assets

The game now uses a coherent top-down sprite layer instead of color-only entity blocks. Included Kenney visual assets are CC0 and have an auditable source record in `docs/ASSET_LICENSES.md`; no attribution is legally required. The icon family and Canvas effects are original.

All sound effects are original deterministic synthesis. Regenerate them with:

```bash
python3 tools/generate_sfx.py
```

## Build from source

### Requirements

- JDK 17
- Android SDK Platform 35 and Android Build Tools
- Gradle dependency access for a first standard Android Studio build

Open the repository in Android Studio or run:

```bash
./gradlew assembleDebug
```

The Gradle output is written to `app/build/outputs/apk/debug/app-debug.apk`. The helper script refreshes a convenient debug artifact:

```bash
./scripts/build-apk.sh
```

The release key is intentionally not in Git. See `docs/SIGNING.md` for production-key handling and verification.

## Project map

```text
app/src/main/java/ai/techtroy/blockhold/MainActivity.kt  Immersive Android host
app/src/main/java/ai/techtroy/blockhold/GameView.kt      Input, endless simulation, saves, rendering
app/src/main/java/ai/techtroy/blockhold/GameModels.kt    Rosters, entities, balance, progression
app/src/main/java/ai/techtroy/blockhold/SpriteCatalog.kt Offline sprite loader
app/src/main/java/ai/techtroy/blockhold/AudioEngine.kt   Native SoundPool playback
app/src/main/res/drawable-nodpi/                         CC0 gameplay sprite layer
app/src/main/assets/licenses/                            Pack license texts
docs/ASSET_LICENSES.md                                   Source/license audit
docs/SIGNING.md                                          Permanent signing procedure
artwork/                                                 Original icon source and store export
tools/generate_sfx.py                                    Reproducible original audio
```

## Testing note

The repository can be compiled, signed, and statically verified in the provided environment, but no Android emulator or physical device is available there. Device-specific play feel and balance should therefore be validated through installation feedback.
