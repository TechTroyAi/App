# Batch 20 / E1 — evolution confirm beat

Runtime polish when the player **commits** a tower evolution (not browse):

- **Rim flash**: lime → gold stroke expanding from the cell (~0.4s via `Tower.evolveFlash`)
- **Fill glow**: soft accent wash under the rim
- **Emblem pop**: evolution icon scales up while flash is live
- **Particles**: kind accent burst + gold secondary burst
- **Floating label**: evolution title in gold with high pop
- **Banner**: `EVOLVED  <TITLE>` (~2.6s)
- **SFX**: low forge `build` sting + bright `ui_click`

No new drawables — uses existing evolution emblems (batches 10–11).

## Later (not in E1)

- **E2**: neighbor path sheen + hotter next shot trail
- **E3**: per-family confirm flavor (bolt snap / frost rim / ember coal)

- Installable APK: `artifacts/Blockhold-Defense-v1.2-installable.apk`
- APK SHA-256: `87e9150d662d53932cf0470508eee803e5446e937ddbfeccf2d62e4827b9edd4`
