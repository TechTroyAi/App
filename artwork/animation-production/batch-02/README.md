# Animation Batch 02 — Splitling and first elite group

Integrated three-frame movement strips for:

- Splitling
- Ironhide Champion
- Blink Stalker
- Rootcaller

The approved original 64×64 sprite is retained under `neutral/` and used unchanged as frame B. New A and C poses are retained as generated high-resolution sources, reviewed selections, and processed anchored frames. The final Android resources are 192×64 horizontal A/B/C strips.

Rootcaller's initial poses were rejected because they changed its defining orb-face, tendril arm, and palette. Both correction slots were used to restore the exact dark face void, green orb and shards, olive armor, stone left forearm, and attached right tendrils while moving only the legs. Blink Stalker's generated anatomy was accepted, then its cyan phase highlights were deterministically normalized to the approved violet palette during processing.

All processed poses share each neutral sprite's horizontal center and ground baseline. They use the generic enemy strip renderer introduced in Animation Batch 01.

Review files:

- `enemy-animation-frame-review.png` — enlarged A/B/C contact sheet
- `enemy-animation-review.gif` — timed four-role ping-pong preview
