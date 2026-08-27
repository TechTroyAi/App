#!/usr/bin/env python3
"""Process the final resource icon family in visual batch 14."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-14"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

RESOURCES = {
    "icon_salvage_parts_source.png": "icon_salvage_parts.png",
    "icon_growth_essence_source.png": "icon_growth_essence.png",
    "icon_forge_cache_source.png": "icon_forge_cache.png",
}

for input_name, output_name in RESOURCES.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
