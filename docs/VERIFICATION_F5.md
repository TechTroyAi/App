# Blockhold Defense — F5 Field pests verification

**Date:** 2026-08-28  
**Phase:** 1.4 Overgrowth — **F5 Field pests** (+5 normal enemies)  
**Branch:** `arena/01a0429d-app`  
**Installable APK:** `artifacts/Blockhold-Defense-v1.2-installable.apk`  
**SHA-256:** `39241216a557ba83bae54261d4d157c0f5ec82dc3aa6cf3a81ef488f02b86588`

## Content delivered

### Normals (clear jobs)
| Kind | Title | Job |
|------|-------|-----|
| BRIAR_MITE | Briar Mite | Fast path pest — **jams traps** after trigger (~3.2s) |
| RUST_TICK | Rust Tick | On hit, briefly **rusts (hexes)** nearest tower |
| DRIFT_SEED | Drift Seed | Floater — **resists slow** hard |
| HOLLOW_SHELL | Hollow Shell | **Shell buffer** absorbs first damage; death gifts armor to allies |
| WISP_DRIFTER | Wisp Drifter | Soft **stealth** pulses (same target deprioritization as Gloom) |

### Art (drawable-nodpi)
- `enemy_{briar_mite,rust_tick,drift_seed,hollow_shell,wisp_drifter}` (192×64 strips)
- Review: `artwork/style-production/batch-27-field-pests-f5/f5-family-review.png`

### Waves
Regular pool expanded (F1 + F5). Theme buckets mix the new pests into swarm/rush/armored/sabotage fronts.

## Build path
Kotlin source of truth + smali port on F4 decode.  
`apktool b` → `sign-apk.mjs`.

## Smoke checklist
- [ ] Waves include new pests (art loads for all five)
- [ ] Briar Mite jams a trap after walking over it
- [ ] Hollow Shell shows shell absorb then SHELL BREAK / real HP
- [ ] Rust Tick can rust a nearby tower after being shot
- [ ] Drift Seed shrugs most slow; Wisp fades from targeting priority
