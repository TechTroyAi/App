#!/usr/bin/env python3
"""Process the approved environment and layered Bolt Tower art batch."""

from pathlib import Path
from PIL import Image, ImageOps

from process_sprite_prototypes import isolate

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

ISOLATED = {
    "tower_bolt_base_source.png": "tower_bolt_base.png",
    "tower_bolt_turret_source.png": "tower_bolt_turret.png",
    "landmark_gate_source.png": "landmark_gate.png",
    "landmark_core_source.png": "landmark_core.png",
}


def seamless_tile(source: Path, destination: Path) -> None:
    image = Image.open(source).convert("RGB")
    extent = min(image.size)
    left = (image.width - extent) // 2
    top = (image.height - extent) // 2
    square = image.crop((left, top, left + extent, top + extent))
    quarter = square.resize((64, 64), Image.Resampling.LANCZOS)
    upper = Image.new("RGB", (128, 64))
    upper.paste(quarter, (0, 0))
    upper.paste(ImageOps.mirror(quarter), (64, 0))
    tile = Image.new("RGB", (128, 128))
    tile.paste(upper, (0, 0))
    tile.paste(ImageOps.flip(upper), (0, 64))
    tile.save(destination, optimize=True)


for input_name, output_name in ISOLATED.items():
    isolate(SOURCE / input_name, OUTPUT / output_name)
    print(OUTPUT / output_name)

seamless_tile(SOURCE / "terrain_grass_source.png", OUTPUT / "terrain_grass.png")
seamless_tile(SOURCE / "terrain_path_source.png", OUTPUT / "terrain_path.png")
print(OUTPUT / "terrain_grass.png")
print(OUTPUT / "terrain_path.png")
