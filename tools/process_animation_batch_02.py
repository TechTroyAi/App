#!/usr/bin/env python3
"""Build anchored three-frame strips for Splitling and the first elite group."""

from __future__ import annotations

import colorsys
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from process_sprite_prototypes import isolate, remove_enclosed_neutral_background

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork" / "animation-production" / "batch-02"
PROCESSED = SOURCE / "processed"
SELECTED = SOURCE / "selected"
NEUTRAL = SOURCE / "neutral"
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
for directory in (PROCESSED, SELECTED, NEUTRAL):
    directory.mkdir(parents=True, exist_ok=True)

ROLES = ("splitling", "ironhide", "blink", "rootcaller")


def normalize_blink_phase(path: Path) -> None:
    """Restore the approved violet phase energy without changing lime eyes."""
    image = Image.open(path).convert("RGBA")
    corrected = []
    for red, green, blue, alpha in image.getdata():
        hue, saturation, value = colorsys.rgb_to_hsv(red / 255, green / 255, blue / 255)
        cyan_phase = alpha > 0 and 0.43 <= hue <= 0.57 and saturation >= 0.34 and value >= 0.24
        if cyan_phase:
            new_red, new_green, new_blue = colorsys.hsv_to_rgb(0.86, min(1.0, saturation * 1.03), value)
            corrected.append((round(new_red * 255), round(new_green * 255), round(new_blue * 255), alpha))
        else:
            corrected.append((red, green, blue, alpha))
    image.putdata(corrected)
    image.save(path)


def align_to_neutral(frame: Path, neutral: Path) -> None:
    moving = Image.open(frame).convert("RGBA")
    reference = Image.open(neutral).convert("RGBA")
    moving_box = moving.getchannel("A").getbbox()
    reference_box = reference.getchannel("A").getbbox()
    if moving_box is None or reference_box is None:
        raise RuntimeError(f"Missing alpha bounds for {frame} or {neutral}")
    shift_x = round((reference_box[0] + reference_box[2] - moving_box[0] - moving_box[2]) / 2)
    shift_y = reference_box[3] - moving_box[3]
    aligned = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    aligned.alpha_composite(moving, (shift_x, shift_y))
    aligned.save(frame, optimize=True)


def build_strip(role: str) -> tuple[Image.Image, Image.Image, Image.Image]:
    neutral_path = NEUTRAL / f"enemy_{role}.png"
    if not neutral_path.exists():
        current = Image.open(DRAWABLES / f"enemy_{role}.png").convert("RGBA")
        if current.size != (64, 64):
            raise RuntimeError(f"Cannot recover neutral frame for {role} from {current.size}")
        current.save(neutral_path, optimize=True)

    frames: dict[str, Image.Image] = {}
    for key in ("a", "c"):
        selected = SELECTED / f"enemy_{role}_frame_{key}_selected.png"
        shutil.copyfile(SOURCE / f"enemy_{role}_frame_{key}_source.png", selected)
        if role == "blink":
            normalize_blink_phase(selected)
        destination = PROCESSED / f"enemy_{role}_frame_{key}.png"
        isolate(selected, destination)
        remove_enclosed_neutral_background(destination)
        align_to_neutral(destination, neutral_path)
        frames[key] = Image.open(destination).convert("RGBA").copy()

    frames["b"] = Image.open(neutral_path).convert("RGBA")
    strip = Image.new("RGBA", (192, 64), (0, 0, 0, 0))
    for index, key in enumerate(("a", "b", "c")):
        strip.alpha_composite(frames[key], (index * 64, 0))
    strip.save(DRAWABLES / f"enemy_{role}.png", optimize=True)
    strip.save(PROCESSED / f"enemy_{role}_strip.png", optimize=True)
    return frames["a"], frames["b"], frames["c"]


def review_contact(frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]]) -> None:
    scale = 4
    cell_width, cell_height = 256, 300
    review = Image.new("RGB", (cell_width * 3, cell_height * len(frames_by_role)), "#172019")
    draw = ImageDraw.Draw(review)
    font = ImageFont.truetype("DejaVuSans-Bold.ttf", 18)
    title_font = ImageFont.truetype("DejaVuSans-Bold.ttf", 20)
    for row, (role, frames) in enumerate(frames_by_role.items()):
        for column, (label, frame) in enumerate(zip(("FRAME A", "NEUTRAL B", "FRAME C"), frames)):
            sprite = frame.resize((64 * scale, 64 * scale), Image.Resampling.NEAREST)
            x, y = column * cell_width, row * cell_height
            review.paste(sprite, (x, y), sprite)
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((x + (cell_width - (box[2] - box[0])) / 2, y + 258), label, fill="#e7efe9", font=font)
        draw.text((12, row * cell_height + 10), role.upper(), fill="#bef44e", font=title_font)
    review.save(SOURCE / "enemy-animation-frame-review.png")


def animated_review(frames_by_role: dict[str, tuple[Image.Image, Image.Image, Image.Image]]) -> None:
    tile, label_height = 256, 34
    sequence = (0, 1, 2, 1)
    output_frames: list[Image.Image] = []
    font = ImageFont.truetype("DejaVuSans-Bold.ttf", 18)
    roles = list(frames_by_role)
    for frame_index in sequence:
        canvas = Image.new("RGB", (tile * 2, (tile + label_height) * 2), "#172019")
        draw = ImageDraw.Draw(canvas)
        for index, role in enumerate(roles):
            column, row = index % 2, index // 2
            x, y = column * tile, row * (tile + label_height)
            sprite = frames_by_role[role][frame_index].resize((tile, tile), Image.Resampling.NEAREST)
            canvas.paste(sprite, (x, y), sprite)
            box = draw.textbbox((0, 0), role.upper(), font=font)
            draw.text((x + (tile - (box[2] - box[0])) / 2, y + tile + 5), role.upper(), fill="#e7efe9", font=font)
        output_frames.append(canvas)
    output_frames[0].save(
        SOURCE / "enemy-animation-review.gif",
        save_all=True,
        append_images=output_frames[1:],
        duration=145,
        loop=0,
        optimize=False,
    )


def main() -> None:
    frames_by_role = {role: build_strip(role) for role in ROLES}
    review_contact(frames_by_role)
    animated_review(frames_by_role)
    for role in ROLES:
        print(DRAWABLES / f"enemy_{role}.png")


if __name__ == "__main__":
    main()
