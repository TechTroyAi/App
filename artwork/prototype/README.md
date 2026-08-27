# Blockhold Defense sprite-style prototype

Status: **visual review only; not integrated into the APK**

Created: 2026-08-27

This folder contains the first six-sprite proof for a fully original Blockhold Defense visual revision. The concepts were generated specifically for this repository with an image model, then isolated, cropped, and reduced to transparent 64×64 gameplay sprites with `tools/process_sprite_prototypes.py`. They do not use the existing Kenney gameplay images as source pixels.

## Locked candidate direction

- Dark industrial forge fantasy
- Top-down three-quarter perspective
- Chunky, deliberate pixel clusters
- Strong near-black silhouette outline
- Upper-left lighting
- Charcoal iron and dark olive stone as shared neutrals
- Muted brass for machinery and rank detail
- Ember orange, acid lime, and restrained cyan as functional energy colors
- Threatening rather than cute characters
- No labels, scenery, floor tiles, protected franchise imagery, or baked backgrounds
- Readable on a transparent 64×64 canvas with roughly three pixels of breathing room

## Prototype set

| Gameplay role | High-resolution concept | Processed 64×64 sprite |
| --- | --- | --- |
| Tower | `bolt_tower_source.png` | `processed/bolt_tower.png` |
| Trap | `spike_bed_source.png` | `processed/spike_bed.png` |
| Regular enemy | `enemy_mosser_source.png` | `processed/enemy_mosser.png` |
| Elite enemy | `enemy_ironhide_source.png` | `processed/enemy_ironhide.png` |
| Utility | `utility_block_generator_source.png` | `processed/utility_block_generator.png` |
| Corruption | `corruption_spore_path_source.png` | `processed/corruption_spore_path.png` |

`../forgeworks-prototype-final-review.png` is the enlarged review board. It uses a dark background and labels for presentation only. `../forgeworks-prototype-board-preview.png` places the six sprites over the current terrain at approximately their real gameplay scale. Neither review image is an in-game resource.

## Reprocessing

The helper requires Pillow 10.4 or newer:

```bash
python3 -m pip install Pillow
python3 tools/process_sprite_prototypes.py
```

The flood-fill removes only neutral bright backdrop pixels connected to the canvas edge, preserving enclosed metal highlights before the sprite is scaled to 64×64.

## Approval gate

Do not replace in-game resources or expand the remaining roster until this prototype direction is accepted or revised. Once accepted, production should proceed in category-focused batches of six to eight sprites, reviewing silhouettes at both 64×64 and enlarged nearest-neighbor scale after every batch.
