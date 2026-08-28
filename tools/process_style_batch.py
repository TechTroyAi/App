#!/usr/bin/env python3
"""Process Era 1.4 style-production batches with the original isolate pipeline.

Matches tools/process_sprite_prototypes.py + animation strip assembly:
  - Single icons (items, sigils, tower bases, evo emblems): 64x64 via isolate()
  - Horizontal 3-panel sources (enemies, turrets, projectiles, impacts, utils):
    split into 3 columns → isolate each to 64x64 → composite 192x64 A/B/C strip

Usage:
  /tmp/pilvenv/bin/python tools/process_style_batch.py batch-29-utils-imbues-f7
  /tmp/pilvenv/bin/python tools/process_style_batch.py batch-27-field-pests-f5 --reprocess
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from process_sprite_prototypes import isolate, remove_enclosed_neutral_background  # noqa: E402

STYLE = ROOT / "artwork" / "style-production"
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# Asset stem → kind. Stems match drawable names without extension.
STRIP_PREFIXES = (
    "enemy_",
    "tower_",  # turrets / some bases may be strip; bases alone are icon — see classify
    "projectile_",
    "impact_",
    "utility_",
)
ICON_PREFIXES = (
    "item_",
    "sigil_",
    "evolution_",
    "icon_",
)


def classify(stem: str) -> str:
    """Return 'strip' or 'icon'."""
    if stem.startswith(ICON_PREFIXES):
        return "icon"
    if stem.startswith("tower_") and stem.endswith("_base"):
        return "icon"
    if stem.startswith("tower_") and stem.endswith("_turret"):
        return "strip"
    if stem.startswith("tower_") and "_pulse" in stem or stem.endswith("_flame"):
        return "strip"
    if any(stem.startswith(p) for p in STRIP_PREFIXES):
        return "strip"
    return "icon"


def neutralize_background(image: Image.Image) -> Image.Image:
    """Map near-black studio backdrops to light-neutral so isolate() edge-flood works.

    Classic batches used bright neutral grounds. Recent gens used pure black —
    convert edge-connected dark pixels to light gray before isolate.
    """
    from collections import deque

    image = image.convert("RGBA")
    width, height = image.size
    pixels = image.load()
    dark = bytearray(width * height)
    queue: deque[tuple[int, int]] = deque()

    def is_dark(px: tuple[int, int, int, int]) -> bool:
        r, g, b, _ = px
        return r + g + b < 70 and max(r, g, b) - min(r, g, b) <= 40

    def enqueue(x: int, y: int) -> None:
        index = y * width + x
        if not dark[index] and is_dark(pixels[x, y]):
            dark[index] = 1
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
            if dark[y * width + x]:
                pixels[x, y] = (210, 210, 210, 255)
    return image


def process_icon(source: Path, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    prepared = dest.parent / f".prep_{dest.stem}.png"
    neutralize_background(Image.open(source)).save(prepared)
    isolate(prepared, dest)
    remove_enclosed_neutral_background(dest)
    prepared.unlink(missing_ok=True)


def process_strip(source: Path, dest: Path) -> None:
    """Split a 3-panel (or single) source into a 192x64 A/B/C strip via isolate."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.parent / f".tmp_{dest.stem}"
    tmp.mkdir(exist_ok=True)

    image = neutralize_background(Image.open(source).convert("RGBA"))
    w, h = image.size
    # Prefer equal thirds; if already ~3:1 strip, split; if square-ish single, triplicate
    ratio = w / max(h, 1)
    frames_src: list[Image.Image] = []
    if ratio >= 2.2:
        for i in range(3):
            x0 = int(i * w / 3)
            x1 = int((i + 1) * w / 3)
            frames_src.append(image.crop((x0, 0, x1, h)))
    else:
        # single pose — use same art for A/B/C (idle) so runtime strip still loads
        frames_src = [image.copy(), image.copy(), image.copy()]

    frame_paths: list[Path] = []
    for i, frame in enumerate(frames_src):
        raw = tmp / f"frame_{i}_raw.png"
        out = tmp / f"frame_{i}.png"
        frame.save(raw)
        isolate(raw, out)
        remove_enclosed_neutral_background(out)
        frame_paths.append(out)

    strip = Image.new("RGBA", (192, 64), (0, 0, 0, 0))
    for i, fp in enumerate(frame_paths):
        fr = Image.open(fp).convert("RGBA")
        strip.alpha_composite(fr, (i * 64, 0))
    strip.save(dest, optimize=True)

    # cleanup tmp frames
    for p in tmp.glob("*"):
        p.unlink()
    tmp.rmdir()


def process_batch(batch_name: str, copy_nodpi: bool = True) -> list[Path]:
    batch = STYLE / batch_name
    sources = batch / "sources"
    if not sources.is_dir():
        # legacy: sources live at batch root as *_source.png
        sources = batch
    processed = batch / "processed"
    processed.mkdir(parents=True, exist_ok=True)

    outputs: list[Path] = []
    src_files = sorted(sources.glob("*_source.png"))
    if not src_files:
        raise SystemExit(f"No *_source.png under {sources}")

    for src in src_files:
        stem = src.name.replace("_source.png", "")
        kind = classify(stem)
        dest = processed / f"{stem}.png"
        print(f"  [{kind:5}] {src.name} -> {dest.name}")
        if kind == "strip":
            process_strip(src, dest)
        else:
            process_icon(src, dest)
        outputs.append(dest)
        if copy_nodpi:
            nodpi = DRAWABLES / f"{stem}.png"
            Image.open(dest).save(nodpi, optimize=True)
            print(f"         -> {nodpi.relative_to(ROOT)}")

    # family review sheet
    if outputs:
        cols = min(4, len(outputs))
        rows = (len(outputs) + cols - 1) // cols
        cell_w, cell_h = 200, 80
        review = Image.new("RGB", (cols * cell_w + 20, rows * cell_h + 20), (23, 32, 25))
        for idx, out in enumerate(outputs):
            im = Image.open(out).convert("RGBA")
            r, c = divmod(idx, cols)
            x = 10 + c * cell_w + (cell_w - im.width) // 2
            y = 10 + r * cell_h + (cell_h - im.height) // 2
            review.paste(im, (x, y), im)
        review_path = batch / f"{batch_name.split('-')[1] if False else batch_name}-family-review.png"
        # shorter name: fN-family-review if batch has fN
        short = batch_name
        for token in batch_name.split("-"):
            if token.startswith("f") and token[1:].isdigit():
                short = token
                break
        review_path = batch / f"{short}-family-review.png"
        review.save(review_path)
        print(f"  review -> {review_path.relative_to(ROOT)}")

    return outputs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batch", help="batch folder name under artwork/style-production/")
    parser.add_argument("--no-nodpi", action="store_true", help="skip copying into drawable-nodpi")
    args = parser.parse_args()
    print(f"Processing {args.batch} …")
    outs = process_batch(args.batch, copy_nodpi=not args.no_nodpi)
    print(f"Done: {len(outs)} assets")


if __name__ == "__main__":
    main()
