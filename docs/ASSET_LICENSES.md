# Asset sources and licenses

Blockhold Defense is designed to work fully offline. Every third-party visual included in the app is from a verified Creative Commons Zero source. No Minecraft artwork, names, sounds, characters, maps, UI, or code are included.

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

Included and adapted material covers terrain, route tiles, tower bodies, turrets, enemies, traps, gate/core details, projectiles, effect sprites, and the neutral bases used in the seven Forgeworks Utility compositions. App PNGs may be cropped, recolored, outlined, layered, or composited with original machinery motifs; those derivatives remain freely reusable under CC0.

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

Included material supplies the Mosser, Runner, Brute, Shellback, Ironhide Champion, Blink Stalker, Rootcaller, and Hex Weaver silhouettes. Images are recolored and composited into the game's original rings, health bars, status effects, and movement treatment.

## Original application artwork

The Blockhold route-and-core icon family, store icon, interface composition, targeting indicators, particles, health bars, status effects, and procedural animation are original to this repository. The store icon source is `artwork/blockhold-store-icon.svg`; its 512-pixel export is `artwork/blockhold-store-icon-512.png`.

The v1.2 Forgeworks pass adds 33 fully original vector-style PNG compositions: six corruption mutations, ten tower-evolution badges, eight Supply cards, six imbuement sigils, two material icons, and one Forge Cache icon. Together with the seven CC0-derived Utility compositions, this is the 40-image Forgeworks family. The generation source is `tools/generate_forgeworks_art.sh`; it records every primitive, color, and output filename and introduces no new third-party pack.

`docs/ASSET_HASHES_SHA256.txt` records every bundled PNG, launcher image, WAV, and license file by repository path and SHA-256. Its current visual-production manifest SHA-256 is `d87b4b5b07a7baf13790cc3b8bc577b6fc6fafd19d8dc7817be25c014dbc3a80`. The original upstream ZIP archives were not retained in Git; the bundled license hashes, retrieval snapshots, and per-output hashes are the permanent release audit baseline.

## Original sprite-revision production

The approved 2026-08-27 visual revision is produced specifically for Blockhold Defense with image-model concept generation followed by repository-local background isolation, cropping, scaling, layer preparation, and seamless-tile construction. No third-party sprite pack pixels are used in these new concepts. High-resolution sources, processed outputs, style constraints, and batch status are retained under `artwork/prototype/` and `artwork/style-production/`.

The integrated roles are all five base towers, six combat-tower evolutions, all five traps, all five regular enemies, all five elites, The Overgrowth boss, all seven Utilities, all six corruption mutations, grass terrain, route terrain, enemy gate, and player core. The Bolt Tower uses separate centered base and turret layers so gameplay rotation is preserved. `tools/process_sprite_prototypes.py` and `tools/process_sprite_batch_02.py` provide the reproducible processing stage; opposite terrain edges are checked pixel-for-pixel before integration.

The remaining Kenney-derived resources stay licensed under the records above until their category is replaced in later reviewed batches.

## Audio

All ten WAV effects in `app/src/main/res/raw/` are original deterministic synthesis produced by `tools/generate_sfx.py`. They contain no third-party samples and can be regenerated from source.

## CC0 reference

Creative Commons CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/

Kenney does not require attribution for these packs. Attribution is included here as a courtesy and to preserve an auditable source record.
