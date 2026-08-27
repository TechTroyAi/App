# Animation Batch 06 — mechanical tower tops

Integrated three-frame firing strips for:

- Bolt Tower top layer
- Frost Tower top layer
- Cannon Tower top layer

Tower pedestals remain static. The approved original 64×64 top layers remain unchanged as frame B. Each firing top sits on rest frame A, jumps to fire/recoil frame C when `tower.recoil` is set, then recovers through exact neutral B before returning to A. Rotation continues to use the tower's target angle, now with source-rectangle strip rendering around the same draw pivot.

Bolt Tower dims and brightens its existing rear energy crystal. Frost Tower retains the exact mechanical silhouette while its socketed crystal moves from low teal to an icy firing peak. Cannon Tower moves its telescoping barrel from an extended armed pose to a shortened recoil pose with restrained orange breech and muzzle energy. Circular mounts, baselines, and rotation pivots remain anchored.

Eight successful generation calls were used. The initial Frost pair changed the turret silhouette and was rejected; the accepted correction pair restricts movement to crystal energy. Two correction slots remained unused.

Review files:

- `tower-top-frame-review.png` — enlarged rest A / exact neutral B / fire C contact sheet
- `tower-top-firing-review.gif` — timed A → C → B → A firing preview
