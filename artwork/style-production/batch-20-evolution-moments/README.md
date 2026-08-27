# Batch 20 / E1–E3 — evolution moments

Runtime polish when the player **commits** a tower evolution (not browse).

## E1 — confirm beat
- **Rim flash** expanding from the cell (~0.4s via `Tower.evolveFlash`)
- **Emblem pop**, dual particle bursts, gold floating title
- **Banner**: `EVOLVED  <TITLE>` (~2.6s)

## E2 — board acknowledgment (~2.2–2.4s)
- **`evolveAura`**: neighbor path sheen boost + soft gold outer ring
- **`evolveProof`**: hotter projectile trail/impact (`Projectile.evolveHot`)

## E3 — per-family flavor
Confirm beat is **family-tinted** (accent → gold) and plays the tower’s own voice:

| Family | Burst feel | SFX |
|--------|------------|-----|
| **Bolt** | White-hot snap sparks | `bolt` + low `build` |
| **Frost** | Cyan + white ice bloom | `frost` + soft `build` |
| **Cannon** | Heavy orange punch | `cannon` + deep `build` |
| **Ember** | Coal orange + gold cinders | `ember` + `build` |
| **Beacon** | Violet resonance + gold | `beacon` + `build` |

Kotlin also draws family secondary rings/sparks during the flash (bolt cross-sparks, frost ice rim, beacon outer ring, ember core wash, cannon heavy stroke). Sideload APK includes family bursts, accent-tinted rim, and family SFX.

No new drawables.

- Installable APK: `artifacts/Blockhold-Defense-v1.2-installable.apk`
- APK SHA-256: `c1036b2b0f13e22c92cfe8c13105bcd07cedd6ff1c9811d09555131067185ccf`
