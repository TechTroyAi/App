#!/usr/bin/env python3
"""Process Bolt, Frost, and Cannon evolution emblems in visual batch 10."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-10"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

EVOLUTIONS = {
    "evolution_chain_conductor_source.png": "evolution_chain_conductor.png",
    "evolution_rail_spire_source.png": "evolution_rail_spire.png",
    "evolution_blizzard_lens_source.png": "evolution_blizzard_lens.png",
    "evolution_shatter_crystal_source.png": "evolution_shatter_crystal.png",
    "evolution_siege_mortar_source.png": "evolution_siege_mortar.png",
    "evolution_gravity_cannon_source.png": "evolution_gravity_cannon.png",
}

for input_name, output_name in EVOLUTIONS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
