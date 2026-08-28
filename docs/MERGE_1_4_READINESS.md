# 1.4 Overgrowth — merge readiness

**Branch:** `arena/01a0429d-app`  
**Installable:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `7e43587c47f54c016a8094950fd77bd56623aeb1438c60d7d0d9edd9180340d4`  
**Date:** 2026-08-28

## Content complete vs ERA +10 targets

| Lane | Status |
|------|--------|
| Board F0 16×9 | Done |
| Normals +10 | Done (F1+F5) |
| Elites +5 / Bosses +4 | Done (F1/F2/F6) |
| Utilities +10 | Done (F3+F7) |
| Crafts +10 | Done (C1–C3) |
| Imbuements +10 | **Done** (F3+F6+F7+Rime) |
| Towers +10 | **Done** (F4+F8a+b+c) |

## Remaining (non-blocking for merge)

- Optional C4 craft flex slot
- Optional art polish (some evo/proj PIL placeholders from 10-img/turn caps)
- Device smoke checklist in VERIFICATION_F* docs
- Full Kotlin rebuild when Android SDK available (current ship path is apktool smali)

## PR intent

Merge era 1.4 Overgrowth content dump into `main` as installable v1.2.
