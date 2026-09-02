# Blockhold Defense

**Blockhold Defense: Endless Pathforge** is an original, offline native-Kotlin tower-defense game for Android. Forge the only enemy route from the entrance gate to the protected core, build a mixed defense, and survive an endless procedural assault.

The game uses its own identity and mechanics. It does not include Minecraft names, artwork, sounds, characters, maps, UI, or code.

## Build status and installation

**The current source targets v1.4.3 / `versionCode 17`.** It includes the completed gameplay art overhaul plus the sprite-led Fantasy Machinery title and playable-interface passes described below. The retained signed v1.4.3 artifact was built before both interface passes, so it must **not** be described as containing the current UI refresh.

`artifacts/Blockhold-Defense-v1.4.3-installable.apk` is the latest built and retained installable package (SHA-256 `478648d470c627c929844804163bde28890c7bc12b329368193d324bf9dd0fee`). It passed the recorded APK/Dex verification, but it is a previous v1.4.3 source snapshot. This checkout has no release-signing material, so a new release-signed artifact cannot be made here; CI can build and verify a current debug APK. See `artifacts/README.md` for the retained artifact's signing history and `docs/SIGNING.md` before producing a new distributable package.

| Item | Value |
| --- | --- |
| Public name | Blockhold Defense |
| Modes | Endless Pathforge, daily challenge, custom seed |
| Application ID | `ai.techtroy.blockhold` |
| Version | `1.4.3` (`17`) — current source; retained signed APK predates the interface refresh |
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

Every tower definition is also shown when its build card or placed tower is selected:

- **Bolt Tower — 70 Blocks:** fast single-target bolts with balanced damage and range
- **Frost Prism — 90 Blocks:** slows enemies on impact and punishes frozen targets
- **Core Cannon — 125 Blocks:** slow heavy shells that explode for area damage
- **Ember Forge — 145 Blocks:** burning shells splash nearby enemies and ignite them
- **Resonance Beacon — 170 Blocks:** resonance shots chain across enemies near the target
- **Thorn Spire — 95 Blocks:** marks enemies so follow-up damage cuts deeper
- **Shard Lance — 155 Blocks:** piercing shards strike several enemies along the route
- **Mire Spout — 135 Blocks:** spreads a slowing mire around every impact
- **Gale Spire — 140 Blocks:** wind shots damage foes and shove them backward
- **Sunforge — 160 Blocks:** hot projectiles deal damage and leave enemies burning
- **Lodestone — 150 Blocks:** magnetic shots pull nearby enemies backward
- **Howl Spire — 145 Blocks:** reveals hidden enemies and briefly slows the pack
- **Vitriol Spout — 155 Blocks:** acid shots shred enemy armor for your whole defense
- **Gravebolt — 165 Blocks:** brands enemies so their deaths can burst with dark damage
- **Aegis Loom — 175 Blocks:** damages enemies while cleansing Hex from nearby towers

### Traps

- **Spike Bed — 45 Blocks:** reliable physical damage whenever an enemy crosses this tile
- **Root Snare — 65 Blocks:** damages, roots, and slows an enemy on the path
- **Ember Rune — 85 Blocks:** ignites the enemy for damage over time after triggering
- **Arc Plate — 110 Blocks:** stuns the trigger and chains lightning into nearby enemies
- **Crusher Block — 145 Blocks:** a heavy smash that deals high damage and a long stun

Towers and traps have three normal levels. After level three, repeatable **Overcharge** ranks provide a continuing long-run currency sink with escalating costs.

## Forgeworks Update

### Forge Cache

Only traps already placed on the battlefield can be stored. **Store** pays a Block recovery fee and preserves the trap's type, level, Overcharge rank, and imbuement; placing that exact cached trap again is free. **Recycle** instead destroys the trap for Blocks and Salvage Parts. Reforge-displaced traps also require open Cache slots and recovery Blocks. The base Cache holds four traps; a Cache Depot expands capacity and lowers recovery fees.

### Utilities

Utilities never attack, occupy clean free terrain, and have three upgrade levels. v1.4.2 has no global active-utility capacity limit: each utility kind is unique, while up to **five Block Generators** may be placed at once. Selecting a utility shows its definition and upgrade action:

