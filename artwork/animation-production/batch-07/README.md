# Animation Batch 07 — utility ambient idles

Integrated three-frame ambient strips for the full Utility family:

- Block Generator
- Cache Depot
- Forge Workshop
- Purifier Totem
- Surveyor Station
- Reforge Anchor
- Salvage Yard

The approved original 64×64 sprite is retained under `neutral/` and used unchanged as frame B. Rest frame A dims each utility's signature glow or energy cue; active frame C brightens that same cue with a restrained bloom. Structure, palette, silhouette, and baseline stay locked.

Runtime playback uses A → B → C → B, derived from `ambientTime` with per-utility and per-tile phase offsets so neighboring utilities do not march in sync. Toolbar icons show the neutral frame.

Review files:

- `utility-idle-frame-review.png` — enlarged A/B/C contact sheet
- `utility-idle-animation-review.gif` — timed ambient ping-pong preview
