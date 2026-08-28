#!/usr/bin/env python3
"""Complete Animation Batch 06: add Ember flame + Beacon pulse firing strips."""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

SOURCE = ROOT / "artwork" / "animation-production" / "batch-06"
PROCESSED = SOURCE / "processed"
SELECTED = SOURCE / "selected"
NEUTRAL = SOURCE / "neutral"
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
for directory in (PROCESSED, SELECTED, NEUTRAL):
    directory.mkdir(parents=True, exist_ok=True)

NEW_ROLES = ("tower_ember_flame", "tower_beacon_pulse")
LABELS = {
    "tower_bolt_turret": "BOLT TURRET",
    "tower_frost_turret": "FROST TURRET",
    "tower_cannon_turret": "CANNON TURRET",
    "tower_ember_flame": "EMBER FLAME",
    "tower_beacon_pulse": "BEACON PULSE",
}


def to_rgba(path: Path) -> Image.Image:
    return Image.open(path).convert("RGBA")


def alpha_bbox(image: Image.Image):
    return image.getchannel("A").getbbox()


def ensure_neutral(role: str) -> Path:
    dest = NEUTRAL / f"{role}.png"
    current = to_rgba(DRAWABLES / f"{role}.png")
    if current.size == (192, 64):
        current = current.crop((64, 0, 128, 64))
    elif current.size != (64, 64):
        raise RuntimeError(f"Unexpected neutral size for {role}: {current.size}")
    current.save(dest, optimize=True)
    return dest


def process_pose(role: str, key: str) -> Image.Image:
    source = SOURCE / f"{role}_frame_{key}_source.png"
    selected = SELECTED / f"{role}_frame_{key}_selected.png"
    shutil.copyfile(source, selected)
    destination = PROCESSED / f"{role}_frame_{key}.png"
    isolate(selected, destination)
    remove_enclosed_neutral_background(destination)
    return to_rgba(destination)


def split_beacon(image: Image.Image):
    arr = np.array(image)
    red, green, blue, alpha = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2], arr[:, :, 3]
    energy = (alpha > 20) & (blue.astype(int) > red.astype(int) + 15) & (green.astype(int) > red.astype(int) + 5)
    metal = (alpha > 20) & (~energy)
    metal_arr = np.zeros_like(arr)
    energy_arr = np.zeros_like(arr)
    metal_arr[metal] = arr[metal]
    energy_arr[energy] = arr[energy]
    return Image.fromarray(metal_arr), Image.fromarray(energy_arr)


def align_beacon(frame: Image.Image, neutral: Image.Image, mode: str) -> Image.Image:
    neutral_metal, neutral_energy = split_beacon(neutral)
    _, frame_energy = split_beacon(frame)
    metal_box = alpha_bbox(neutral_metal) or alpha_bbox(neutral)
    frame_metal, _ = split_beacon(frame)
    frame_metal_box = alpha_bbox(frame_metal)
    energy_box = alpha_bbox(frame_energy) or alpha_bbox(frame)
    if metal_box is None:
        raise RuntimeError("Beacon neutral metal missing")

    if frame_metal_box is not None:
        neutral_extent = max(metal_box[2] - metal_box[0], metal_box[3] - metal_box[1])
        frame_extent = max(frame_metal_box[2] - frame_metal_box[0], frame_metal_box[3] - frame_metal_box[1])
        metal_scale = neutral_extent / max(1, frame_extent)
    else:
        metal_scale = 1.0

    energy_boost = 0.82 if mode == "a" else 1.20
    energy_scale = metal_scale * energy_boost

    if energy_box is None:
        return neutral.copy()

    crop = frame_energy.crop(energy_box)
    resized = crop.resize(
        (max(1, round(crop.width * energy_scale)), max(1, round(crop.height * energy_scale))),
        Image.Resampling.LANCZOS,
    )
    center_x = (metal_box[0] + metal_box[2]) / 2
    center_y = (metal_box[1] + metal_box[3]) / 2
    x = int(round(center_x - resized.width / 2))
    y = int(round(center_y - resized.height / 2))

    energy_layer = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    energy_layer.alpha_composite(resized, (x, y))
    arr = np.array(energy_layer)
    if mode == "a":
        arr[:, :, 3] = (arr[:, :, 3].astype(np.float32) * 0.72).astype(np.uint8)
        arr[:, :, 0] = (arr[:, :, 0].astype(np.float32) * 0.82).astype(np.uint8)
        arr[:, :, 1] = (arr[:, :, 1].astype(np.float32) * 0.88).astype(np.uint8)
        arr[:, :, 2] = (arr[:, :, 2].astype(np.float32) * 0.88).astype(np.uint8)
    else:
        for channel in range(3):
            arr[:, :, channel] = np.clip(arr[:, :, channel].astype(np.float32) * 1.10 + 10, 0, 255).astype(np.uint8)
    energy_layer = Image.fromarray(arr)

    layered = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    layered.alpha_composite(energy_layer, (0, 0))
    layered.alpha_composite(neutral_metal, (0, 0))
    # Restore the exact neutral core gem so the hub identity stays locked.
    gem = neutral_energy.crop((26, 26, 38, 38))
    layered.alpha_composite(gem, (26, 26))
    return layered


