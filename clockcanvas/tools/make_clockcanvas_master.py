#!/usr/bin/env python3
"""Draw ClockCanvas' master launcher artwork: `artwork/clockcanvas-icon-source.png`.

Why a generator and not a dropped-in PNG: `make_clockcanvas_icons.py` reuses this one file for
every output - the adaptive foreground (transparent, so the launcher's own shape cannot reveal
a square), the monochrome themed layer, and the legacy mipmaps with their own gradient field.
An artwork with an opaque background therefore shows up as a coloured tile *inside* the icon,
so the master must be authored with real alpha. Drawing it also means the exact app palette
(#09070F field, #7C3AED/#C084FC violet, #F5F3FF hands) and 1-pixel-crisp geometry at 48 dp,
which a photo-like render loses as soon as it is downsampled.

The composition is the classic dial pose: hands at ten past ten, a violet ring that brightens
toward the top, twelve low-contrast ticks, and a glow behind the ring so the mark survives a
light wallpaper. All sizes are fractions of the canvas, so `--size` renders any resolution.

    python3 tools/make_clockcanvas_master.py            # 1024 master
    python3 tools/make_clockcanvas_master.py --compare  # also write both variants side by side
"""
from __future__ import annotations

import argparse
import math
import os

from PIL import Image, ImageDraw, ImageFilter

MODULE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # <repo>/clockcanvas
OUT = os.path.join(MODULE, "artwork", "clockcanvas-icon-source.png")

# The palette the app itself uses, so the icon cannot drift from the UI.
FIELD = (9, 7, 15)          # #09070F
VIOLET = (124, 58, 237)     # #7C3AED
BRIGHT = (168, 85, 247)     # #A855F7
LAVENDER = (192, 132, 252)  # #C084FC
IVORY = (245, 243, 255)     # #F5F3FF

# The dial radius as a fraction of the canvas. Everything on the face is expressed against
# this, so `--size` scales the whole mark and nothing needs re-tuning per resolution.
DIAL = 0.40


def vertical_gradient(size: int, top, bottom) -> Image.Image:
    out = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(out)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line(
            [(0, y), (size, y)],
            fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,),
        )
    return out


def ring(size: int, box, width: int, top, bottom) -> Image.Image:
    """An annulus painted with a vertical gradient: bright at the top, deep at the bottom."""
    gradient = vertical_gradient(size, top, bottom)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse(box, outline=255, width=width)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(gradient, (0, 0), mask)
    return out


def hand(
    size: int,
    centre,
    angle_deg: float,
    reach: float,
    width: int,
    colour,
    tail: float = 0.0,
) -> Image.Image:
    """One hand, drawn as a rounded line. `reach` and `tail` are fractions of the dial radius."""
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(out)
    angle = math.radians(angle_deg - 90.0)  # 0 deg = 12 o'clock
    radius = size * DIAL * reach
    back = size * DIAL * tail
    draw.line(
        [
            (centre[0] - math.cos(angle) * back, centre[1] - math.sin(angle) * back),
            (centre[0] + math.cos(angle) * radius, centre[1] + math.sin(angle) * radius),
        ],
        fill=colour,
        width=width,
    )
    # Round the tip: `junction` only affects polylines, so cap it by hand.
    tip = (centre[0] + math.cos(angle) * radius, centre[1] + math.sin(angle) * radius)
    r = width / 2.0
    draw.ellipse((tip[0] - r, tip[1] - r, tip[0] + r, tip[1] + r), fill=colour)
    return out


