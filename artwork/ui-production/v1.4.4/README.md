# v1.4.4 HUD controls — batch 01

Ten image-model concepts were generated specifically for the v1.4.4 interface refit. The direction is **fantasy machinery**: tarnished brass, dark gunmetal, compact gear construction, engraved runes, and one clear semantic silhouette per control.

- `source/`: original 1254×1254 generated PNGs. The generator returned a baked neutral checkerboard rather than true alpha.
- `processed/`: reviewed 128×128 transparent controls.
- Android copies: `app/src/main/res/drawable-nodpi/hud_*.png`.

Each prompt required one centered front-facing UI object, generous padding, no scene, no writing, no watermark, and readability at 64 pixels. Run the processor from the repository root:

```bash
./tools/process_hud_sprites_v144.sh
```

The processor edge-floods the neutral checkerboard at 12% fuzz, trims, scales to a 116-pixel maximum extent, then centers the art on a 128×128 transparent canvas. Runtime files and their hashes are recorded in `docs/ASSET_HASHES_SHA256.txt`.
