#!/usr/bin/env python3
"""Render `artwork/clockcanvas-icon-mockup.png`: every launcher shape, size and context.

Not part of the app - a review sheet. The reason it is a script rather than a screenshot is
that the icon is generated: whenever `make_clockcanvas_master.py` or `make_clockcanvas_icons.py`
changes, re-running this shows what a launcher will actually draw (adaptive circle, squircle,
square), whether the mark survives 48 dp on a light wallpaper, and how the themed (monochrome)
layer behaves - three things that are invisible if you only look at the 1024 px master.

    python3 tools/make_clockcanvas_icons.py && python3 tools/make_clockcanvas_icon_mockup.py
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

MODULE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # <repo>/clockcanvas
RES = os.path.join(MODULE, "app", "src", "main", "res")
OUT = os.path.join(MODULE, "artwork", "clockcanvas-icon-mockup.png")
BG_LAYER = os.path.join(RES, "drawable-nodpi", "ic_launcher_background.png")
FG_LAYER = os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png")
MONO_LAYER = os.path.join(RES, "drawable-nodpi", "ic_launcher_monochrome.png")
LEGACY = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")
LEGACY_ROUND = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_round.png")
STORE = os.path.join(MODULE, "artwork", "clockcanvas-store-icon-512.png")

DARK_PLATE = (16, 13, 26)
LIGHT_PLATE = (226, 222, 236)
LABEL = (184, 174, 201)
MUTED = (129, 120, 149)


FONT = os.path.join(MODULE, "app", "src", "main", "assets", "fonts", "inter_variable.ttf")


def font(px: int, bold: bool = False) -> ImageFont.ImageFont:
    """The app's own typeface, so the sheet reads like the product rather than a debug dump."""
    try:
        return ImageFont.truetype(FONT, px)
    except Exception:
        return ImageFont.load_default()

def load(path: str) -> Image.Image:
    return Image.open(path).convert("RGBA")


