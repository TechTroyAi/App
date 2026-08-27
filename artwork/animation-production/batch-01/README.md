# Animation Batch 01 — regular enemies, part one

Integrated three-frame movement strips for:

- Mosser
- Runner
- Brute
- Shellback

The approved original 64×64 sprite is retained under `neutral/` and used unchanged as frame B. New A and C poses are retained as generated high-resolution sources, reviewed selections, and processed anchored frames. The final Android resources are 192×64 horizontal A/B/C strips.

Several generation responses supplied multiple unlabeled pose candidates on one white canvas. `tools/process_animation_batch_01.py` reproducibly selects the reviewed Mosser and Runner candidates before isolation. Runner's selected opposite-stride pose had its generated orange energy pixels normalized back to the approved acid-lime palette. Brute's first opposite pose was rejected because it changed violet cracks to lime and was regenerated; Shellback's first left-pose request returned no image and was retried.

All processed movement poses are aligned to the neutral sprite's horizontal center and ground baseline. Runtime playback uses A → B → C → B, derives cadence from each enemy's existing movement animation clock, and offsets initial phases by enemy ID to avoid synchronized marching.

Review files:

- `regular-enemy-frame-review.png` — enlarged A/B/C contact sheet
- `regular-enemy-animation-review.gif` — timed four-role ping-pong preview
