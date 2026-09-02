# Fantasy Machinery title-menu production

Created: 2026-09-02

This directory records the first interface pass for Blockhold Defense: a concise, sprite-led title menu in the project's fantasy-machinery style. It is a production source/review directory; the Android runtime loads the exported PNGs from `app/src/main/res/drawable-nodpi/`, not from this directory.

## Runtime family

| Role | Android export | Native dimensions |
| --- | --- | --- |
| Forge-room cover background | `menu_title_background.png` | 1376×768 |
| Framed menu plate | `menu_panel.png` | 760×632 |
| Crest | `menu_title_crest.png` | 560×252 |
| Normal/held/disabled action skins | `menu_button_*.png` | 640×152 |
| Play, resume, challenge, sound on/off icons | `menu_icon_*.png` | 128×128 |

The runtime keeps action text deliberately short: **NEW RUN**, **CONTINUE**, and **CHALLENGES**. The normal action skins, held skins, and disabled Continue skin are separate authored exports rather than canvas-drawn rounded rectangles.

## Source, regeneration, and review

- `title_forge_background_source.png` is the project-owned canonical source for the title forge scene. `menu_title_background.png` is a pixel-identical 1376×768 runtime PNG export (with Android-ready compression).
- `tools/generate_menu_ui_sprites.py` deterministically generates the transparent panel, crest, button, and icon exports with Python's standard library. Run it from the repository root with:

  ```bash
  python3 tools/generate_menu_ui_sprites.py
  ```

  The script writes directly to `app/src/main/res/drawable-nodpi/`; review its diff before accepting regenerated pixels.
- `menu-ui-sprite-review.png` is an enlarged, labelled contact sheet for visual asset review only.
- `title-menu-preview.png` is a 1920×1080 composition assembled from the exact runtime PNG exports. It is a visual QA reference, not an Android resource.

## Integration contract

`SpriteCatalog.kt` owns the decoded title assets. `GameView.kt` cover-crops the background, draws the central panel/crest/action stack, and retains existing behavior for New Run, saved-run Continue, Challenges, and sound toggling. Button bounds preserve the authored 640:152 aspect ratio and scale together within the panel on constrained landscape screens.

All artwork in this directory and the corresponding runtime exports was created specifically for Blockhold Defense. No third-party UI, artwork, trademarked game interface, or external asset pack is used.
