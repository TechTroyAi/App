#!/usr/bin/env python3
"""v1.4.3 sprite recreation pipeline: fantasy x machinery, 2D top-view.

Turns generated concept renders in artwork/style-production/batch-33-v143/
into engine-ready sprites in app/src/main/res/drawable-nodpi/.

Static sprites  -> 64x64 RGBA
Animated strips -> 192x64 RGBA (three square frames, left to right)
"""

from __future__ import annotations

import sys
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


def publish(staged: Path, name: str) -> Path:
    target = DRAWABLE / name
    Image.open(staged).convert("RGBA").save(target, optimize=True)
    return target


if __name__ == "__main__":
    print(f"library helpers ready; source={SOURCE}")
