# Asset sources and licenses

Blockhold Defense is designed to work fully offline. The current APK's gameplay drawable roster is entirely original to this project; the Kenney records below document the verified CC0 material used by earlier visual iterations and retained in repository history. No Minecraft artwork, names, sounds, characters, maps, UI, or code are included.

## Kenney — Tower Defense (Top-Down)

- Creator: Kenney Vleugels / Kenney
- Official source: https://kenney.nl/assets/tower-defense-top-down
- Upstream pack: **Tower Defense (Top-Down)**, 300 files
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)
- License text: `app/src/main/assets/licenses/KENNEY_TOWER_DEFENSE_CC0.txt`
- License-file SHA-256: `b01f57bbeebed0f3766e033cc8ac510b6203eee10073b6a1d46a88668a7d8166`
- Retrieval mirror used by this build environment: https://github.com/jhonnold/tower-defense-v2/tree/master/img
- Snapshot date: 2026-08-27; upstream publishes no semantic pack version
- Redistribution audit: commercial use, modification, APK embedding, attribution-free distribution, and redistribution are permitted by CC0 1.0

Earlier releases adapted this pack for terrain, route tiles, tower bodies, turrets, traps, gate/core details, and Forgeworks placeholders. Batch 14 removed the final unreferenced Kenney drawables from Android resources; none of these pack PNGs remain in the current APK.

## Kenney — Top-down Shooter

- Creator: Kenney Vleugels / Kenney
- Official source: https://kenney.nl/assets/top-down-shooter
- Upstream pack: **Top-down Shooter**, 580 files
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)
- License text: `app/src/main/assets/licenses/KENNEY_TOPDOWN_SHOOTER_CC0.txt`
- License-file SHA-256: `0ccfce222d3747caa79e5450ea9daddb66ae2343adc5f4206c833cc9d2d40110`
- Retrieval mirror used by this build environment: https://github.com/Tiddybub/2d-assets/tree/main/modern-urban/top-down-shooter
- Snapshot date: 2026-08-27; upstream publishes no semantic pack version
- Redistribution audit: commercial use, modification, APK embedding, attribution-free distribution, and redistribution are permitted by CC0 1.0

Earlier releases adapted this pack for several enemy silhouettes. Those runtime sprites were replaced during the reviewed original-style production batches; none of these pack PNGs remain in the current APK.

## Original application artwork

The Blockhold route-and-core icon family, store icon, interface composition, targeting indicators, particles, health bars, status effects, and procedural animation are original to this repository. The store icon source is `artwork/blockhold-store-icon.svg`; its 512-pixel export is `artwork/blockhold-store-icon-512.png`.

The initial v1.2 Forgeworks pass added 33 repository-generated vector-style PNG compositions through `tools/generate_forgeworks_art.sh`. Those placeholders and the seven former CC0-derived Utility compositions have since been replaced by the reviewed original sprite-production batches retained under `artwork/style-production/`.

`docs/ASSET_HASHES_SHA256.txt` records every bundled PNG, launcher image, WAV, and license file by repository path and SHA-256. Its current visual-production manifest SHA-256 is `e5ee3d9715c369df4c82f4bfb4ffb85cc6e56de64f88b0737f949998244b039c`. The original upstream ZIP archives were not retained in Git; the bundled license hashes, retrieval snapshots, and per-output hashes are the permanent release audit baseline.

## Original sprite-revision production

The approved 2026-08-27 visual revision is produced specifically for Blockhold Defense with image-model concept generation followed by repository-local background isolation, cropping, scaling, layer preparation, and seamless-tile construction. No third-party sprite pack pixels are used in these new concepts. High-resolution sources, processed outputs, style constraints, and batch status are retained under `artwork/prototype/` and `artwork/style-production/`.

The completed roster covers all five base towers, all ten tower evolutions, all eight crafted supplies, all six bound sigils, all three resource icons, all five traps, all five regular enemies, all five elites, The Overgrowth boss, all seven Utilities, all six corruption mutations, grass terrain, route terrain, enemy gate, and player core. Layered towers preserve gameplay rotation and animation. `tools/process_sprite_prototypes.py` and the numbered batch processors provide the reproducible processing stage; opposite terrain edges are checked pixel-for-pixel before integration.

All 70 PNGs under `app/src/main/res/drawable-nodpi/` are active original Blockhold Defense assets. Every `SpriteCatalog` runtime load maps to one of those files, and no obsolete third-party drawable is packaged.

The limited-animation extension is retained under `artwork/animation-production/`. It uses the approved project-specific high-resolution concepts and neutral gameplay sprites as identity references, then packages reviewed poses into anchored horizontal strips. Animation Batches 01–04 add original movement poses for all five regular enemies, all five elites, and The Overgrowth, ambient states for both landmarks, and trigger states for Spike Bed and Root Snare without introducing third-party source pixels.

## Audio

All ten WAV effects in `app/src/main/res/raw/` are original deterministic synthesis produced by `tools/generate_sfx.py`. They contain no third-party samples and can be regenerated from source.

## CC0 reference

Creative Commons CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/

Kenney does not require attribution for these packs. Attribution is included here as a courtesy and to preserve an auditable source record.
