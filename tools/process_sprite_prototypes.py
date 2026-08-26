#!/usr/bin/env python3
"""Turn the approved concept renders into clean 64 px transparent prototypes.

The image generator may render a white/checker preview instead of alpha. This
script flood-fills only neutral, bright pixels connected to the canvas edge,
then crops and point-scales the isolated sprite without erasing enclosed metal
highlights.
"""

from collections import deque
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "prototype"
OUTPUT = SOURCE / "processed"
OUTPUT.mkdir(parents=True, exist_ok=True)

SPRITES = {
    "bolt_tower_source.png": "bolt_tower.png",
    "spike_bed_source.png": "spike_bed.png",
    "enemy_mosser_source.png": "enemy_mosser.png",
    "enemy_ironhide_source.png": "enemy_ironhide.png",
    "utility_block_generator_source.png": "utility_block_generator.png",
    "corruption_spore_path_source.png": "corruption_spore_path.png",
}


def is_removable_background(pixel: tuple[int, int, int, int]) -> bool:
    red, green, blue, _ = pixel
    return min(red, green, blue) >= 145 and max(red, green, blue) - min(red, green, blue) <= 28


def isolate(source: Path, destination: Path) -> None:
    image = Image.open(source).convert("RGBA")
    width, height = image.size
    pixels = image.load()
    background = bytearray(width * height)
    queue: deque[tuple[int, int]] = deque()

    def enqueue(x: int, y: int) -> None:
        index = y * width + x
        if not background[index] and is_removable_background(pixels[x, y]):
            background[index] = 1
            queue.append((x, y))

    for x in range(width):
        enqueue(x, 0)
        enqueue(x, height - 1)
    for y in range(height):
        enqueue(0, y)
        enqueue(width - 1, y)

    while queue:
        x, y = queue.popleft()
        if x > 0:
            enqueue(x - 1, y)
        if x + 1 < width:
            enqueue(x + 1, y)
        if y > 0:
            enqueue(x, y - 1)
        if y + 1 < height:
            enqueue(x, y + 1)

    for y in range(height):
        for x in range(width):
            if background[y * width + x]:
                red, green, blue, _ = pixels[x, y]
                pixels[x, y] = (red, green, blue, 0)

    alpha = image.getchannel("A")
    bounds = alpha.getbbox()
    if bounds is None:
        raise RuntimeError(f"No foreground found in {source}")
    left, top, right, bottom = bounds
    pad = max(3, round(max(right - left, bottom - top) * 0.035))
    left = max(0, left - pad)
    top = max(0, top - pad)
    right = min(width, right + pad)
    bottom = min(height, bottom + pad)
    cropped = image.crop((left, top, right, bottom))

    target_extent = 58
    scale = min(target_extent / cropped.width, target_extent / cropped.height)
    target_width = max(1, round(cropped.width * scale))
    target_height = max(1, round(cropped.height * scale))
    scaled = cropped.resize((target_width, target_height), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    x = (64 - target_width) // 2
    y = (64 - target_height) // 2
    canvas.alpha_composite(scaled, (x, y))
    canvas.save(destination, optimize=True)


for input_name, output_name in SPRITES.items():
    isolate(SOURCE / input_name, OUTPUT / output_name)
    print(OUTPUT / output_name)