def split_ember(image: Image.Image):
    arr = np.array(image)
    red, green, blue, alpha = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2], arr[:, :, 3]
    flame = (alpha > 20) & (
        ((red.astype(int) > green.astype(int)) & (red.astype(int) > 80) & ((np.maximum(np.maximum(red, green), blue) - np.minimum(np.minimum(red, green), blue)) > 25))
        | ((red > 200) & (green > 170) & (blue > 80))
    )
    metal = (alpha > 20) & (~flame)
    metal_arr = np.zeros_like(arr)
    flame_arr = np.zeros_like(arr)
    metal_arr[metal] = arr[metal]
    flame_arr[flame] = arr[flame]
    return Image.fromarray(metal_arr), Image.fromarray(flame_arr)


def align_ember(frame: Image.Image, neutral: Image.Image, mode: str) -> Image.Image:
    neutral_metal, neutral_flame = split_ember(neutral)
    _, frame_flame = split_ember(frame)
    metal_box = alpha_bbox(neutral_metal) or (20, 50, 44, 62)
    flame_box = alpha_bbox(frame_flame) or alpha_bbox(frame)
    neutral_flame_box = alpha_bbox(neutral_flame) or (22, 10, 42, 52)
    if flame_box is None:
        return neutral.copy()

    neutral_height = max(1, neutral_flame_box[3] - neutral_flame_box[1])
    frame_height = max(1, flame_box[3] - flame_box[1])
    target_height = max(8, round(neutral_height * (0.70 if mode == "a" else 1.30)))
    scale = target_height / frame_height

    crop = frame_flame.crop(flame_box)
    resized = crop.resize(
        (max(1, round(crop.width * scale)), max(1, round(crop.height * scale))),
        Image.Resampling.LANCZOS,
    )
    neutral_width = max(1, neutral_flame_box[2] - neutral_flame_box[0])
    target_width = max(8, round(neutral_width * (0.78 if mode == "a" else 1.22)))
    if resized.width > 0 and abs(target_width / resized.width - 1.0) > 0.04:
        width_scale = target_width / resized.width
        resized = resized.resize(
            (max(1, round(resized.width * width_scale)), resized.height),
            Image.Resampling.LANCZOS,
        )

    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    collar_top = metal_box[1]
    center_x = (metal_box[0] + metal_box[2]) / 2
    x = int(round(center_x - resized.width / 2))
    y = int(round(collar_top - resized.height + 2))
    y = max(0, y)
    if y + resized.height > 64:
        overflow = y + resized.height - 64
        resized = resized.crop((0, overflow, resized.width, resized.height))
        y = 0
    out.alpha_composite(resized, (x, y))
    out.alpha_composite(neutral_metal, (0, 0))

    arr = np.array(out)
    flame_mask = (arr[:, :, 0] > arr[:, :, 2] + 10) & (arr[:, :, 3] > 20)
    if mode == "a":
        multipliers = (0.84, 0.87, 0.90)
        for channel, multiplier in enumerate(multipliers):
            values = arr[:, :, channel].astype(np.float32)
            values[flame_mask] *= multiplier
            arr[:, :, channel] = np.clip(values, 0, 255).astype(np.uint8)
    else:
        multipliers = (1.10, 1.07, 1.03)
        for channel, multiplier in enumerate(multipliers):
            values = arr[:, :, channel].astype(np.float32)
            values[flame_mask] = np.clip(values[flame_mask] * multiplier + 8, 0, 255)
            arr[:, :, channel] = values.astype(np.uint8)
    return Image.fromarray(arr)


