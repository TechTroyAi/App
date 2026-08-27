# Batch 19 / 1.3 Phase C — board life (quiet ambient)

Runtime polish (no new drawables). Keeps ambient **subtle** vs combat FX:

- **Path route**: softer base stroke + breathing alpha; traveling shimmer dots along segments (`ambientTime * 0.55`)
- **Path tiles**: warm sheen pulse staggered by cell
- **Grass tiles**: slight alpha sway + existing tip flecks
- **Gate**: soft lime intake glow under the sprite (pulse-driven)
- **Core**: health-linked heartbeat (`beatHz` faster when wounded), dual glow rings, danger-tinted RGB, sprite scale breath, hot center mote

Kotlin source in `GameView.kt` is the full design. Sideload APK includes the smali port of the above.

- Installable APK: `artifacts/Blockhold-Defense-v1.2-installable.apk`
- APK SHA-256: `8a81e7bc51b067020accb5c61cb6ad2fc34d239a830b9301a173f97c95a42b19`
