# Batch 15 — tower projectile flight sprites

Original forge-fantasy projectile strips for all five base towers:

- Bolt energy rivet (`projectile_bolt`)
- Frost crystal shard (`projectile_frost`)
- Cannon forged shell (`projectile_cannon`)
- Ember fire droplet (`projectile_ember`)
- Beacon resonance mote (`projectile_beacon`)

Each Android resource is a 192×64 horizontal A/B/C strip. Sprites are authored pointing right; runtime rotates them toward the current target using the projectile velocity angle. Flight playback uses A → B → C → B driven by projectile age.

High-resolution sources live in `sources/`. Processed neutrals, frames, and strips live in `processed/`. Review sheet: `projectile-frame-review.png`. Flight GIF: `projectile-flight-review.gif`.

## Runtime wiring (Option B shipped)

- `SpriteCatalog.projectile(TowerKind)` loads the five strips.
- `GameView.drawProjectile` draws accent glow, then the strip frame rotated toward the target (`atan2`), playback A→B→C→B from projectile age.
- Installable APK: `artifacts/Blockhold-Defense-v1.2-installable.apk` (Batch 15 included).
- Multi-frame strip count: **35** (30 prior animated + 5 projectiles). Evolution-unique shots / impact FX remain Option C.
