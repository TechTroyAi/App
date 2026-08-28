# Blockhold Defense — F8a Towers pack verification

**Date:** 2026-08-28  
**Phase:** 1.4 Overgrowth — **F8a towers** (Gale Spire / Sunforge / Lodestone)  
**Branch:** `arena/01a0429d-app`  
**Installable APK:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `bfec42f78a96875e7415d0eccb724bb1c9a7130722015a16784e55bb3859df4e`

## Content

### Towers (+3) — TWR pages **1/3 … 3/3**
| Kind | Cost | Verb |
|------|------|------|
| **Gale Spire** | 140 | Shoves target backward on the route; Cyclone Crown / Shear Blade evos |
| **Sunforge** | 160 | Damage + ignite; Solar Flare spread / Hearth Core close-range |
| **Lodestone** | 150 | Yanks target backward; Pull Well / Repulsor stun |

### Evolutions (+6)
| Evo | Tower | Verb |
|-----|-------|------|
| Cyclone Crown | Gale | Stronger shove |
| Shear Blade | Gale | Secondary cut + shove (Kotlin; simplified in smali) |
| Solar Flare | Sunforge | Burn spread (Kotlin) |
| Hearth Core | Sunforge | Close-range bonus (Kotlin) |
| Pull Well | Lodestone | Stronger yank |
| Repulsor | Lodestone | Brief stun on hit |

### Art
`artwork/style-production/batch-30-towers-f8a/` — light cream sources → `process_style_batch.py` → 64 icons / 192×64 strips → `drawable-nodpi/`.

Lodestone projectile/impact and all six evo emblems used pure-PIL synth placeholders (gen budget was 10/turn; Gale+Sunforge kits + Lodestone base/turret were AI-gen).

## Build notes
- Kotlin sources updated (GameModels / SpriteCatalog / GameView).
- Installable APK via apktool smali path (enums end-appended; GameView F8a place/draw/impact/toolAccent).
- BuildTool ordinals **end-appended** after CRUSHER (14–16) so SPIKES+ packed-switch stays stable.
- TowerKind ordinals 8–10; TowerEvolution 16–21.

## Smoke
- [ ] TWR tab cycles **3 pages**; page 3 shows Gale / Sunforge / Lodestone
- [ ] Place each tower — base + turret art, projectiles, impacts
- [ ] Gale shove / Sunforge burn / Lodestone yank feel on path
- [ ] Level-3 evolve offers the matching pair of evos
