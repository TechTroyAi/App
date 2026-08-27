# Batch 20 / E1+E2 — evolution moments

Runtime polish when the player **commits** a tower evolution (not browse).

## E1 — confirm beat
- **Rim flash**: lime → gold stroke expanding from the cell (~0.4s via `Tower.evolveFlash`)
- **Fill glow**: soft accent wash under the rim
- **Emblem pop**: evolution icon scales up while flash is live
- **Particles**: kind accent burst + gold secondary burst
- **Floating label**: evolution title in gold with high pop
- **Banner**: `EVOLVED  <TITLE>` (~2.6s)
- **SFX**: low forge `build` sting + bright `ui_click`

## E2 — board acknowledgment (~2.2–2.4s)
- **`Tower.evolveAura`**: neighbor path tiles (Chebyshev ≤√2) get a warmer sheen boost; tower keeps a soft gold outer ring while aura lasts
- **`Tower.evolveProof`**: shots fired during the window are marked `Projectile.evolveHot` — larger glow, gold trail mote, slightly bigger sprite, dual impact spark bursts on hit

No new drawables — uses existing evolution emblems (batches 10–11).

## Later (not shipped)
- **E3**: per-family confirm flavor (bolt snap / frost rim / ember coal)

- Installable APK: `artifacts/Blockhold-Defense-v1.2-installable.apk`
- APK SHA-256: `0ec0d9a8ed3e4680d4d5422e7dabdc7e390fab1b274bed16823986654537b613`
