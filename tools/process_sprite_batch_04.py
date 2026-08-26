#!/usr/bin/env python3
"""Process the four original regular-enemy concepts in visual batch 04."""

from pathlib import Path

from process_sprite_prototypes import isolate

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-04"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

ENEMIES = {
    "enemy_runner_source.png": "enemy_runner.png",
    "enemy_brute_source.png": "enemy_brute.png",
    "enemy_shellback_source.png": "enemy_shellback.png",
    "enemy_splitling_source.png": "enemy_splitling.png",
}

for input_name, output_name in ENEMIES.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    print(destination)
