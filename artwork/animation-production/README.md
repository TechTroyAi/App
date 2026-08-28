# Original animation production

This directory extends the approved original Blockhold Defense sprites with deliberately limited two- and three-frame animation. Production rules:

- Preserve the approved static sprite as the neutral frame.
- Generate pose changes only; do not redesign anatomy, equipment, palette, lighting, or perspective.
- Use fixed baselines and centered anchors to prevent gameplay jitter.
- Keep terrain, pedestals, inventory items, evolution emblems, sigils, and resources static unless a mechanic specifically benefits from motion.
- Review every batch as both an enlarged frame contact sheet and a timed animation before Android integration.
- Use at most ten image generations per reviewed production session, retaining correction capacity whenever possible.

Three-frame loops play in the order A → B → C → B. Android resources store horizontal strips with one 64×64 cell per frame.

Completed animation batches:

- Batch 01: Mosser, Runner, Brute, and Shellback
- Batch 02: Splitling, Ironhide Champion, Blink Stalker, and Rootcaller
- Batch 03: Hex Weaver, Siege Colossus, and The Overgrowth; completes the enemy roster
- Batch 04: Enemy Gate, Player Core, Spike Bed, and Root Snare
- Batch 05: Ember Rune, Arc Plate, and Crusher Block; completes the trap roster
- Batch 06: Bolt, Frost, Cannon, Ember Forge, and Resonance Beacon mechanical tower top layers (complete five-tower top family)
- Batch 07: all seven Utilities ambient idle glow strips
