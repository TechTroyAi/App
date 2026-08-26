# Batch 08 — Frost Prism and Core Cannon

Integrated original layered tower sprites:

- Frost Prism pedestal
- Frost Prism rotating lens
- Core Cannon pedestal
- Core Cannon rotating turret

Both towers preserve autonomous target tracking. Their top layers are centered around the mechanical pivot and point right at zero degrees, allowing the Canvas renderer to rotate them directly without legacy angle offsets.

The high-resolution `*_source.png` concepts were created specifically for Blockhold Defense. `processed/` contains transparent 64×64 layer outputs produced by `tools/process_sprite_batch_08.py`.

`layered-tower-review.png` composites Bolt Tower, Frost Prism, and Core Cannon at enlarged nearest-neighbor scale. It is a review image and is not packaged into the APK.
