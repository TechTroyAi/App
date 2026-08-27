# Animation Batch 04 — landmarks and first traps

Integrated three-frame strips for:

- Enemy Gate
- Player Core
- Spike Bed
- Root Snare

The approved original 64×64 sprites remain unchanged as frame B. Enemy Gate and Player Core play slow A → B → C → B ambient mechanism/energy loops. Spike Bed and Root Snare do not loop continuously: their idle toolbar, cache, and battlefield appearance uses armed frame A, then each trigger's existing `pulse` value drives C → B → A recovery.

Spike Bed retracts into its five original sockets at rest and extends those same spikes on impact. Root Snare progresses from a flattened open ring through the original neutral curl to a tight inward grip. All foundations remain aligned to the exact neutral horizontal center and baseline.

All eight first-pass generations were accepted, leaving both correction slots unused.

Review files:

- `landmark-trap-frame-review.png` — enlarged A/B/C contact sheet
- `landmark-trap-animation-review.gif` — timed visual preview; traps use trigger timing in gameplay
