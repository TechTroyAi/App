# Batch 24 — Named threats (1.4 F2)

Forge-fantasy elite/boss strips for Era 1.4 **F2 Named threats**.

## Roster

| Kind | Role | Verb / tell |
|------|------|-------------|
| **Grave Mender** | Elite | Wind-up **MENDING…** then heals nearby allies |
| **Pyre Wight** | Elite | Wind-up **PYRE…** then ignition trail + light tower scorch |
| **Iron Monarch** | Boss | Wind-up **MONARCH SLAM** then area tower hex + screen shake |
| **Spore Sovereign** | Boss | Wind-up **SPORE BLOOM** then mycelial spawn + ally mend |

## Pipeline

- Source frames: `enemy_*_source.png`
- Processed 192×64 A/B/C strips in `app/src/main/res/drawable-nodpi/`
  - `enemy_grave_mender.png`
  - `enemy_pyre_wight.png`
  - `enemy_iron_monarch.png`
  - `enemy_spore_sovereign.png`
- Family review: `f2-family-review.png`

## Waves

- Elites rotate into every 5th wave pool (with F1 Thornback).
- Bosses on every 10th wave: tier%3 → Iron Monarch / Spore Sovereign / Overgrowth; every 30th stays **Overgrowth**.
