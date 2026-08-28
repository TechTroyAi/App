# Blockhold Defense — F4 New guns verification

**Date:** 2026-08-28  
**Phase:** 1.4 Overgrowth — **F4 New guns** (+3 towers full kit)  
**Branch:** `arena/01a0429d-app`  
**Installable APK:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `3d8375d48067e42d4613fe3458afd85b888fbc838904f52470148cf525c2652c`

## Content delivered

### Towers (TWR tab pages **1/2** and **2/2**)
| Kind | Title | Role |
|------|-------|------|
| THORN | Thorn Spire | Fast shots that **mark** foes (+22% damage while marked) |
| LANCE | Shard Lance | High pierce / rail damage; Skewer hits a second target |
| MIRE | Mire Spout | Bog **slow** on hit; Tar Font evolution briefly **stuns** |

Page 1: BOLT · FROST · CANNON · EMBER  
Page 2: BEACON · THORN · LANCE · MIRE  

### Evolutions (×6)
| Evolution | Tower | Role |
|-----------|-------|------|
| Bramble Crown | THORN | Marks last longer |
| Venom Quill | THORN | Marked foes also burn briefly |
| Prism Rail | LANCE | Pierce / rail identity |
| Skewer Array | LANCE | Second high-progress target |
| Bog King | MIRE | Stickier bog pools |
| Tar Font | MIRE | Brief stun on primary |

### Art (drawable-nodpi)
- `tower_{thorn,lance,mire}_{base,turret}`
- `projectile_{thorn,lance,mire}` · `impact_{thorn,lance,mire}`
- `evolution_{bramble_crown,venom_quill,prism_rail,skewer_array,bog_king,tar_font}`
- Review sheet: `artwork/style-production/batch-26-new-guns-f4/f4-family-review.png`

## Build path
Kotlin source of truth (`GameModels` / `SpriteCatalog` / `GameView`) + smali port on F3 decode (`/tmp/blockhold_apk`).  
`apktool b` → `sign-apk.mjs` (private.pem + cert.pem, v2+v3).

## Smoke checklist
- [ ] TWR tab cycles **1/2 → 2/2** (second page shows Beacon + F4 trio)
- [ ] Place Thorn Spire / Shard Lance / Mire Spout (base + turret art)
- [ ] Thorn marks enemies; marked foes take bonus damage
- [ ] Lance deals heavy pierce hits; Skewer Array second-target when evolved
- [ ] Mire slows; Tar Font stun when evolved
- [ ] Workshop evolution picks show F4 pairs for each new tower
