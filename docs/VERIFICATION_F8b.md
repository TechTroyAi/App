# Blockhold Defense — F8b Towers pack verification

**Date:** 2026-08-28  
**Phase:** 1.4 Overgrowth — **F8b towers** (Howl Spire / Vitriol Spout)  
**Branch:** `arena/01a0429d-app`  
**Installable APK:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `ec5b8db7d455c2a2a7a26b6cccf2dd5f2c0c0cc8396b3c0de9da737e080c0bcb`

## Content

### Towers (+2) — TWR pages **1/4 … 4/4**
| Kind | Cost | Verb |
|------|------|------|
| **Howl Spire** | 145 | Clears stealth (gloom/wisp) + slow; Pack Call / Echo Vault |
| **Vitriol Spout** | 155 | Armor shred DoT window; Acid Vein / Rust Scour |

### Evolutions (+4)
| Evo | Tower | Verb |
|-----|-------|------|
| Pack Call | Howl | Secondary slow nearby |
| Echo Vault | Howl | Longer reveal lock (brief stun) |
| Acid Vein | Vitriol | Stronger/longer shred |
| Rust Scour | Vitriol | Burn tick on shredded target |

### Art
`artwork/style-production/batch-31-towers-f8b/` — light cream → `process_style_batch.py` → nodpi.

Echo Vault + Rust Scour evo icons are PIL synth placeholders (10-img gen budget used on core kits + Pack Call / Acid Vein).

## Build
- Kotlin: models, catalog, GameView (pages %4, armorShred field, impacts).
- Smali end-append TowerKind 11–12, BuildTool 17–18, TE 22–25; GameView place/draw/impact/toolAccent.

## Smoke
- [ ] TWR cycles **4 pages**; page 4 shows Howl / Vitriol
- [ ] Howl peels Wisp/Gloom stealth and slows
- [ ] Vitriol shred makes armored foes take more damage for ~2.4s
- [ ] Evolve offers matching pairs at level-3
