#!/usr/bin/env python3
"""Build ambient idle strips for all seven Utilities (Animation Batch 07)."""
from __future__ import annotations

from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "animation-production" / "batch-07"
PROCESSED = SOURCE / "processed"
NEUTRAL = SOURCE / "neutral"
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
for directory in (PROCESSED, NEUTRAL, SOURCE / "selected"):
    directory.mkdir(parents=True, exist_ok=True)

ROLES = (
    "utility_block_generator",
    "utility_cache_depot",
    "utility_forge_workshop",
    "utility_purifier_totem",
    "utility_surveyor_station",
    "utility_reforge_anchor",
    "utility_salvage_yard",
)
LABELS = {
    "utility_block_generator": "BLOCK GENERATOR",
    "utility_cache_depot": "CACHE DEPOT",
    "utility_forge_workshop": "FORGE WORKSHOP",
    "utility_purifier_totem": "PURIFIER TOTEM",
    "utility_surveyor_station": "SURVEYOR STATION",
    "utility_reforge_anchor": "REFORGE ANCHOR",
    "utility_salvage_yard": "SALVAGE YARD",
}


def rgba(path: Path) -> Image.Image:
    return Image.open(path).convert("RGBA")


def accent_mask(role: str, arr: np.ndarray) -> np.ndarray:
    red, green, blue, alpha = [arr[:, :, index].astype(np.int16) for index in range(4)]
    opaque = alpha > 40
    if role == "utility_block_generator":
        return opaque & (red > 140) & (green > 40) & (green < 200) & (blue < 90) & (red > green) & (red > blue + 40)
    if role == "utility_cache_depot":
        return opaque & (blue > 90) & (green > 80) & (red < 120) & (blue > red + 15)
    if role == "utility_forge_workshop":
        return opaque & (red > 150) & (green > 40) & (green < 180) & (blue < 80) & (red > blue + 50)
    if role == "utility_purifier_totem":
        return opaque & (green > 150) & (blue > 150) & (red > 100) & (blue >= red - 10) & ((blue + green) > red + 40)
    if role == "utility_surveyor_station":
        return opaque & (blue > 140) & (green > 120) & (red < 200) & (blue > red + 10)
    if role == "utility_reforge_anchor":
        return opaque & (red > 60) & (blue > 70) & (green < 120) & (blue > green) & (red + blue > green + 40)
    if role == "utility_salvage_yard":
        warm = opaque & (red > 130) & (green > 40) & (blue < 100) & (red > blue + 30)
        spark = opaque & (red > 200) & (green > 180) & (blue > 150)
        return warm | spark
    return np.zeros(alpha.shape, dtype=bool)


def soften(mask: np.ndarray) -> np.ndarray:
    image = Image.fromarray((mask.astype(np.uint8) * 255)).filter(ImageFilter.MaxFilter(3))
    return np.array(image) > 0


def modulate_frame(neutral: Image.Image, mask: np.ndarray, mode: str) -> Image.Image:
    array = np.array(neutral).astype(np.float32)
    active = soften(mask)
    if not active.any():
        alpha = array[:, :, 3] > 40
        red, green, blue = array[:, :, 0], array[:, :, 1], array[:, :, 2]
        active = alpha & ((red + green + blue) > 280)
    if mode == "a":
        multiply, add, alpha_multiply = 0.55, -18.0, 0.85
    else:
        multiply, add, alpha_multiply = 1.35, 28.0, 1.0
    for channel in range(3):
        values = array[:, :, channel]
        values[active] = np.clip(values[active] * multiply + add, 0, 255)
        array[:, :, channel] = values
    alpha = array[:, :, 3]
    alpha[active] = np.clip(alpha[active] * alpha_multiply, 0, 255)
    array[:, :, 3] = alpha
    output = Image.fromarray(array.astype(np.uint8))
    if mode != "c":
        return output

    bloom_mask = Image.fromarray((active.astype(np.uint8) * 255)).filter(ImageFilter.GaussianBlur(1.2))
    bloom = np.array(output).astype(np.float32)
    weights = np.array(bloom_mask).astype(np.float32) / 255.0
    source = np.array(neutral)
    average = source[active][:, :3].mean(axis=0) if active.any() else np.array([255.0, 180.0, 80.0])
    for channel in range(3):
        bloom[:, :, channel] = np.clip(bloom[:, :, channel] + weights * average[channel] * 0.22, 0, 255)
    bloom[:, :, 3] = np.clip(bloom[:, :, 3] + weights * 40, 0, 255)
    base = np.array(neutral).astype(np.float32)
    keep = ~soften(mask)
    bloom[keep] = base[keep]
    bright = np.array(output).astype(np.float32)
    bloom[active] = bright[active]
    return Image.fromarray(bloom.astype(np.uint8))