def build_strip(role: str):
    neutral_path = ensure_neutral(role)
    neutral = to_rgba(neutral_path)
    frames: dict[str, Image.Image] = {}
    for key in ("a", "c"):
        raw = process_pose(role, key)
        if role == "tower_ember_flame":
            aligned = align_ember(raw, neutral, key)
        else:
            aligned = align_beacon(raw, neutral, key)
        destination = PROCESSED / f"{role}_frame_{key}.png"
        aligned.save(destination, optimize=True)
        frames[key] = aligned
    frames["b"] = neutral
    strip = Image.new("RGBA", (192, 64), (0, 0, 0, 0))
    for index, key in enumerate(("a", "b", "c")):
        strip.alpha_composite(frames[key], (index * 64, 0))
    strip.save(DRAWABLES / f"{role}.png", optimize=True)
    strip.save(PROCESSED / f"{role}_strip.png", optimize=True)
    return frames["a"], frames["b"], frames["c"]


def load_existing_strip(role: str):
    strip = to_rgba(DRAWABLES / f"{role}.png")
    if strip.size != (192, 64):
        raise RuntimeError(f"{role} is not a 3-frame strip: {strip.size}")
    return tuple(strip.crop((index * 64, 0, index * 64 + 64, 64)) for index in range(3))


def review_contact(frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]]) -> None:
    cell_width, cell_height = 256, 300
    roles = list(frames_by_role)
    review = Image.new("RGB", (cell_width * 3, cell_height * len(roles)), "#172019")
    draw = ImageDraw.Draw(review)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 18)
        title_font = ImageFont.truetype("DejaVuSans-Bold.ttf", 20)
    except OSError:
        font = ImageFont.load_default()
        title_font = font
    for row, role in enumerate(roles):
        for column, (label, frame) in enumerate(zip(("REST A", "NEUTRAL B", "FIRE C"), frames_by_role[role])):
            sprite = frame.resize((256, 256), Image.Resampling.NEAREST)
            x, y = column * cell_width, row * cell_height
            review.paste(sprite, (x, y), sprite)
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((x + (cell_width - (box[2] - box[0])) / 2, y + 258), label, fill="#e7efe9", font=font)
        draw.text((12, row * cell_height + 10), LABELS[role], fill="#bef44e", font=title_font)
    review.save(SOURCE / "tower-top-frame-review.png")


def animated_review(frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]]) -> None:
    tile, label_height = 176, 34
    roles = list(frames_by_role)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 13)
    except OSError:
        font = ImageFont.load_default()
    output_frames: list[Image.Image] = []
    for frame_index, duration in ((0, 650), (2, 130), (1, 160), (0, 420)):
        canvas = Image.new("RGB", (tile * len(roles), tile + label_height), "#172019")
        draw = ImageDraw.Draw(canvas)
        for index, role in enumerate(roles):
            x = index * tile
            sprite = frames_by_role[role][frame_index].resize((tile, tile), Image.Resampling.NEAREST)
            canvas.paste(sprite, (x, 0), sprite)
            label = LABELS[role]
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((x + (tile - (box[2] - box[0])) / 2, tile + 8), label, fill="#e7efe9", font=font)
        canvas.info["duration"] = duration
        output_frames.append(canvas)
    output_frames[0].save(
        SOURCE / "tower-top-firing-review.gif",
        save_all=True,
        append_images=output_frames[1:],
        duration=[frame.info["duration"] for frame in output_frames],
        loop=0,
        optimize=False,
    )


def main() -> None:
    frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]] = {}
    for role in NEW_ROLES:
        print("building", role)
        frames_by_role[role] = build_strip(role)
    for role in ("tower_bolt_turret", "tower_frost_turret", "tower_cannon_turret"):
        frames_by_role[role] = load_existing_strip(role)

    ordered = {
        "tower_bolt_turret": frames_by_role["tower_bolt_turret"],
        "tower_frost_turret": frames_by_role["tower_frost_turret"],
        "tower_cannon_turret": frames_by_role["tower_cannon_turret"],
        "tower_ember_flame": frames_by_role["tower_ember_flame"],
        "tower_beacon_pulse": frames_by_role["tower_beacon_pulse"],
    }
    review_contact(ordered)
    animated_review(ordered)
    for role in NEW_ROLES:
        print(DRAWABLES / f"{role}.png")


if __name__ == "__main__":
    main()
