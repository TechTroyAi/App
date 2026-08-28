# Blockhold Defense — F3 Toolkit verification

**Date:** 2026-08-28  
**Phase:** 1.4 Overgrowth — **F3 Toolkit** (utilities + C2 crafts + imbuements)  
**Branch:** `arena/01a0429d-app`  
**Installable APK:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `058f8a7da7e08e565e340467ecc866248850b367b1d19b18258f85b8d8a938db`

Also mirrored as `artifacts/Blockhold-Defense-v1.3-installable.apk` (same bytes).

## Content delivered

### Utilities (page 3 — UTIL n/3)
| Kind | Role |
|------|------|
| Ward Beacon | Nearby towers shrug Hex faster; wave-clear immunity pulse |
| Battle Banner | Nearby towers deal bonus damage |
| Essence Still | Wave clear → Growth Essence |
| Trap Lattice | Nearby path traps deal bonus damage |

### C2 crafts
| Item | Role |
|------|------|
| Overcharge Cell | Selected tower damage surge (~18s) |
| Focus Lens | Selected tower range/pierce boost (~16s) |
| Snap Spring | Selected trap trigger reset + pulse |

### Imbuements
| Sigil | Role |
|------|------|
| Ward | Hex resist + faster hex decay |
| Leech | Nearby kills can restore Core |
| Surge | First activations each wave hit harder |

## Build path
Smali port on F2 decode (`/tmp/blockhold_apk`) + Kotlin source of truth in-repo.  
`apktool b` → `sign-apk.mjs` (v2+v3).

## Smoke checklist
- [ ] UTIL tab cycles **1/3 → 2/3 → 3/3**
- [ ] Place Ward Beacon / Battle Banner / Essence Still / Trap Lattice (art loads)
- [ ] Craft C2 at workshop; Overcharge/Focus on tower; Snap on trap
- [ ] Imbue Ward/Leech/Surge at level-3 structure
- [ ] Wave start refreshes Surge charges; banner/lattice affect damage