def adaptive(shape: str, size: int) -> Image.Image:
    """Compose background + foreground the way a launcher does, then apply its mask."""
    image = Image.alpha_composite(load(BG_LAYER).resize((size, size), Image.LANCZOS),
                                  load(FG_LAYER).resize((size, size), Image.LANCZOS))
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    elif shape == "squircle":
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.30), fill=255)
    else:
        draw.rectangle((0, 0, size, size), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(image, (0, 0), mask)
    return out


def tinted_mono(plate: tuple, size: int) -> Image.Image:
    """The themed layer, painted in the launcher's own foreground colour on its background."""
    mask = load(MONO_LAYER).resize((size, size), Image.LANCZOS).split()[3]
    out = Image.new("RGBA", (size, size), plate + (255,))
    out.paste(Image.new("RGBA", (size, size),
                       (255, 255, 255, 255) if sum(plate) < 400 else (24, 18, 40, 255)),
              (0, 0), mask)
    return out


def card(size: int, image: Image.Image, label: str, plate=DARK_PLATE) -> Image.Image:
    pad = 14
    cell = Image.new("RGBA", (size + pad * 2, size + pad * 2 + 30), (0, 0, 0, 0))
    ImageDraw.Draw(cell).rounded_rectangle(
        (0, 0, size + pad * 2 - 1, size + pad * 2 + 29), radius=20,
        fill=plate + (255,), outline=(52, 39, 80, 255), width=1,
    )
    cell.alpha_composite(image, (pad, pad))
    ImageDraw.Draw(cell).text((pad, size + pad + 4), label, fill=LABEL, font=font(14))
    return cell


def heading(canvas: Image.Image, y: int, text: str, sub: str = "") -> int:
    draw = ImageDraw.Draw(canvas)
    draw.text((48, y), text, fill=(245, 243, 255, 255), font=font(23, bold=True))
    if sub:
        draw.text((48, y + 32), sub, fill=MUTED, font=font(15))
        return y + 66
    return y + 38


def place(canvas: Image.Image, x: int, y: int, items, gap: int = 14) -> None:
    for item in items:
        canvas.alpha_composite(item, (x, y))
        x += item.width + gap


def home_screen(width: int, height: int) -> Image.Image:
    """A dark wallpaper with the icon in a dock, next to a clock widget of the same palette."""
    panel = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(panel)
    draw.rounded_rectangle((0, 0, width - 1, height - 1), radius=26, fill=(14, 11, 22, 255),
                           outline=(52, 39, 80, 255))
    glow = Image.new("L", (width, height), 0)
    ImageDraw.Draw(glow).ellipse((-160, -220, width - 240, height - 120), fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    panel.alpha_composite(Image.composite(Image.new("RGBA", (width, height), (48, 26, 86, 255)),
                                         Image.new("RGBA", (width, height), (0, 0, 0, 0)), glow))
    draw = ImageDraw.Draw(panel)

    # A widget cell, so the sheet shows icon and product together.
    wx, wy, ww, wh = 36, 28, 420, 132
    draw.rounded_rectangle((wx, wy, wx + ww, wy + wh), radius=22, fill=(22, 18, 34, 235),
                           outline=(52, 39, 80, 255))
    draw.text((wx + 22, wy + 16), "10:10", fill=(245, 243, 255, 255), font=font(34, bold=True))
    draw.text((wx + 22, wy + 60), "Monday, 12 May", fill=LABEL, font=font(16))
    draw.text((wx + 22, wy + 86), "media wall \u00b7 3 in reel", fill=MUTED, font=font(14))
    # The reel, as the widget would show it: a frame plus two queued thumbnails.
    for index in range(3):
        tx = wx + ww - 40 - (3 - index) * 44
        draw.rounded_rectangle((tx, wy + 24, tx + 34, wy + 24 + 34), radius=8,
                               fill=(37, 28, 61, 255) if index else (124, 58, 237, 255),
                               outline=(52, 39, 80, 255))

    icon = adaptive("circle", 96)
    names = ["ClockCanvas", "Files", "Camera", "Music", "Mail", "Photos"]
    x = 36
    y = height - 118
    for index, name in enumerate(names):
        if index == 0:
            tile = icon
        else:
            tile = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
            td = ImageDraw.Draw(tile)
            td.rounded_rectangle((4, 4, 91, 91), radius=26, fill=(37, 28, 61, 255),
                                 outline=(52, 39, 80, 255))
            td.ellipse((30, 30, 65, 65), outline=MUTED + (255,), width=3)
        panel.alpha_composite(tile, (x, y))
        draw.text((x + 2, y + 102), name, fill=(245, 243, 255, 255) if index == 0 else LABEL,
                  font=font(15))
        x += 122
    return panel


def main() -> None:
    size = 200
    width = 1280
    canvas = Image.new("RGBA", (width, 1440), (11, 9, 18, 255))
    y = heading(canvas, 30, "ClockCanvas - launcher icon",
                "premium clock, black and violet. generated by tools/make_clockcanvas_master.py")

    y = heading(canvas, y + 8, "Adaptive layers, as a launcher masks them")
    place(canvas, 48, y, [
        card(size, adaptive("circle", size), "circle"),
        card(size, adaptive("squircle", size), "squircle"),
        card(size, adaptive("square", size), "square"),
        card(size, load(LEGACY).resize((size, size), Image.LANCZOS), "legacy square"),
        card(size, load(LEGACY_ROUND).resize((size, size), Image.LANCZOS), "legacy round"),
    ])
    y += size + 66

    y = heading(canvas, y, "Small sizes - 192 down to 48 dp")
    strip = Image.new("RGBA", (width - 96, 210), (0, 0, 0, 0))
    x = 0
    for dim in (192, 144, 96, 72, 48):
        tile = load(LEGACY).resize((dim, dim), Image.LANCZOS)
        strip.alpha_composite(tile, (x, 100 - dim // 2))
        ImageDraw.Draw(strip).text((x + 2, 178), "%d px" % dim, fill=MUTED)
        x += dim + 40
    canvas.alpha_composite(strip, (48, y))
    # The same mark on a light wallpaper: where a dark icon usually disappears.
    light = card(120, adaptive("circle", 120), "on a light wallpaper", LIGHT_PLATE)
    canvas.alpha_composite(light, (width - 48 - light.width, y + 14))
    light_mono = card(120, tinted_mono(LIGHT_PLATE, 120), "themed, light", LIGHT_PLATE)
    canvas.alpha_composite(light_mono, (width - 48 - light.width - light_mono.width - 14, y + 14))
    y += 232

    y = heading(canvas, y, "Themed icon (Android 13 monochrome layer) and store art")
    place(canvas, 48, y, [
        card(size, tinted_mono(DARK_PLATE, size), "themed, dark"),
        card(size, load(STORE).resize((size, size), Image.LANCZOS), "store 512"),
        card(size, Image.alpha_composite(
            Image.new("RGBA", (size, size), (255, 255, 255, 255)),
            load(FG_LAYER).resize((size, size), Image.LANCZOS)), "foreground only, on white"),
    ])
    y += size + 66

    y = heading(canvas, y, "In context")
    canvas.alpha_composite(home_screen(width - 96, 300), (48, y))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    canvas.save(OUT, "PNG", optimize=True)
    print("wrote %s (%dx%d, %s bytes)" % (os.path.relpath(OUT, MODULE), canvas.width, canvas.height,
                                           f"{os.path.getsize(OUT):,}"))


if __name__ == "__main__":
    main()
