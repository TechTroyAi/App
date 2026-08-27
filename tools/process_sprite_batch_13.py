#!/usr/bin/env python3
"""Process the complete bound-sigil family in visual batch 13."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-13"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

SIGILS = {
    "sigil_might_source.png": "sigil_might.png",
    "sigil_tempo_source.png": "sigil_tempo.png",
    "sigil_reach_source.png": "sigil_reach.png",
    "sigil_clarity_source.png": "sigil_clarity.png",
    "sigil_echoes_source.png": "sigil_echoes.png",
    "sigil_conservation_source.png": "sigil_conservation.png",
}

for input_name, output_name in SIGILS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
