#!/usr/bin/env python3
"""Generate ClockCanvas launcher icons from the master artwork.

Produces, from `artwork/clockcanvas-icon-source.png`:

  * adaptive icon layers (res/drawable-nodpi/ic_launcher_foreground.png on a
    generated violet field, plus a monochrome layer so Android 13+ themed icons
    work), and res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
  * legacy square + round PNGs for every density bucket (the round ones are
    masked, not merely scaled, so they do not look like a cropped square)

Re-run this whenever the artwork changes:  python3 tools/make_clockcanvas_icons.py
"""
from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageOps

MODULE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # <repo>/clockcanvas
SOURCE = os.path.join(MODULE, "artwork", "clockcanvas-icon-source.png")
RES = os.path.join(MODULE, "app", "src", "main", "res")
REPO = MODULE

# Adaptive icons: 108dp canvas, 72dp visible safe zone (66% of the canvas).
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG_TOP = (45, 28, 61)      # #251C3D
BG_BOTTOM = (9, 7, 15)     # #09070F
ACCENT = (124, 58, 237)    # #7C3AED


def log(msg: str) -> None:
    print(f"\033[1;35m==>\033[0m {msg}")


def load_source() -> Image.Image:
    if not os.path.exists(SOURCE):
        sys.exit(f"missing master artwork: {SOURCE}")
    image = Image.open(SOURCE).convert("RGBA")
    return image


def square_crop(image: Image.Image) -> Image.Image:
    side = min(image.width, image.height)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    return image.crop((left, top, left + side, top + side))


def gradient_field(size: int, top, bottom) -> Image.Image:
    base = Image.new("RGBA", (size, size))
    for y in range(size):
        t = y / max(1, size - 1)
        value = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        ImageDraw.Draw(base).line([(0, y), (size, y)], fill=value + (255,))
    return base


def foreground(size: int, source: Image.Image, *, inset: float = 0.62) -> Image.Image:
    """Scaled subject on a transparent field, inside the adaptive safe zone."""
    art = square_crop(source)
    inner = int(size * inset)
    art = art.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(art, ((size - inner) // 2, (size - inner) // 2), art)
    return canvas


def monochrome(size: int, source: Image.Image) -> Image.Image:
    """Single-colour silhouette for Android 13 themed icons."""
    art = square_crop(source).convert("L")
    inner = int(size * 0.62)
    art = art.resize((inner, inner), Image.LANCZOS)
    # Invert so bright artwork becomes a filled white glyph on transparency.
    art = ImageOps.invert(ImageOps.autocontrast(art))
    mask = art.point(lambda p: 255 if p > 150 else (p * 2) if p > 90 else 0)
    layer = Image.new("L", (size, size), 0)
    layer.paste(mask, ((size - inner) // 2, (size - inner) // 2))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(Image.new("RGBA", (size, size), (255, 255, 255, 255)), (0, 0), layer)
    return out


def legacy(size: int, source: Image.Image, round_icon: bool) -> Image.Image:
    art = square_crop(source).resize((size, size), Image.LANCZOS)
    base = gradient_field(size, BG_TOP, BG_BOTTOM)
    base.paste(art, (0, 0), art)
    if round_icon:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(base, (0, 0), mask)
        # A hairline ring keeps a round icon crisp on a light wallpaper.
        ImageDraw.Draw(out).ellipse(
            (1, 1, size - 2, size - 2), outline=(52, 39, 80, 255), width=max(1, size // 48)
        )
        return out
    radius = max(2, int(size * 0.16))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out


def adaptive_layer(name: str, image: Image.Image) -> None:
    """Writes res/drawable-nodpi/<name>.png (name is given without extension)."""
    folder = os.path.join(RES, "drawable-nodpi")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name if name.endswith(".png") else name + ".png")
    image.save(path, "PNG", optimize=True)
    log(f"wrote {os.path.relpath(path, REPO)} ({image.width}px, {os.path.getsize(path):,} bytes)")


def xml(name: str, body: str) -> None:
    folder = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(body)
    log(f"wrote {os.path.relpath(path, REPO)}")


def main() -> None:
    source = load_source()
    log(f"master artwork: {source.width}x{source.height}")

    # Adaptive layers are authored at xxxhdpi (4x) so every bucket downsamples.
    adaptive = 432  # 108dp * 4
    adaptive_layer("ic_launcher_foreground", foreground(adaptive, source))
    adaptive_layer("ic_launcher_monochrome.png", monochrome(adaptive, source))

    # The background layer is a generated field, not the artwork: keeps the
    # silhouette readable under any mask shape (circle, squircle, teardrop...).
    bg = gradient_field(adaptive, BG_TOP, BG_BOTTOM)
    ring = Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse(
        (int(adaptive * 0.08), int(adaptive * 0.08), int(adaptive * 0.92), int(adaptive * 0.92)),
        outline=ACCENT + (90,), width=int(adaptive * 0.012),
    )
    ring = ring.filter(ImageFilter.GaussianBlur(adaptive * 0.01))
    bg = Image.alpha_composite(bg, ring)
    adaptive_layer("ic_launcher_background.png", bg)

    adaptive_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n"
        "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
        "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n"
        "</adaptive-icon>\n"
    )
    xml("ic_launcher.xml", adaptive_xml)
    xml("ic_launcher_round.xml", adaptive_xml)

    for density, size in LEGACY_SIZES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        square = legacy(size, source, round_icon=False)
        round_img = legacy(size, source, round_icon=True)
        square.save(os.path.join(folder, "ic_launcher.png"), "PNG", optimize=True)
        round_img.save(os.path.join(folder, "ic_launcher_round.png"), "PNG", optimize=True)
        log(f"legacy {density}: {size}px square + round")

    # Store/listing icon at the size the Play listing wants.
    store = os.path.join(REPO, "artwork", "clockcanvas-store-icon-512.png")
    legacy(512, source, round_icon=False).resize((512, 512), Image.LANCZOS).save(store, "PNG", optimize=True)
    log(f"store icon: {os.path.relpath(store, REPO)}")


if __name__ == "__main__":
    main()
