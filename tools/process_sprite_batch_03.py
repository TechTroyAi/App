#!/usr/bin/env python3
"""Process the four original trap concepts in visual-production batch 03."""

from pathlib import Path
from PIL import Image

from process_sprite_prototypes import isolate

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
        # The curled roots enclose areas of the generated white backdrop, so
        # remove those neutral pixels after edge-connected isolation.
        image = Image.open(destination).convert("RGBA")
        cleaned = []
        for red, green, blue, alpha in image.getdata():
            if min(red, green, blue) >= 150 and max(red, green, blue) - min(red, green, blue) <= 34:
                cleaned.append((red, green, blue, 0))
            else:
                cleaned.append((red, green, blue, alpha))
        image.putdata(cleaned)
        image.save(destination, optimize=True)
    print(destination)
