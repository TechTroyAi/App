#!/usr/bin/env python3
"""Process the complete crafted-supply icon family in visual batch 12."""

from pathlib import Path

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-12"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

ITEMS = {
    "item_core_patch_source.png": "item_core_patch.png",
    "item_recovery_wrap_source.png": "item_recovery_wrap.png",
    "item_purifier_vial_source.png": "item_purifier_vial.png",
    "item_reforge_coupler_source.png": "item_reforge_coupler.png",
    "item_utility_gearset_source.png": "item_utility_gearset.png",
    "item_survey_lens_source.png": "item_survey_lens.png",
    "item_trap_refit_kit_source.png": "item_trap_refit_kit.png",
    "item_blank_sigil_source.png": "item_blank_sigil.png",
}

for input_name, output_name in ITEMS.items():
    destination = OUTPUT / output_name
    isolate(SOURCE / input_name, destination)
    remove_enclosed_neutral_background(destination)
    print(destination)
