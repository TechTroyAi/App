# Original animation production

This directory extends the approved original Blockhold Defense sprites with deliberately limited two- and three-frame animation. Production rules:

- Preserve the approved static sprite as the neutral frame.
- Generate pose changes only; do not redesign anatomy, equipment, palette, lighting, or perspective.
- Use fixed baselines and centered anchors to prevent gameplay jitter.
- Keep terrain, pedestals, inventory items, evolution emblems, sigils, and resources static unless a mechanic specifically benefits from motion.
- Review every batch as both an enlarged frame contact sheet and a timed animation before Android integration.
- Use at most ten image generations per reviewed production session, retaining correction capacity whenever possible.

Three-frame loops play in the order A → B → C → B. Android resources store horizontal strips with one 64×64 cell per frame.
