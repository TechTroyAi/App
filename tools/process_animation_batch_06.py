#!/usr/bin/env python3
"""Build firing strips for the first three tower top layers."""

from __future__ import annotations

import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "animation-production" / "batch-06"
PROCESSED = SOURCE / "processed"
SELECTED = SOURCE / "selected"
NEUTRAL = SOURCE / "neutral"
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
for directory in (PROCESSED, SELECTED, NEUTRAL):
    directory.mkdir(parents=True, exist_ok=True)

ROLES = ("tower_bolt_turret", "tower_frost_turret", "tower_cannon_turret")
LABELS = {
    "tower_bolt_turret": "BOLT TURRET",
    "tower_frost_turret": "FROST TURRET",
    "tower_cannon_turret": "CANNON TURRET",
}
ANCHOR_OFFSETS = {("tower_cannon_turret", "a"): (1, 0)}


def normalize_and_align(frame: Path, neutral: Path, role: str, key: str) -> None:
    moving = Image.open(frame).convert("RGBA")
    reference = Image.open(neutral).convert("RGBA")
    moving_box = moving.getchannel("A").getbbox()
    reference_box = reference.getchannel("A").getbbox()
    if moving_box is None or reference_box is None:
        raise RuntimeError(f"Missing alpha bounds for {frame} or {neutral}")

    # Width preserves the footprint when the silhouette is stable. Cannon barrel
    # travel intentionally changes width, so its fixed circular mount is scaled
    # from the unchanged vertical extent instead.
    axis = 1 if role == "tower_cannon_turret" else 0
    moving_extent = moving_box[2 + axis] - moving_box[axis]
    reference_extent = reference_box[2 + axis] - reference_box[axis]
    scale = reference_extent / moving_extent
    cropped = moving.crop(moving_box)
    resized = cropped.resize(
        (max(1, round(cropped.width * scale)), max(1, round(cropped.height * scale))),
        Image.Resampling.LANCZOS,
    )
    aligned = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    offset_x, offset_y = ANCHOR_OFFSETS.get((role, key), (0, 0))
    x = round((reference_box[0] + reference_box[2] - resized.width) / 2) + offset_x
    y = reference_box[3] - resized.height + offset_y
    aligned.alpha_composite(resized, (x, y))
    aligned.save(frame, optimize=True)


def build_strip(role: str) -> tuple[Image.Image, Image.Image, Image.Image]:
    neutral_path = NEUTRAL / f"{role}.png"
    if not neutral_path.exists():
        current = Image.open(DRAWABLES / f"{role}.png").convert("RGBA")
        if current.size != (64, 64):
            raise RuntimeError(f"Cannot recover neutral frame for {role} from {current.size}")
        current.save(neutral_path, optimize=True)

    frames: dict[str, Image.Image] = {}
    for key in ("a", "c"):
        selected = SELECTED / f"{role}_frame_{key}_selected.png"
        shutil.copyfile(SOURCE / f"{role}_frame_{key}_source.png", selected)
        destination = PROCESSED / f"{role}_frame_{key}.png"
        isolate(selected, destination)
        remove_enclosed_neutral_background(destination)
        normalize_and_align(destination, neutral_path, role, key)
        frames[key] = Image.open(destination).convert("RGBA").copy()

    frames["b"] = Image.open(neutral_path).convert("RGBA")
    strip = Image.new("RGBA", (192, 64), (0, 0, 0, 0))
    for index, key in enumerate(("a", "b", "c")):
        strip.alpha_composite(frames[key], (index * 64, 0))
    strip.save(DRAWABLES / f"{role}.png", optimize=True)
    strip.save(PROCESSED / f"{role}_strip.png", optimize=True)
    return frames["a"], frames["b"], frames["c"]


def review_contact(frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]]) -> None:
    cell_width, cell_height = 256, 300
    review = Image.new("RGB", (cell_width * 3, cell_height * len(frames_by_role)), "#172019")
    draw = ImageDraw.Draw(review)
    font = ImageFont.truetype("DejaVuSans-Bold.ttf", 18)
    title_font = ImageFont.truetype("DejaVuSans-Bold.ttf", 20)
    for row, (role, frames) in enumerate(frames_by_role.items()):
        for column, (label, frame) in enumerate(zip(("REST A", "NEUTRAL B", "FIRE C"), frames)):
            sprite = frame.resize((256, 256), Image.Resampling.NEAREST)
            x, y = column * cell_width, row * cell_height
            review.paste(sprite, (x, y), sprite)
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((x + (cell_width - (box[2] - box[0])) / 2, y + 258), label, fill="#e7efe9", font=font)
        draw.text((12, row * cell_height + 10), LABELS[role], fill="#bef44e", font=title_font)
    review.save(SOURCE / "tower-top-frame-review.png")


def animated_review(frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]]) -> None:
    tile, label_height = 256, 34
    output_frames: list[Image.Image] = []
    font = ImageFont.truetype("DejaVuSans-Bold.ttf", 18)
    roles = list(frames_by_role)
    # Runtime sits on A, jumps to C when firing, then recovers through B.
    for frame_index, duration in ((0, 650), (2, 130), (1, 160), (0, 420)):
        canvas = Image.new("RGB", (tile * 3, tile + label_height), "#172019")
        draw = ImageDraw.Draw(canvas)
        for index, role in enumerate(roles):
            x = index * tile
            sprite = frames_by_role[role][frame_index].resize((tile, tile), Image.Resampling.NEAREST)
            canvas.paste(sprite, (x, 0), sprite)
            label = LABELS[role]
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((x + (tile - (box[2] - box[0])) / 2, tile + 5), label, fill="#e7efe9", font=font)
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
    frames_by_role = {role: build_strip(role) for role in ROLES}
    review_contact(frames_by_role)
    animated_review(frames_by_role)
    for role in ROLES:
        print(DRAWABLES / f"{role}.png")


if __name__ == "__main__":
    main()
