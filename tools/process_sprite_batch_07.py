#!/usr/bin/env python3
"""Process the five corruption concepts in visual batch 07."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-07"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

CORRUPTIONS = {
    "corruption_carapace_growth_source.png": "corruption_carapace_growth.png",
    "corruption_hex_bloom_source.png": "corruption_hex_bloom.png",
    "corruption_blink_root_source.png": "corruption_blink_root.png",
    "corruption_thorn_soil_source.png": "corruption_thorn_soil.png",
    "corruption_brood_nest_source.png": "corruption_brood_nest.png",
}

for input_name, output_name in CORRUPTIONS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
