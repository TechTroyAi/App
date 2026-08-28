# Batch 16 — tower impact bursts (1.3 Phase A)

Original forge-fantasy **hit/impact** strips for the five base towers, paired with Batch 15 flight projectiles.

| Resource | Identity | Accent |
|----------|----------|--------|
| `impact_bolt` | Electric spark star | Lime |
| `impact_frost` | Crystal shatter | Cyan |
| `impact_cannon` | Shell blast + brass debris | Orange |
| `impact_ember` | Fire bloom | Ember red |
| `impact_beacon` | Resonance rings | Violet / cyan |

Each Android drawable is a **192×64** horizontal **A/B/C** strip (64×64 frames). Playback is one-shot A→B→C on projectile impact (particles still spawn underneath).

## Runtime

- `SpriteCatalog.impact(TowerKind)`
- `ImpactFx` spawned from `impactProjectile`
- `GameView.drawImpact` — no rotation (radial bursts)

## Out of scope (later)

Evolution-unique impacts, enemy hurt/death, UI/board life, full SFX pack.
