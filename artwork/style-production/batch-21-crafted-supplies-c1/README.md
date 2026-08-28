# Batch 21 / 1.4 Crafts C1 — panic forge kit

Three new **use-from-supplies** crafts (workshop level 2):

| Item | Cost (B/P/E) | Effect |
|------|----------------|--------|
| **Splinter Brace** | 90 / 4 / 1 | Arm once: next Core hit dealing **2+** damage loses **1** |
| **Resin Seal** | 110 / 3 / 1 | Arms the same one-leak **Core barrier** as Phase Barrier perk |
| **Cooling Flask** | 70 / 3 / 0 | Clears Hex on **selected tower** + **6s** Hex immunity ward |

## Art
- 64×64 transparent icons in `processed/` and `app/src/main/res/drawable-nodpi/`
- Forge plaque frame, lime/cyan/gold accents (matches batch-12 family)

## Code
- `CraftedItem` enum + `SpriteCatalog` maps
- `GameView`: arm flags, core-hit brace, barrier seal, cooling immunity map, save `run_splinter_brace`

## Ship

- Installable SHA256: `b0718dddc5d18837615d7a9de90ea3576ef72dd40c15851b283b8a19935b910e`
- APK: `artifacts/Blockhold-Defense-v1.2-installable.apk`
