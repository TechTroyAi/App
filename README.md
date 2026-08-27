# Blockhold Defense

**Blockhold Defense: Endless Pathforge** is an original, offline native-Kotlin tower-defense game for Android. Forge the only enemy route from the entrance gate to the protected core, build a mixed defense, and survive an endless procedural assault.

The game uses its own identity and mechanics. It does not include Minecraft names, artwork, sounds, characters, maps, UI, or code.

## Build status and installation

The latest verified Forgeworks build is `artifacts/Blockhold-Defense-v1.2-unsigned.apk`. It is intentionally **unsigned** while the permanent v1.0 release key is unavailable; Android cannot install it until it is signed. Do not sign an update with a replacement key, because existing `ai.techtroy.blockhold` installations would reject it. The last directly installable production artifact retained in the repository is the signed v1.0 APK.

| Item | Value |
| --- | --- |
| Public name | Blockhold Defense |
| Modes | Endless Pathforge, daily challenge, custom seed |
| Application ID | `ai.techtroy.blockhold` |
| Version | `1.2.0` (`12`) |
| Minimum Android | Android 7.0 / API 24 |
| Target SDK | API 35 |
| Orientation | Landscape |
| Permissions | None requested |
| Network requirement | None; fully offline |

See `artifacts/README.md` for artifact hashes and signing status, `docs/VERIFICATION.md` for static verification, and `docs/SIGNING.md` for permanent-key handling.

## How to play

1. Drag from the gate on the left through adjacent blocks until the route reaches the glowing core on the right.
2. Backtrack over the previous block to undo a segment. The route locks when it reaches the core.
3. Switch among **Towers**, **Traps**, **Utilities**, and **Cache** in the bottom build bar.
4. Put towers and Utilities on clean free terrain; place traps directly on empty route blocks.
5. Tap any structure between waves to upgrade it, Overcharge/evolve eligible defenses, open Forgeworks, imbue it, store placed traps, or recycle it.
6. Start the next wave. Combat, targeting, status effects, and projectiles are automatic.
7. Every five cleared waves, choose one of three persistent Forge Perks.
8. Bosses every ten waves spread corruption and award Forge Charges plus Evolution Cores.
9. Between waves, cleanse mutations, preview and confirm a reforged route, or evolve a sufficiently Overcharged tower.
10. Continue until the core is destroyed. A versioned checkpoint is saved between waves and can be resumed from the title screen.

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

## Forgeworks Update

### Forge Cache

Only traps already placed on the battlefield can be stored. **Store** pays a Block recovery fee and preserves the trap's type, level, Overcharge rank, and imbuement; placing that exact cached trap again is free. **Recycle** instead destroys the trap for Blocks and Salvage Parts. Reforge-displaced traps also require open Cache slots and recovery Blocks. The base Cache holds four traps; a Cache Depot expands capacity and lowers recovery fees.

### Utilities

Utilities never attack, occupy clean free terrain, have three levels, and are limited to four active structures. Each kind is unique except that two Block Generators are allowed:

- **Block Generator:** produces Blocks after waves, with a route-adjacency bonus
- **Cache Depot:** expands Cache capacity and reduces recovery fees
- **Forge Workshop:** unlocks Forgeworks recipes, Supplies, repair, and imbuement
- **Purifier Totem:** lowers nearby cleanse costs and can automatically purify mutations
- **Surveyor Station:** reveals increasingly detailed information about the next wave
- **Reforge Anchor:** lowers route-editing costs in its operating radius
- **Salvage Yard:** improves recycling returns and Salvage Parts recovery

### Crafting, Supplies, and Imbuement

Elite enemies, bosses, recycling, cleansing, and Utilities supply **Salvage Parts** and **Growth Essence**. A Forge Workshop converts those materials and Blocks into eight bounded Supply stacks: Core Patch, Recovery Wrap, Purifier Vial, Reforge Coupler, Utility Gearset, Survey Lens, Trap Refit Kit, and Blank Sigil. Supplies are used only between waves; context-sensitive Supplies activate automatically when their matching action is taken.

A level-three Workshop can consume a Blank Sigil, Growth Essence, and Blocks to give one eligible level-three structure a persistent run imbuement: **Might**, **Tempo**, **Reach**, **Clarity**, **Echoes**, or **Conservation**. A structure has one slot, and a replacement destroys its old effect. Cached traps retain their imbuement.

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

