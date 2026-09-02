# v1.4.4 HUD refit

v1.4.4 rebuilds the Canvas interface around a compact, sprite-first fantasy-machinery system. Simulation, saves, balance, and touch targets are unchanged; the release changes information hierarchy, chrome, and artwork.

![v1.4.4 HUD layout reference](V1.4.4_HUD_PREVIEW.png)

> The image above is a source-generated layout reference, not an emulator screenshot. Run `tools/generate_hud_preview_v144.sh` to rebuild it from the packaged assets.

## Interface changes

- **Title:** three equal sprite-led actions replace the old feature pills, slogan, studio label, and mixed two-row button layout.
- **Challenge menu:** daily and custom choices use compact icon cards; repeated seed instructions and modifier descriptions were removed from the live screen.
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

High-resolution 1254×1254 generation outputs are retained in `artwork/ui-production/v1.4.4/source/`. The reviewed runtime files are 128×128 RGBA PNGs with transparent padding in `app/src/main/res/drawable-nodpi/`.

## Reproduction

```bash
# Requires ImageMagick 6+
./tools/process_hud_sprites_v144.sh
./tools/generate_hud_preview_v144.sh
```

The processor removes the generated checkerboard backdrop by edge-connected neutral flood fill, crops the subject, scales it to a 116-pixel maximum extent, and centers it on a transparent 128×128 canvas.

## Verification checklist

- Android version: `1.4.4` (`18`)
- New generated concepts this batch: **10**
- New packaged HUD drawables: **10**
- All ten packaged files: 128×128 RGBA with transparent corner pixels
- `SpriteCatalog`: all ten runtime names loaded eagerly
- Preview: 1600×900 PNG regenerated from packaged art
- Static Kotlin pitfall lint: zero errors
- Gradle compile: delegated to the repository's `Build APK` workflow; local Gradle 8.9 acquisition is blocked by the runner's TLS connection