def draw_master(size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    cx = cy = size / 2.0
    radius = size * DIAL             # ~27% margin: safe under any launcher mask
    ring_width = max(2, int(size * 0.028))

    # Glow first, so it reads as light rather than a shape.
    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse(
        (cx - radius * 1.18, cy - radius * 1.18, cx + radius * 1.18, cy + radius * 1.18),
        fill=190,
    )
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.045))
    canvas.paste(Image.new("RGBA", (size, size), VIOLET + (255,)), (0, 0), glow)

    # The dial: a dark disc so the ticks and hands have something to sit on.
    disc = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse(
        (cx - radius, cy - radius, cx + radius, cy + radius),
        fill=FIELD + (255,),
        outline=(29, 23, 48, 255),
        width=max(1, int(size * 0.006)),
    )
    canvas.alpha_composite(disc)

    # Twelve ticks, quarter hours a touch longer - the only "numerals" a 48 dp icon can afford.
    ticks = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tick_draw = ImageDraw.Draw(ticks)
    inner_r = radius * 0.78
    for index in range(12):
        angle = math.radians(index * 30 - 90)
        long_tick = index % 3 == 0
        outer_r = radius * (0.90 if long_tick else 0.86)
        width = max(1, int(size * (0.0075 if long_tick else 0.005)))
        tick_draw.line(
            [
                (cx + math.cos(angle) * inner_r, cy + math.sin(angle) * inner_r),
                (cx + math.cos(angle) * outer_r, cy + math.sin(angle) * outer_r),
            ],
            fill=(IVORY if long_tick else (184, 174, 201)) + ((200,) if long_tick else (110,)),
            width=width,
        )
    canvas.alpha_composite(ticks)

    canvas.alpha_composite(ring(size, (cx - radius, cy - radius, cx + radius, cy + radius), ring_width, LAVENDER, VIOLET))

    # A highlight along the upper inside edge: what makes a ring look machined, not flat.
    highlight = Image.new("L", (size, size), 0)
    ImageDraw.Draw(highlight).arc(
        (cx - radius, cy - radius, cx + radius, cy + radius), 200, 340, fill=255, width=max(1, ring_width // 3)
    )
    highlight = highlight.filter(ImageFilter.GaussianBlur(size * 0.004))
    canvas.alpha_composite(Image.composite(
        Image.new("RGBA", (size, size), (255, 255, 255, 165)),
        Image.new("RGBA", (size, size), (0, 0, 0, 0)),
        highlight,
    ))

    # Ten past ten.
    canvas.alpha_composite(hand(size, (cx, cy), 300, 0.52, max(3, int(size * 0.026)), IVORY + (255,), tail=0.16))
    canvas.alpha_composite(hand(size, (cx, cy), 60, 0.80, max(2, int(size * 0.017)), IVORY + (255,), tail=0.18))

    # Pivot: violet core, ivory ring, so the hands read as attached at any size.
    pivot = max(3, int(size * 0.026))
    ImageDraw.Draw(canvas).ellipse((cx - pivot, cy - pivot, cx + pivot, cy + pivot), fill=BRIGHT + (255,))
    ImageDraw.Draw(canvas).ellipse(
        (cx - pivot * 0.42, cy - pivot * 0.42, cx + pivot * 0.42, cy + pivot * 0.42), fill=IVORY + (255,)
    )
    return canvas


def key_render(path: str, size: int) -> Image.Image:
    """Second variant: an AI render with its dark field keyed to alpha, for comparison."""
    image = Image.open(path).convert("RGB")
    side = min(image.width, image.height)
    image = image.crop(((image.width - side) // 2, (image.height - side) // 2,
                        (image.width + side) // 2, (image.height + side) // 2)).resize(
        (size, size), Image.LANCZOS
    )
    alpha = image.convert("L").point(lambda p: 0 if p < 22 else max(0, min(255, int((p - 22) * 2.4))))
    out = image.convert("RGBA")
    out.putalpha(alpha)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int, default=1024)
    parser.add_argument("--compare", action="store_true", help="write both variants side by side")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    master = draw_master(args.size)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    master.save(args.out, "PNG", optimize=True)
    print(f"wrote {os.path.relpath(args.out, os.path.dirname(MODULE))} ({master.width}px, "
          f"{os.path.getsize(args.out):,} bytes)")

    if args.compare:
        candidates = os.path.join(MODULE, "artwork", "candidates")
        tiles = [("drawn master", master)]
        for name in sorted(os.listdir(candidates)) if os.path.isdir(candidates) else []:
            if name.endswith(".png"):
                tiles.append(("keyed " + name, key_render(os.path.join(candidates, name), args.size)))
        side = args.size
        sheet = Image.new("RGBA", (side * len(tiles) + 24 * (len(tiles) + 1), side + 84), (11, 9, 18, 255))
        field = vertical_gradient(side, (37, 28, 61), FIELD)
        x = 24
        for label, tile in tiles:
            composed = Image.new("RGBA", (side, side), (0, 0, 0, 0))
            composed.paste(field, (0, 0))
            composed.alpha_composite(tile.resize((side, side), Image.LANCZOS), (0, 0))
            sheet.alpha_composite(composed, (x, 60))
            ImageDraw.Draw(sheet).text((x, 24), label, fill=(184, 174, 201, 255))
            x += side + 24
        preview = os.path.join(MODULE, "artwork", "clockcanvas-icon-variants.png")
        sheet.save(preview, "PNG", optimize=True)
        print(f"wrote {os.path.relpath(preview, os.path.dirname(MODULE))} ({sheet.width}x{sheet.height})")


if __name__ == "__main__":
    main()
