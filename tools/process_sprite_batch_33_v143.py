#!/usr/bin/env python3
"""v1.4.3 sprite recreation pipeline: fantasy x machinery, 2D top-view.

Turns generated concept renders in artwork/style-production/batch-33-v143/
into engine-ready sprites in app/src/main/res/drawable-nodpi/.

Static sprites  -> 64x64 RGBA
Animated strips -> 192x64 RGBA (three square frames, left to right)
"""

from __future__ import annotations

import sys
from collections import deque
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance

sys.path.insert(0, str(Path(__file__).resolve().parent))
from process_sprite_prototypes import isolate, remove_enclosed_neutral_background  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "style-production" / "batch-33-v143"
STAGE = SOURCE / "processed"
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
FRAME = 64


def clean(source: Path, destination: Path) -> Path:
    """Flood-fill the neutral backdrop away and normalise to a 64px sprite."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    isolate(source, destination)
    remove_enclosed_neutral_background(destination)
    return destination


def _tint(image: Image.Image, factor: float) -> Image.Image:
    rgb = image.convert("RGB")
    rgb = ImageEnhance.Brightness(rgb).enhance(factor)
    out = rgb.convert("RGBA")
    out.putalpha(image.getchannel("A"))
    return out


def _scaled(image: Image.Image, factor: float, dx: int = 0, dy: int = 0) -> Image.Image:
    size = max(1, round(FRAME * factor))
    body = image.resize((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (FRAME, FRAME), (0, 0, 0, 0))
    canvas.alpha_composite(body, ((FRAME - size) // 2 + dx, (FRAME - size) // 2 + dy))
    return canvas


def build_strip(frames: list[Image.Image], destination: Path) -> Path:
    strip = Image.new("RGBA", (FRAME * len(frames), FRAME), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        strip.alpha_composite(frame.convert("RGBA"), (FRAME * index, 0))
    destination.parent.mkdir(parents=True, exist_ok=True)
    strip.save(destination, optimize=True)
    return destination


def turret_strip(base_sprite: Path, destination: Path) -> Path:
    """Idle / charging / firing recoil cycle from a single turret render."""
    art = Image.open(base_sprite).convert("RGBA")
    idle = _scaled(art, 1.0)
    charge = _tint(_scaled(art, 1.03), 1.14)
    fire = _tint(_scaled(art, 0.97, dx=-2), 1.30)
    return build_strip([idle, charge, fire], destination)


def travel_strip(base_sprite: Path, destination: Path) -> Path:
    """Projectile flight cycle: pulse of scale and glow."""
    art = Image.open(base_sprite).convert("RGBA")
    frames = [
        _scaled(art, 0.94),
        _tint(_scaled(art, 1.0), 1.12),
        _tint(_scaled(art, 0.97), 1.22),
    ]
    return build_strip(frames, destination)


def burst_strip(base_sprite: Path, destination: Path) -> Path:
    """Impact burst: flash, expand, fade."""
    art = Image.open(base_sprite).convert("RGBA")
    frames = []
    for factor, bright, alpha in ((0.62, 1.35, 1.0), (0.92, 1.15, 0.85), (1.0, 0.95, 0.45)):
        frame = _tint(_scaled(art, factor), bright)
        channel = frame.getchannel("A").point(lambda value: round(value * alpha))
        frame.putalpha(channel)
        frames.append(frame)
    return build_strip(frames, destination)


def trap_strip(base_sprite: Path, destination: Path) -> Path:
    """Floor-trap cycle: armed and idle, charging, triggered flash."""
    art = Image.open(base_sprite).convert("RGBA")
    frames = [
        _scaled(art, 1.0),
        _tint(_scaled(art, 1.01), 1.12),
        _tint(_scaled(art, 1.04), 1.28),
    ]
    return build_strip(frames, destination)


def keyframe_strip(sources: list[Path], destination: Path, scratch: Path) -> Path:
    """Assemble hand-rendered keyframes into one strip.

    Each frame is isolated independently, so a shared bounding box is applied
    afterwards: without it the per-frame auto-crop makes a static floor plate
    jitter and breathe between frames.
    """
    scratch.mkdir(parents=True, exist_ok=True)
    cleaned = []
    for index, source in enumerate(sources):
        staged = scratch / f"{destination.stem}_f{index}.png"
        clean(source, staged)
        cleaned.append(Image.open(staged).convert("RGBA"))

    boxes = [image.getchannel("A").getbbox() for image in cleaned]
    left = min(box[0] for box in boxes)
    top = min(box[1] for box in boxes)
    right = max(box[2] for box in boxes)
    bottom = max(box[3] for box in boxes)

    frames = []
    for image in cleaned:
        crop = image.crop((left, top, right, bottom))
        scale = min(FRAME / crop.width, FRAME / crop.height)
        width = max(1, round(crop.width * scale))
        height = max(1, round(crop.height * scale))
        body = crop.resize((width, height), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (FRAME, FRAME), (0, 0, 0, 0))
        canvas.alpha_composite(body, ((FRAME - width) // 2, (FRAME - height) // 2))
        frames.append(canvas)
    return build_strip(frames, destination)


def idle_strip(base_sprite: Path, destination: Path) -> Path:
    """Standing-structure idle cycle: a soft breathing glow, no positional shift.

    Buildings must not slide sideways like a recoiling turret, so this keeps the
    footprint fixed and only varies brightness and a hair of scale.
    """
    art = Image.open(base_sprite).convert("RGBA")
    frames = [
        _scaled(art, 1.0),
        _tint(_scaled(art, 1.01), 1.10),
        _tint(_scaled(art, 1.0), 1.18),
    ]
    return build_strip(frames, destination)


def tile(source: Path, destination: Path) -> Path:
    """Normalise a full-bleed overlay texture to a 64px tile.

    Corruption overlays cover the whole ground tile, so the flood-fill isolate
    used for objects must be skipped here: it would treat the texture's own
    pale pixels as backdrop and crop the tile apart.
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    image = Image.open(source).convert("RGBA")
    side = min(image.width, image.height)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    square = image.crop((left, top, left + side, top + side))
    square.resize((FRAME, FRAME), Image.Resampling.LANCZOS).save(destination, optimize=True)
    return destination


