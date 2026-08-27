#!/usr/bin/env python3
"""Process the elite-enemy and boss concepts in visual batch 05."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-05"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

ENEMIES = {
    "enemy_blink_stalker_source.png": "enemy_blink.png",
    "enemy_rootcaller_source.png": "enemy_rootcaller.png",
    "enemy_hex_weaver_source.png": "enemy_hex.png",
    "enemy_siege_colossus_source.png": "enemy_siege.png",
    "enemy_overgrowth_boss_source.png": "enemy_overgrowth.png",
}

for input_name, output_name in ENEMIES.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
