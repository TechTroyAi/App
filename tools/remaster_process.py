#!/usr/bin/env python3
"""Slice AI sheets into exact code-friendly sprites for the 1.4.3 remaster.

Produces game-ready files that satisfy SpriteCatalog.loadStrip():
  - strips: 384x128, three equal 128x128 square frames
  - icons:  128x128
  - transparent background (edge-flood white removal + enclosed-white cleanup)
  - uniform scale across the 3 frames of one sheet; each frame centred on its
    own ink bbox so the rotation pivot (frame centre) never drifts

Usage:
    python3 tools/remaster_process.py icon  <in.png> <out.png>
    python3 tools/remaster_process.py strip <in.png> <out.png>
"""

from __future__ import annotations

import sys
from collections import deque
from pathlib import Path

from PIL import Image
import numpy as np

CELL = 128
STRIP_EXTENT = 112   # max ink extent inside a strip frame
ICON_EXTENT = 116


def is_removable(px) -> bool:
    r, g, b = px[:3]
    return min(r, g, b) >= 145 and max(r, g, b) - min(r, g, b) <= 28


def kill_background(img: Image.Image) -> Image.Image:
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load()
    bg = bytearray(w * h)
    q: deque[tuple[int, int]] = deque()

    def enqueue(x: int, y: int) -> None:
        i = y * w + x
        if not bg[i] and is_removable(px[x, y]):
            bg[i] = 1
            q.append((x, y))

    for x in range(w):
        enqueue(x, 0)
        enqueue(x, h - 1)
    for y in range(h):
        enqueue(0, y)
        enqueue(w - 1, y)
    while q:
        x, y = q.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                enqueue(nx, ny)
    for y in range(h):
        for x in range(w):
            i = y * w + x
            # reachable backdrop OR enclosed white pocket -> transparent
            if bg[i] or is_removable(px[x, y]):
                r, g, b, _ = px[x, y]
                px[x, y] = (r, g, b, 0)
    return img


def kill_enclosed_white(path: Path) -> None:
    img = Image.open(path).convert("RGBA")
    data = []
    for r, g, b, a in img.getdata():
        if a and min(r, g, b) >= 150 and max(r, g, b) - min(r, g, b) <= 34:
            data.append((r, g, b, 0))
        else:
            data.append((r, g, b, a))
    img.putdata(data)
    img.save(path, optimize=True)


def cell_bounds(alpha: np.ndarray):
    ys, xs = np.nonzero(alpha > 8)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def clean_edges(cell: Image.Image) -> Image.Image:
    """Zero a thin outer ring so AI-drawn panel divider lines never count as ink."""
    arr = np.array(cell.convert("RGBA"))
    m = max(3, round(min(arr.shape[0], arr.shape[1]) * 0.01))
    arr[:m, :, 3] = 0
    arr[-m:, :, 3] = 0
    arr[:, :m, 3] = 0
    arr[:, -m:, 3] = 0
    return Image.fromarray(arr, "RGBA")


def fit_cell(cell: Image.Image, scale: float) -> Image.Image:
    a = np.array(cell.convert("RGBA"))[..., 3]
    bb = cell_bounds(a)
    if bb is None:
        return Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    x0, y0, x1, y1 = bb
    cropped = cell.crop(bb)
    tw = max(1, round(cropped.width * scale))
    th = max(1, round(cropped.height * scale))
    scaled = cropped.resize((tw, th), Image.Resampling.LANCZOS)
    # centre the ACTUAL post-resize ink (resize can erode thin bbox edges)
    sb = cell_bounds(np.array(scaled)[..., 3])
    canvas = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    if sb is None:
        return canvas
    sx0, sy0, sx1, sy1 = sb
    ox = (CELL - (sx1 - sx0 + 1)) // 2 - sx0
    oy = (CELL - (sy1 - sy0 + 1)) // 2 - sy0
    canvas.alpha_composite(scaled, (ox, oy))
    return canvas


def process_strip(src: Path, dst: Path) -> None:
    sheet = kill_background(Image.open(src))
    w, h = sheet.size
    # models sometimes draw a 2x3 grid instead of 1x3 -> keep the top row
    rows = max(1, round(h / (w / 3)))
    if rows > 1:
        sheet = sheet.crop((0, 0, w, h // rows))
        w, h = sheet.size
    cw = w // 3
    cells = [clean_edges(sheet.crop((i * cw, 0, (i + 1) * cw, h))) for i in range(3)]
    extents = []
    for c in cells:
        bb = cell_bounds(np.array(c.convert("RGBA"))[..., 3])
        if bb:
            extents.append(max(bb[2] - bb[0] + 1, bb[3] - bb[1] + 1))
    scale = STRIP_EXTENT / max(extents)
    strip = Image.new("RGBA", (CELL * 3, CELL), (0, 0, 0, 0))
    for i, c in enumerate(cells):
        strip.alpha_composite(fit_cell(c, scale), (i * CELL, 0))
    dst.parent.mkdir(parents=True, exist_ok=True)
    strip.save(dst, optimize=True)


def process_icon(src: Path, dst: Path) -> None:
    img = clean_edges(kill_background(Image.open(src)))
    a = np.array(img.convert("RGBA"))[..., 3]
    bb = cell_bounds(a)
    if bb is None:
        raise RuntimeError(f"no ink in {src}")
    cropped = img.crop(bb)
    scale = ICON_EXTENT / max(cropped.width, cropped.height)
    tw = max(1, round(cropped.width * scale))
    th = max(1, round(cropped.height * scale))
    scaled = cropped.resize((tw, th), Image.Resampling.LANCZOS)
    sb = cell_bounds(np.array(scaled)[..., 3])
    canvas = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    if sb is not None:
        sx0, sy0, sx1, sy1 = sb
        ox = (CELL - (sx1 - sx0 + 1)) // 2 - sx0
        oy = (CELL - (sy1 - sy0 + 1)) // 2 - sy0
        canvas.alpha_composite(scaled, (ox, oy))
    dst.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(dst, optimize=True)


def main() -> None:
    mode, src, dst = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    if mode == "strip":
        process_strip(src, dst)
    elif mode == "icon":
        process_icon(src, dst)
    else:
        raise SystemExit("mode must be icon|strip")
    out = Image.open(dst)
    print(f"{dst.name}: {out.size[0]}x{out.size[1]}")


if __name__ == "__main__":
    main()