- **Block Generator:** produces Blocks after every cleared wave; upgrades increase output
- **Cache Depot:** expands trap storage and lowers recovery fees
- **Forge Workshop:** repairs, fabricates supplies, and binds sigils
- **Purifier Totem:** reduces nearby corruption cleansing costs
- **Surveyor Station:** reveals approaching waves and elite threats
- **Reforge Anchor:** reduces the cost of nearby route changes
- **Salvage Yard:** improves recycling returns and recovered Parts
- **Ward Beacon:** nearby towers shrug off Hex faster and gain brief immunity pulses
- **Battle Banner:** towers near the banner deal extra damage during waves
- **Essence Still:** converts wave pressure into Growth Essence over time
- **Trap Lattice:** path traps near the lattice deal more damage
- **Aegis Pylon:** nearby towers take shorter Hex and gain a light shield pulse
- **Growth Nursery:** cleared waves yield bonus Growth Essence
- **Path Warden:** enemies on nearby path tiles move slightly slower
- **Spark Relay:** nearby towers fire a touch faster
- **Bounty Board:** kills near the board grant a small Blocks bonus
- **Cinder Kiln:** nearby Ember traps and Ember towers burn hotter

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
- **Sapper:** very fast, fragile saboteur; destroys its first two triggered traps, then ignores the rest

### Elite threats

Elites recur approximately every five waves. If an elite or boss is held in one place for **1.75 seconds**, it smashes the trap directly beneath it; the orange ring shows that anti-trap timer filling.

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

The title screen offers a date-derived daily seed and an editable custom seed. Tap the custom seed field to type up to 12 digits; the same numeric seed reproduces wave composition, perk cards, elites, and corruption. Challenge records are separate from Endless and separated again into Daily and Custom records. Seeds deterministically select one modifier: traps only, towers only, no recycling, double corruption, armored horde, rush hour, short route, or fragile core. No network access or permission is used.

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

## v1.4.3 art overhaul

v1.4.3 replaces **every** gameplay drawable with a single coherent **fantasy × machinery** sprite set — brass gearwork and rune-lit metal for everything the player builds, purely organic flesh, fungus, and bramble for everything that attacks it. All art is strict top-down to match the game camera.

### Sprite-led title menu

The title screen begins the same UI treatment: a forge-room cover background, framed machinery plate, crest, three large skinned actions, and a compact sound control. The screen now limits visible copy to the game identity and **NEW RUN**, **CONTINUE**, and **CHALLENGES**; redundant taglines, feature pills, score/footer copy, and "no saved run" copy are removed. Normal, pressed, and disabled states use authored sprites, while the existing New Run, saved-run Continue, Challenges, and sound-toggle behavior remains intact.

The title assets are loaded by `SpriteCatalog.kt` from `drawable-nodpi` and scale as an aspect-preserving group inside the central plate on landscape devices. Canonical source/review material and the deterministic UI generator live in `artwork/menu-production/`; `title-menu-preview.png` is a 1920×1080 review composition made from the exact packaged PNGs.

### Sprite-led playable interface

The playable screen now uses the same forged visual language rather than flat code-drawn chrome: continuous top/bottom rails, framed live-resource slots, compact category tabs, build-slot skins, contextual banner, selection panels, craft/supply/sigil cards, modal shells, and action controls are all authored sprites. The reusable button family supplies normal, held, and disabled skins; category and action icons keep common controls readable without adding explanatory copy.

The revised Next Wave state is concise and visual. The top-right control keeps its existing behavior—start a build-phase wave, stack an active wave, or confirm a reforge—but now pairs a launch/stack/forge icon with a short live label. Its recurring contextual panel reads `WAVE <n> • <seconds>S` during the automatic countdown instead of repeating a tutorial sentence. One-shot gameplay outcomes still use the same art-backed banner when detail matters.

`GameView.kt` retains Canvas text only for live values such as costs, waves, inventory, and stats; `SpriteCatalog.kt` loads 49 dedicated HUD/interface PNGs from `drawable-nodpi`. Existing hit rectangles, gameplay dispatch, seeded challenge input, Forgeworks logic, and pause/end behavior remain unchanged. The deterministic producer and review contact sheet live in `artwork/hud-production/` and `tools/generate_game_ui_sprites.py`.

| Family | Count | Size | Notes |
| --- | --- | --- | --- |
| Tower bases | 15 | 64×64 | Octagonal gear-ring foundation pads, per-element rune accent |
| Turret heads | 15 | 192×64 | 3-frame firing recoil, aimed along +X |
| Projectiles | 15 | 192×64 | 3-frame travel |
| Impacts | 15 | 192×64 | 3-frame radial burst |
| Evolution emblems | 30 | 64×64 | Real crest art per evolution, parent accent colour |
| Evolution ring | 1 | 192×64 | Hollow-centre overlay so the tower shows through |
| Traps | 5 | 192×64 | True keyframe mechanical motion |
| Utility structures | 17 | 192×64 | Idle loop, no recoil offset |
| Corruption overlays | 6 | 64×64 | Full-bleed ground tiles |
| Hex status | 1 | 192×64 | Animated shackle sprite |
| Crafted items | 18 | 64×64 | |
| Imbuement sigils | 16 | 64×64 | Brass-rimmed rune tokens |
| Resource icons | 3 | 64×64 | |
| **Enemies** | **30** | 192×64 | **True 3-keyframe animation** |
| Terrain tiles | 2 | 128×128 | Seamless full-bleed grass and road |
| Landmarks | 2 | 192×64 | Animated spawn gate and core reactor |
| **HUD and in-game interface** | **49** | **128×128 to 1024×256** | **Rails, surfaces, controls, slots, tabs, banner, and icons** |

