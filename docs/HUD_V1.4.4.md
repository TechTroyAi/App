# v1.4.4 HUD refit

v1.4.4 rebuilds the Canvas interface around a compact, sprite-first fantasy-machinery system. Simulation, saves, balance, and touch targets are unchanged; the release changes information hierarchy, chrome, and artwork.

![v1.4.4 HUD layout reference](V1.4.4_HUD_PREVIEW.png)

> The image above is a source-generated layout reference, not an emulator screenshot. Run `tools/generate_hud_preview_v144.sh` to rebuild it from the packaged assets.

## Interface changes

- **Title:** a generated 16:9 fortress-forge scene replaces the procedural grid; one generated logo sprite replaces live `BLOCKHOLD DEFENSE` typography. New Run, Continue, and Challenges now use three full generated button plates in a centered vertical stack.
- **Records:** generated Best, Wave, and Daily emblems replace the former tiny record sentence and move into a dedicated lower rail with larger values.
- **Challenge menu:** the new scene continues behind the challenge view, the Daily choice uses its generated emblem, and compact icon cards avoid repeated seed instructions and modifier descriptions.
- **Top dock:** Blocks, Core, Wave, and secondary resources are icon/value chips. Reforge, wave, sound, and pause are now sprite controls. The old brand/phase sentence and TXT toggle are gone.
- **Bottom dock:** category controls are icon tabs; defense cards retain only identity and cost. Selection panels focus on rank, damage/range, cost, and actions instead of repeating full descriptions.
- **Forgeworks:** resource chips, art-led tabs/cards, and icon pagination replace text-heavy navigation.
- **Drafts and overlays:** perk categories, evolution choices, pause, restart, and menu actions now carry matching sprites.
- **Feedback:** only short timed notices render over the board. Persistent tutorial/status paragraphs were removed; the next-wave countdown remains on the Wave button.
- **Space:** the top dock drops from 14.5% to 12.2% of view height and the bottom dock from 23.5% to 21.5%, increasing the fixed board viewport.

## AI sprite batch 01

Exactly ten generated concepts were used in this turn, per the production limit:

| Runtime drawable | Purpose | Accent |
| --- | --- | --- |
| `hud_play.png` | new run, wave, resume, retry | lime rune |
| `hud_continue.png` | continue and next page | cyan rune |
| `hud_challenge.png` | seeded challenge and wave identity | cyan crystal |
| `hud_sound_on.png` | audio enabled | lime sound arcs |
| `hud_sound_off.png` | audio disabled | ember slash |
| `hud_pause.png` | pause | amber rune |
| `hud_reforge.png` | path reforge and Forge Charge | violet rune |
| `hud_confirm.png` | selected/confirm/upgrade | lime rune |
| `hud_back.png` | back/cancel/previous | pale rune |
| `hud_menu.png` | title mark, Forgeworks, main menu | amber rune |

Batch 01's high-resolution generation outputs are retained in `artwork/ui-production/v1.4.4/source/`. The reviewed runtime controls are 128×128 RGBA PNGs with transparent padding in `app/src/main/res/drawable-nodpi/`.

## AI menu-art batch 02

This follow-up uses **eight** generated concepts, remaining within the ten-sprite turn limit:

| Runtime drawable | Purpose | Runtime size |
| --- | --- | --- |
| `title_background.png` | full-bleed fortress-forge title/challenge scene | 1280×720 |
| `title_logo.png` | unified title mark; replaces live title text | 640×288 |
| `title_button_new_run.png` | full New Run action plate | 640×208 |
| `title_button_continue.png` | full Continue action plate | 640×208 |
| `title_button_challenges.png` | full Challenges action plate | 640×208 |
| `menu_badge_best.png` | best-score/result emblem | 128×128 |
| `menu_badge_wave.png` | wave record and HUD emblem | 128×128 |
| `menu_badge_daily.png` | daily record/challenge emblem | 128×128 |

The three button plates are aligned vertically and share one centerline. Best score, best endless wave, and best daily wave now occupy a separate bottom record rail represented by their emblems rather than repeated labels. Result cards also use sprite glyphs for Score, Wave, and Best.

## Reproduction

```bash
# Requires ImageMagick 6+
./tools/process_hud_sprites_v144.sh
./tools/generate_hud_preview_v144.sh
```

The processor removes generated neutral backdrops with edge-connected flood fill, normalizes transparent sprites to fixed canvases, scales the title scene to 1280×720, strips volatile metadata for deterministic output, and publishes the Android copies.

## Verification checklist

- Android version: `1.4.4` (`18`)
- Generated concepts: **10 in Batch 01 + 8 in Batch 02 = 18 total**
- Batch 02 remains within the ten-sprite per-turn limit
- New packaged Batch 02 assets: **8**
- All seventeen transparent v1.4.4 sprites have transparent corner pixels; the opaque scene and all fixed-size assets match their documented dimensions
- `SpriteCatalog`: all eighteen v1.4.4 runtime names loaded eagerly
- Preview: 1600×900 PNG regenerated from packaged art and updated for Batch 02
- Static Kotlin pitfall lint: zero errors
- Gradle compile: delegated to the repository's `Build APK` workflow; local Gradle 8.9 acquisition is blocked by the runner's TLS connection
