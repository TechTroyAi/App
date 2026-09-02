# Fantasy Machinery gameplay-interface production

Created: 2026-09-02

This directory records the second interface pass for Blockhold Defense: the playable HUD, build shelf, contextual status panel, selection controls, Forgeworks, and game overlays now share the project's Fantasy Machinery treatment. It is a source/review directory; Android loads the corresponding exports from `app/src/main/res/drawable-nodpi/`, not from this directory.

## Runtime family

| Role | Android exports | Native dimensions | Count |
| --- | --- | --- | ---: |
| Continuous HUD rails | `hud_top_rail.png`, `hud_bottom_rail.png` | 1024×136; 1024×256 | 2 |
| Reusable surfaces | `ui_panel*`, `ui_modal`, `ui_card*`, `ui_tab*`, `ui_build_slot*`, `ui_banner`, `ui_stat_frame` | 164×112 to 720×480 | 12 |
| Action controls | `ui_button_primary*`, `ui_button_secondary*`, `ui_button_accent*`, `ui_button_warning*`, `ui_button_disabled` | 400×120 | 9 |
| Context/category/control icons | `ui_icon_*.png` | 128×128 | 26 |

The full runtime family contains **49 authored PNG exports**. It gives the live game dark forged steel, brass edging, inset hardware, gear mounts, rune-green ready states, cyan utility states, purple special actions, and ember warning actions without replacing state-dependent values with stale baked text.

## Source, regeneration, and review

- `tools/generate_game_ui_sprites.py` deterministically produces all 49 exports with Python's standard library. It deliberately reuses the raster primitives and palette in `tools/generate_menu_ui_sprites.py`, so the title screen and playable screen have one visual language.

  ```bash
  python3 tools/generate_game_ui_sprites.py
  ```

  The script writes directly to `app/src/main/res/drawable-nodpi/`; review the resulting diff before accepting regenerated pixels.
- `hud-ui-sprite-review.png` is an enlarged contact sheet of the exact generated sprite family for visual QA. It is not packaged as an Android resource.
- Exports are rendered at a compact logical resolution and doubled with nearest-neighbour scaling, preserving a crisp pixel-art finish when `GameView` adapts them to landscape layouts.

## Integration contract

`SpriteCatalog.kt` decodes every HUD/interface export once. `GameView.kt` places the authored rails and skins behind adaptive text, live values, selection highlights, and existing hit rectangles. The top bar, bottom build shelf, contextual Next Wave banner, path forge panel, tower/trap/utility/corruption panels, pause/end overlays, challenge menu, perk/evolution picks, and Forgeworks use these skins. Existing gameplay dispatch, costs, timers, seeded challenges, input, and pause/menu behavior are unchanged.

The recurring Next Wave presentation is intentionally concise: the action button uses a launch/stack icon and a short live wave label, while the contextual banner shows `WAVE <n> • <seconds>S` during its countdown. Longer one-shot outcomes still use the same art-backed banner when useful.

All artwork in this directory and the corresponding runtime exports was created specifically for Blockhold Defense. No third-party UI, artwork, trademarked game interface, or external asset pack is used.
