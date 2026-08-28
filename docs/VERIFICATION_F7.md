# Blockhold Defense — F7 Utils + Imbues verification

**Date:** 2026-08-28  
**Phase:** 1.4 Overgrowth — **F7 infrastructure pack** (6 utilities + 4 imbuements)  
**Branch:** `arena/01a0429d-app`  
**Installable APK:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `c8113f86224ea3eab742904ac509dd665f4c6eee04647168cec9bd98ea1dad57`

## Sprite pipeline (aligned)

All F7 sources processed with `tools/process_style_batch.py`, matching classic batches:

1. Light cream / neutral studio background on sources (not pure black)
2. `neutralize_background` (dark-edge → light) when needed
3. `isolate()` edge flood-fill from `process_sprite_prototypes.py`
4. `remove_enclosed_neutral_background`
5. Icons → **64×64**; utility strips → **192×64** A/B/C
6. Copy into `drawable-nodpi/` + family review sheet

F4–F6 assets were **reprocessed** through the same tool.

## Content

### Utilities (+6) — UTIL pages **1/5 … 5/5**
| Kind | Verb |
|------|------|
| Aegis Pylon | Nearby towers take shorter Hex |
| Growth Nursery | Wave clear → bonus Essence |
| Path Warden | Nearby path enemies move slower |
| Spark Relay | Nearby towers fire faster |
| Bounty Board | Nearby kills → bonus Blocks |
| Cinder Kiln | Hotter burns near kiln |

### Imbuements (+4)
| Sigil | Verb |
|-------|------|
| Volley | Every 4th shot fragments a second target |
| Siege | +22% vs elites/bosses |
| Fortune | Chance of extra Blocks on kill |
| Binding | Hits apply brief root |

### Art
`artwork/style-production/batch-29-utils-imbues-f7/`

## Remaining sprite budget
| Lane | Left | Sheets (approx) |
|------|------|-----------------|
| Towers | ~7 full kits | ~42 |
| Imbues | ~1 (quota was +10; have +9 of F3/F6/F7) | ~1 |
| **Total** | | **~43** mostly towers |

## Smoke
- [ ] UTIL tab cycles **5 pages**; new utils place with art
- [ ] Spark Relay / Path Warden / Aegis feel on board
- [ ] Imbue Volley/Siege/Fortune/Binding at level-3