def build_strip(role: str):
    current = rgba(DRAWABLES / f"{role}.png")
    if current.size == (192, 64):
        neutral = current.crop((64, 0, 128, 64))
    elif current.size == (64, 64):
        neutral = current
    else:
        raise RuntimeError(f"Unexpected size for {role}: {current.size}")
    neutral.save(NEUTRAL / f"{role}.png", optimize=True)
    mask = accent_mask(role, np.array(neutral))
    frame_a = modulate_frame(neutral, mask, "a")
    frame_c = modulate_frame(neutral, mask, "c")
    frame_a.save(PROCESSED / f"{role}_frame_a.png", optimize=True)
    neutral.save(PROCESSED / f"{role}_frame_b.png", optimize=True)
    frame_c.save(PROCESSED / f"{role}_frame_c.png", optimize=True)
    strip = Image.new("RGBA", (192, 64), (0, 0, 0, 0))
    for index, frame in enumerate((frame_a, neutral, frame_c)):
        strip.alpha_composite(frame, (index * 64, 0))
    strip.save(DRAWABLES / f"{role}.png", optimize=True)
    strip.save(PROCESSED / f"{role}_strip.png", optimize=True)
    return frame_a, neutral.copy(), frame_c


def review_contact(frames_by_role):
    cell_width, cell_height = 220, 260
    roles = list(frames_by_role)
    review = Image.new("RGB", (cell_width * 3, cell_height * len(roles)), "#172019")
    draw = ImageDraw.Draw(review)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 16)
        title_font = ImageFont.truetype("DejaVuSans-Bold.ttf", 18)
    except OSError:
        font = title_font = ImageFont.load_default()
    for row, role in enumerate(roles):
        for column, (label, frame) in enumerate(zip(("REST A", "NEUTRAL B", "ACTIVE C"), frames_by_role[role])):
            sprite = frame.resize((200, 200), Image.Resampling.NEAREST)
            x, y = column * cell_width + 10, row * cell_height + 28
            review.paste(sprite, (x, y), sprite)
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((column * cell_width + (cell_width - (box[2] - box[0])) / 2, row * cell_height + 232), label, fill="#e7efe9", font=font)
        draw.text((12, row * cell_height + 6), LABELS[role], fill="#bef44e", font=title_font)
    review.save(SOURCE / "utility-idle-frame-review.png")


def animated_review(frames_by_role):
    tile, label_height = 128, 30
    roles = list(frames_by_role)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 11)
    except OSError:
        font = ImageFont.load_default()
    row_one, row_two = roles[:4], roles[4:]
    frames = []
    for frame_index, duration in ((0, 500), (1, 280), (2, 420), (1, 280)):
        canvas = Image.new("RGB", (tile * 4, tile * 2 + label_height * 2), "#172019")
        draw = ImageDraw.Draw(canvas)
        for row_index, group in enumerate((row_one, row_two)):
            for index, role in enumerate(group):
                x = index * tile
                y = row_index * (tile + label_height)
                sprite = frames_by_role[role][frame_index].resize((tile, tile), Image.Resampling.NEAREST)
                canvas.paste(sprite, (x, y), sprite)
                label = LABELS[role].split()[0]
                box = draw.textbbox((0, 0), label, font=font)
                draw.text((x + (tile - (box[2] - box[0])) / 2, y + tile + 6), label, fill="#e7efe9", font=font)
        canvas.info["duration"] = duration
        frames.append(canvas)
    frames[0].save(
        SOURCE / "utility-idle-animation-review.gif",
        save_all=True,
        append_images=frames[1:],
        duration=[frame.info["duration"] for frame in frames],
        loop=0,
        optimize=False,
    )


def main() -> None:
    frames_by_role = {role: build_strip(role) for role in ROLES}
    review_contact(frames_by_role)
    animated_review(frames_by_role)
    for role in ROLES:
        print(DRAWABLES / f"{role}.png")


if __name__ == "__main__":
    main()
