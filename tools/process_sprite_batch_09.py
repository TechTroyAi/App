#!/usr/bin/env python3
"""Process Ember Forge and Resonance Beacon layers in visual batch 09."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-09"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

LAYERS = {
    "tower_ember_base_source.png": "tower_ember_base.png",
    "tower_ember_flame_source.png": "tower_ember_flame.png",
    "tower_beacon_base_source.png": "tower_beacon_base.png",
    "tower_beacon_pulse_source.png": "tower_beacon_pulse.png",
}

for input_name, output_name in LAYERS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
