#!/usr/bin/env python3
"""Process Ember and Beacon evolution emblems in visual batch 11."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-11"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

EVOLUTIONS = {
    "evolution_inferno_engine_source.png": "evolution_inferno_engine.png",
    "evolution_cinder_reactor_source.png": "evolution_cinder_reactor.png",
    "evolution_storm_choir_source.png": "evolution_storm_choir.png",
    "evolution_harmony_nexus_source.png": "evolution_harmony_nexus.png",
}

for input_name, output_name in EVOLUTIONS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
