# v1.4.4 HUD and menu art — batches 01–02

Eighteen image-model concepts were generated specifically for the v1.4.4 interface refit across two sessions, with each session kept below the ten-sprite production limit. The shared direction is **fantasy machinery**: tarnished brass, dark gunmetal, compact gear construction, engraved runes, and one clear semantic silhouette per control.

## Batch 01 — ten HUD controls

- Play, continue, challenge, sound on, sound off, pause, reforge, confirm, back, and menu.
- Original outputs use a baked neutral field rather than true alpha.
- Reviewed outputs are transparent 128×128 controls.
- Android copies use `app/src/main/res/drawable-nodpi/hud_*.png`.

## Batch 02 — eight title/menu assets

- One 16:9 fortress-forge background.
- One unified `BLOCKHOLD DEFENSE` logo sprite.
- Three full horizontal action plates: New Run, Continue, and Challenges.
- Three record emblems: Best, Wave, and Daily.
- The title actions are displayed as one vertical stack; the record emblems replace the former tiny text sentence.

## Directory roles

- `source/`: original image-model outputs at their generated resolutions.
- `processed/`: reviewed Android-scale assets with deterministic metadata stripping.
- Android copies: matching files under `app/src/main/res/drawable-nodpi/`.

Run the processor from the repository root:

```bash
./tools/process_hud_sprites_v144.sh
```

The processor edge-floods each neutral backdrop at 12% fuzz, trims and normalizes transparent sprites, scales the opaque scene to 1280×720, and publishes byte-identical processed/runtime copies. Runtime files and their hashes are recorded in `docs/ASSET_HASHES_SHA256.txt`.