### True enemy animation

All 30 enemies (19 normal, 8 elite, 3 boss) use hand-rendered keyframes rather than a procedural squash-and-stretch cycle. Each strip is three distinct poses:

| Frame | Pose |
| --- | --- |
| 0 | Neutral — limbs tucked, glow calm |
| 1 | Attack — limbs thrown forward, maw or spines open, glow blazing |
| 2 | Death — collapsed or unravelling, cracked or wilted, glow extinguished, colours desaturated |

`GameView` plays `0,1,2,1` while walking and holds frame 2 on death, so the death pose doubles as the walk-cycle extreme.

### Map and chrome

Terrain is two seamless 128×128 tiles: overgrown turf with half-buried brass fragments, and a deliberately dark, low-contrast road so bright creatures and towers stay readable on top of it. The spawn gate animates closed → opening → fully open with a blazing portal throat; the core reactor pulses calm → bright and has a cracked, tarnished damaged frame. `GameView.drawBoardBezel()` frames the play grid in a tarnished brass rail with corner rivets so the board reads as a machined table.

### Regenerating the sprites

High-resolution sources live in `artwork/style-production/batch-33-v143/` (per-frame animation keys under `anim/`). The processing pipeline is `tools/process_sprite_batch_33_v143.py`:

```python
import sys; sys.path.insert(0, "tools")
from process_sprite_batch_33_v143 import clean, clean_matched, keyframe_strip, tile, publish, SOURCE, STAGE

clean(SOURCE / "x_source.png", STAGE / "x.png")          # normal subjects
clean_matched(SOURCE / "x_source.png", STAGE / "x.png")  # pale / bright-neutral subjects
tile(SOURCE / "t_source.png", STAGE / "t.png", size=128) # full-bleed terrain
publish(keyframe_strip(frames, STAGE / "a.png", STAGE / "anim", matched=True), "a.png")
```

Two rules matter when adding art. Pale or bright-neutral subjects (bone, salt, spore haze, pale fluff) must use `clean_matched()` — the plain flood-fill `clean()` treats their own bright pixels as backdrop and leaves a grey box or eats into the subject. Full-bleed textures must use `tile()`, never `clean()`, which would shred them. Verify any new sprite by asserting the published size and that pixel `(1, 1)` has alpha ≤ 40.

## Visual and audio assets

The game uses a coherent top-down sprite layer instead of color-only entities or flat interface chrome. Forgeworks adds Utility, corruption, evolution, Supply, imbuement, material, and Cache images; the Fantasy Machinery pass adds a title family plus 49 playable HUD/interface exports. The fully original dark industrial forge-fantasy roster covers all towers, evolutions, crafted supplies, sigils, resources, traps, enemies, Utilities, corruption mutations, terrain, landmarks, and the full player-facing interface. Limited animation production includes the complete enemy roster, ambient Enemy Gate and Player Core loops, trigger-driven sequences for all five traps, and firing motion for the Bolt, Frost, and Cannon tower top layers. Gameplay source/processing records live under `artwork/prototype/` and `artwork/style-production/`; interface generators and visual QA live under `artwork/menu-production/` and `artwork/hud-production/`. No Kenney or other third-party visuals remain in the packaged runtime assets; the historical CC0 record is retained in `docs/ASSET_LICENSES.md`.

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
app/src/main/res/drawable-nodpi/                         Original gameplay and Fantasy Machinery UI sprites
app/src/main/assets/licenses/                            Historical pack license texts
docs/ASSET_LICENSES.md                                   Source/license audit
docs/SIGNING.md                                          Permanent signing procedure
artwork/                                                 Original source art, processing records, and review boards
artwork/menu-production/                                 Title-menu source and 1920×1080 visual QA preview
artwork/hud-production/                                  Playable-interface source record and sprite review sheet
tools/generate_sfx.py                                    Reproducible original audio
tools/generate_forgeworks_art.sh                         Reproducible Forgeworks art
tools/generate_menu_ui_sprites.py                        Deterministic title-menu UI sprite exports
tools/generate_game_ui_sprites.py                        Deterministic playable HUD/interface sprite exports
```

## Testing note

The repository can be compiled, signed, and statically verified in the provided environment, but no Android emulator or physical device is available there. Device-specific play feel and balance should therefore be validated through installation feedback.
