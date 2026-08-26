#!/usr/bin/env python3
"""Process the six Utility concepts in visual batch 06."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-06"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

UTILITIES = {
    "utility_cache_depot_source.png": "utility_cache_depot.png",
    "utility_forge_workshop_source.png": "utility_forge_workshop.png",
    "utility_purifier_totem_source.png": "utility_purifier_totem.png",
    "utility_surveyor_station_source.png": "utility_surveyor_station.png",
    "utility_reforge_anchor_source.png": "utility_reforge_anchor.png",
    "utility_salvage_yard_source.png": "utility_salvage_yard.png",
}

for input_name, output_name in UTILITIES.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