def walk_strip(base_sprite: Path, destination: Path) -> Path:
    """Enemy walk cycle.

    GameView plays enemy strips as 0,1,2,1 while moving and holds frame 2 on
    death, so frame 1 is a squashed forward stride and frame 2 is a dimmer
    stretched pose that also reads as a collapse.
    """
    art = Image.open(base_sprite).convert("RGBA")

    def shape(sx: float, sy: float, dx: int = 0, dy: int = 0, bright: float = 1.0) -> Image.Image:
        width = max(1, round(FRAME * sx))
        height = max(1, round(FRAME * sy))
        body = art.resize((width, height), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (FRAME, FRAME), (0, 0, 0, 0))
        canvas.alpha_composite(body, ((FRAME - width) // 2 + dx, (FRAME - height) // 2 + dy))
        return _tint(canvas, bright) if bright != 1.0 else canvas

    frames = [shape(1.0, 1.0), shape(1.03, 0.96, 1, 1, 1.06), shape(0.96, 1.03, -1, 0, 0.90)]
    return build_strip(frames, destination)


def clean_matched(source: Path, destination: Path, tolerance: int = 26, target: int = 58) -> Path:
    """Isolate art whose own palette contains bright neutrals.

    `clean()` strips any bright neutral pixel reachable from the border, which
    destroys subjects that are themselves pale (white salt, bone, pale stone
    rune rims): the fill leaks inward or aborts and leaves a grey box. This
    instead samples the actual corner colour and removes only pixels close to
    it, so a pale subject survives while the flat backdrop goes.
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    image = Image.open(source).convert("RGBA")
    width, height = image.size
    pixels = image.load()
    backdrop = pixels[0, 0][:3]

    def matches(pixel) -> bool:
        return all(abs(pixel[index] - backdrop[index]) <= tolerance for index in range(3))

    seen = bytearray(width * height)
    queue: deque[tuple[int, int]] = deque()

    def push(x: int, y: int) -> None:
        index = y * width + x
        if not seen[index] and matches(pixels[x, y]):
            seen[index] = 1
            queue.append((x, y))

    for x in range(width):
        push(x, 0)
        push(x, height - 1)
    for y in range(height):
        push(0, y)
        push(width - 1, y)

    while queue:
        x, y = queue.popleft()
        if x > 0:
            push(x - 1, y)
        if x + 1 < width:
            push(x + 1, y)
        if y > 0:
            push(x, y - 1)
        if y + 1 < height:
            push(x, y + 1)

    for y in range(height):
        for x in range(width):
            if seen[y * width + x]:
                red, green, blue, _ = pixels[x, y]
                pixels[x, y] = (red, green, blue, 0)

    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        raise RuntimeError(f"No foreground found in {source}")
    crop = image.crop(bounds)
    scale = min(target / crop.width, target / crop.height)
    body_width = max(1, round(crop.width * scale))
    body_height = max(1, round(crop.height * scale))
    body = crop.resize((body_width, body_height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (FRAME, FRAME), (0, 0, 0, 0))
    canvas.alpha_composite(body, ((FRAME - body_width) // 2, (FRAME - body_height) // 2))
    canvas.save(destination, optimize=True)
    return destination


def publish(staged: Path, name: str) -> Path:
    target = DRAWABLE / name
    Image.open(staged).convert("RGBA").save(target, optimize=True)
    return target


if __name__ == "__main__":
    print(f"library helpers ready; source={SOURCE}")
