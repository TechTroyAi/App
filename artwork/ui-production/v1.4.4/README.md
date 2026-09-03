# v1.4.4 HUD and menu art — batches 01–03

Twenty-one approved runtime assets were generated specifically for the v1.4.4 interface refit across three bounded sessions. The shared direction is **fantasy machinery**: tarnished brass, dark gunmetal, compact gear construction, engraved runes, and one clear semantic silhouette per control. No session exceeded the ten-image production limit.

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
- The title actions are displayed as one vertical stack; the emblems remain useful in challenge, HUD, and result contexts.

## Batch 03 — three full record plates

- Best, Daily, and Wave each receive a complete generated horizontal plate rather than a painted rectangle around a standalone glyph.
- The approved plates have empty metal value fields so runtime numbers remain crisp and readable.
- The title stacks the three plates vertically in the top-left and moves the version tag to the bottom-right.
- Three initial generations plus three clean-panel refinements produced the three retained assets: six image outputs this session, below the limit of ten.

## Retained asset manifest

| Batch | High-resolution source | Processed master / Android drawable |
| --- | --- | --- |
| 01 | `source/ui_play_source.png` | `processed/ui_play.png` / `hud_play.png` |
| 01 | `source/ui_continue_source.png` | `processed/ui_continue.png` / `hud_continue.png` |
| 01 | `source/ui_challenge_source.png` | `processed/ui_challenge.png` / `hud_challenge.png` |
| 01 | `source/ui_sound_on_source.png` | `processed/ui_sound_on.png` / `hud_sound_on.png` |
| 01 | `source/ui_sound_off_source.png` | `processed/ui_sound_off.png` / `hud_sound_off.png` |
| 01 | `source/ui_pause_source.png` | `processed/ui_pause.png` / `hud_pause.png` |
| 01 | `source/ui_reforge_source.png` | `processed/ui_reforge.png` / `hud_reforge.png` |
| 01 | `source/ui_confirm_source.png` | `processed/ui_confirm.png` / `hud_confirm.png` |
| 01 | `source/ui_back_source.png` | `processed/ui_back.png` / `hud_back.png` |
| 01 | `source/ui_menu_source.png` | `processed/ui_menu.png` / `hud_menu.png` |
| 02 | `source/title_background_source.png` | `processed/title_background.png` / `title_background.png` |
| 02 | `source/title_logo_source.png` | `processed/title_logo.png` / `title_logo.png` |
| 02 | `source/title_button_new_run_source.png` | `processed/title_button_new_run.png` / `title_button_new_run.png` |
| 02 | `source/title_button_continue_source.png` | `processed/title_button_continue.png` / `title_button_continue.png` |
| 02 | `source/title_button_challenges_source.png` | `processed/title_button_challenges.png` / `title_button_challenges.png` |
| 02 | `source/menu_badge_best_source.png` | `processed/menu_badge_best.png` / `menu_badge_best.png` |
| 02 | `source/menu_badge_wave_source.png` | `processed/menu_badge_wave.png` / `menu_badge_wave.png` |
| 02 | `source/menu_badge_daily_source.png` | `processed/menu_badge_daily.png` / `menu_badge_daily.png` |
| 03 | `source/menu_record_best_plate_source.png` | `processed/menu_record_best_plate.png` / `menu_record_best_plate.png` |
| 03 | `source/menu_record_daily_plate_source.png` | `processed/menu_record_daily_plate.png` / `menu_record_daily_plate.png` |
| 03 | `source/menu_record_wave_plate_source.png` | `processed/menu_record_wave_plate.png` / `menu_record_wave_plate.png` |

Android drawable names in the last column are located in `app/src/main/res/drawable-nodpi/`.

## Directory roles

- `source/`: original image-model outputs at their generated resolutions.
- `processed/`: reviewed Android-scale assets with deterministic metadata stripping.
- Android copies: matching files under `app/src/main/res/drawable-nodpi/`.

Run the processor from the repository root:

```bash
./tools/process_hud_sprites_v144.sh
```

The processor edge-floods each neutral backdrop at 12% fuzz, trims and normalizes transparent sprites, scales the opaque scene to 1280×720, and publishes byte-identical processed/runtime copies. Runtime files and their hashes are recorded in `docs/ASSET_HASHES_SHA256.txt`.
