#!/usr/bin/env python3
"""Process the four original trap concepts in visual-production batch 03."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-03"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

TRAPS = {
    "trap_root_snare_source.png": "trap_root_snare.png",
    "trap_ember_rune_source.png": "trap_ember_rune.png",
    "trap_arc_plate_source.png": "trap_arc_plate.png",
    "trap_crusher_block_source.png": "trap_crusher_block.png",
}

for input_name, output_name in TRAPS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    if output_name == "trap_root_snare.png":
        # Curled roots enclose areas of the generated white backdrop.
        remove_enclosed_neutral_background(destination)
    print(destination)
