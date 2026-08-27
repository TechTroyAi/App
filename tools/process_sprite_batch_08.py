#!/usr/bin/env python3
"""Process Frost Prism and Core Cannon tower layers in visual batch 08."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-08"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

LAYERS = {
    "tower_frost_base_source.png": "tower_frost_base.png",
    "tower_frost_turret_source.png": "tower_frost_turret.png",
    "tower_cannon_base_source.png": "tower_cannon_base.png",
    "tower_cannon_turret_source.png": "tower_cannon_turret.png",
}

for input_name, output_name in LAYERS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