## Living Path Update

### Forge Perks

Every fifth cleared wave presents a deterministic three-card draft. The 25 persistent perks cover towers, traps, economy, core survival, and route forging; repeat picks stack where the effect supports stacking. Draft choices and selected stacks are included in the checkpoint.

### Path Reforging and corruption

Overgrowth bosses award limited **Forge Charges**. Between waves, the Reforge control opens a full route preview: redraw from gate to core, backtrack to undo, then confirm the displayed Forge, recovery, and Cache-capacity costs or cancel to restore the original route. Towers and Utilities are impassable. Traps displaced by the confirmed route enter the exact-item Forge Cache only when the recovery fee and capacity requirements are met.

Boss cycles add up to six readable mutation types, with intensity rising by tier:

- **Spore Path:** heals crossing enemies
- **Carapace Growth:** grants temporary armor
- **Hex Bloom:** periodically disables a nearby tower
- **Blink Root:** jumps each crossing enemy forward once
- **Thorn Soil:** raises construction cost on the affected terrain
- **Brood Nest:** inserts Splitlings into later waves

Mutations can be avoided through rerouting or tapped and cleansed with Forge Charges. Corruption is capped so endless sessions retain bounded state.

### Defense synergies and evolutions

Ten named proximity combinations have concrete effects and on-board link indicators: Resonant Volley, Thermal Shock, Thunderburst, Shatterfield, Molten Spikes, Verdant Volt, Wildfire Roots, Storm Network, Seismic Reset, and Beacon Relay.

Bosses also award **Evolution Cores**. A level-three tower with two Overcharge ranks can choose one of two mutually exclusive forms. The ten forms are Chain Conductor, Rail Spire, Blizzard Lens, Shatter Crystal, Siege Mortar, Gravity Cannon, Inferno Engine, Cinder Reactor, Storm Choir, and Harmony Nexus.

### Offline seeded challenges

The title screen offers a date-derived daily seed and an editable six-digit custom seed. The same numeric seed reproduces wave composition, perk cards, elites, and corruption. Challenge records are separate from Endless and separated again into Daily and Custom records. Seeds deterministically select one modifier: traps only, towers only, no recycling, double corruption, armored horde, rush hour, short route, or fragile core. No network access or permission is used.

## Endless director

The bounded-memory wave director generates swarm, rush, armored, regeneration, sabotage, siege, split, mixed, elite, and boss encounters. Enemy health, speed, rewards, elite density, and boss mutation tiers continue scaling without storing an infinite authored wave list.

A run records:

- Forged route
- Towers, levels, and Overcharge ranks
- Traps plus exact cached-trap records preserving type, level, Overcharge, and imbuement
- Utilities, Supplies, materials, Survey Lens duration, and structure imbuements
- Perk stacks, tower evolution branches, corruption, Forge Charges, and Evolution Cores
- Mode, numeric seed, modifier, Blocks, core health, score, and completed wave
- Separate Endless, Daily, and Custom score/high-wave records

The bounded version-three save reader migrates v1.0/v1.1 checkpoints, including legacy aggregate trap inventory, while supplying safe defaults for Forgeworks fields. Checkpoints are written only between waves, including pending perk drafts. Leaving during a live wave returns **Continue** to the last safe build checkpoint.

## Visual and audio assets

The game uses a coherent top-down sprite layer instead of color-only entities. Forgeworks adds 40 Utility, corruption, evolution, Supply, imbuement, material, and Cache images. A fully original dark industrial forge-fantasy revision is now replacing the earlier mixed layer in reviewed batches; the completed original roster covers all five base towers, all ten tower evolutions, all eight crafted supplies, all six bound sigils, all three resources, all five traps, all five regular enemies, all five elites, The Overgrowth boss, all seven Utilities, all six corruption mutations, terrain, gate, and core. Every packaged gameplay drawable is active original Blockhold Defense artwork. High-resolution sources and processing records live under `artwork/prototype/` and `artwork/style-production/`. Remaining Kenney visuals are CC0 and retain an auditable record in `docs/ASSET_LICENSES.md` until their categories are replaced.

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
tools/generate_forgeworks_art.sh                         Reproducible Forgeworks art
```

## Testing note

The repository can be compiled, signed, and statically verified in the provided environment, but no Android emulator or physical device is available there. Device-specific play feel and balance should therefore be validated through installation feedback.
