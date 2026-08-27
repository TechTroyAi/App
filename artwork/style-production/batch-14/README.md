# Batch 14 — resource icons

Integrated the final original resource-icon family:

- Salvage Parts
- Growth Essence
- Forge Cache

Their silhouettes separate common reclaimed hardware, rare living boss essence, and a sealed high-value forge reward. This batch completes the essential original-sprite overhaul.

The high-resolution `*_source.png` concepts were created specifically for Blockhold Defense. `processed/` contains transparent 64×64 outputs produced by `tools/process_sprite_batch_14.py`.

`resource-family-review.png` presents all three resources at enlarged nearest-neighbor scale and is not packaged into the APK.

After integration, the 15 obsolete unreferenced Kenney drawable files were removed. Every PNG remaining in `app/src/main/res/drawable-nodpi/` is an active original Blockhold Defense asset, and every runtime `SpriteCatalog` load maps to exactly one of those files.
