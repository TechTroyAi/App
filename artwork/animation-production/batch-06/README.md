# Animation Batch 06 — mechanical tower tops

Integrated three-frame firing strips for:

- Bolt Tower top layer
- Frost Tower top layer
- Cannon Tower top layer
- Ember Forge flame core
- Resonance Beacon pulse

Tower pedestals remain static. The approved original 64×64 top layers remain unchanged as frame B. Each firing top sits on rest frame A, jumps to fire/recoil frame C when `tower.recoil` is set, then recovers through exact neutral B before returning to A. Rotation continues to use the tower's target angle, now with source-rectangle strip rendering around the same draw pivot.

Bolt Tower dims and brightens its existing rear energy crystal. Frost Tower retains the exact mechanical silhouette while its socketed crystal moves from low teal to an icy firing peak. Cannon Tower moves its telescoping barrel from an extended armed pose to a shortened recoil pose with restrained orange breech and muzzle energy. Ember Forge banks its flame low at rest and roars taller/brighter on fire while locking the iron collar. Resonance Beacon contracts its cyan rings at rest and expands a brighter pulse on fire while locking the brass three-arm rotor. Circular mounts, baselines, and rotation pivots remain anchored.

This batch originally shipped Bolt/Frost/Cannon only; Ember and Beacon tops were completed later to finish the full five-tower top family (3/5 → 5/5).

Review files:

- `tower-top-frame-review.png` — enlarged rest A / exact neutral B / fire C contact sheet for all five tops
- `tower-top-firing-review.gif` — timed A → C → B → A firing preview
