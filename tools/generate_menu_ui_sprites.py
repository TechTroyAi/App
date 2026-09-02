#!/usr/bin/env python3
"""Generate the v1.4.3 fantasy-machinery title-menu PNG sprite family.

The runtime only loads the generated PNG files from drawable-nodpi. This small,
stdlib-only producer keeps the title chrome reproducible on the same minimal
build machines used by the Android project (no runtime vector drawable or UI
framework needed). Assets are rendered at a deliberately low logical
resolution then doubled with nearest-neighbour scaling for a crisp pixel-art
finish matching the game's existing sprite work.

Usage:
    python3 tools/generate_menu_ui_sprites.py
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

Color = tuple[int, int, int, int]


def rgba(value: str, alpha: int = 255) -> Color:
    value = value.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha)


def clamp(value: int) -> int:
    return max(0, min(255, value))


def shade(color: Color, factor: float, alpha: int | None = None) -> Color:
    return (
        clamp(round(color[0] * factor)),
        clamp(round(color[1] * factor)),
        clamp(round(color[2] * factor)),
        color[3] if alpha is None else alpha,
    )


def blend(a: Color, b: Color, amount: float) -> Color:
    return (
        clamp(round(a[0] + (b[0] - a[0]) * amount)),
        clamp(round(a[1] + (b[1] - a[1]) * amount)),
        clamp(round(a[2] + (b[2] - a[2]) * amount)),
        clamp(round(a[3] + (b[3] - a[3]) * amount)),
    )


class Raster:
    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        self.data = bytearray(width * height * 4)

    def pixel(self, x: int, y: int, color: Color) -> None:
        if x < 0 or x >= self.width or y < 0 or y >= self.height or color[3] <= 0:
            return
        index = (y * self.width + x) * 4
        sa = color[3]
        if sa >= 255:
            self.data[index:index + 4] = bytes(color)
            return
        da = self.data[index + 3]
        out_a = sa + (da * (255 - sa) + 127) // 255
        if out_a <= 0:
            return
        source_weight = sa * 255
        dest_weight = da * (255 - sa)
        self.data[index] = clamp((color[0] * source_weight + self.data[index] * dest_weight) // (out_a * 255))
        self.data[index + 1] = clamp((color[1] * source_weight + self.data[index + 1] * dest_weight) // (out_a * 255))
        self.data[index + 2] = clamp((color[2] * source_weight + self.data[index + 2] * dest_weight) // (out_a * 255))
        self.data[index + 3] = clamp(out_a)

    def rect(self, left: int, top: int, right: int, bottom: int, color: Color) -> None:
        for y in range(max(0, top), min(self.height, bottom)):
            for x in range(max(0, left), min(self.width, right)):
                self.pixel(x, y, color)

    def line(self, x0: int, y0: int, x1: int, y1: int, color: Color, width: int = 1) -> None:
        dx = abs(x1 - x0)
        sx = 1 if x0 < x1 else -1
        dy = -abs(y1 - y0)
        sy = 1 if y0 < y1 else -1
        error = dx + dy
        radius = max(0, (width - 1) // 2)
        while True:
            self.rect(x0 - radius, y0 - radius, x0 + radius + 1, y0 + radius + 1, color)
            if x0 == x1 and y0 == y1:
                return
            twice = 2 * error
            if twice >= dy:
                error += dy
                x0 += sx
            if twice <= dx:
                error += dx
                y0 += sy

    def circle(self, cx: int, cy: int, radius: int, color: Color, *, fill: bool = True, width: int = 1) -> None:
        radius_sq = radius * radius
        inner = max(0, radius - width)
        inner_sq = inner * inner
        for y in range(cy - radius, cy + radius + 1):
            for x in range(cx - radius, cx + radius + 1):
                distance = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if distance <= radius_sq and (fill or distance >= inner_sq):
                    self.pixel(x, y, color)

    def polygon(self, points: list[tuple[int, int]], color: Color) -> None:
        if len(points) < 3:
            return
        min_y = max(0, min(y for _, y in points))
        max_y = min(self.height - 1, max(y for _, y in points))
        count = len(points)
        for y in range(min_y, max_y + 1):
            crossings: list[float] = []
            for i in range(count):
                x1, y1 = points[i]
                x2, y2 = points[(i + 1) % count]
                if (y1 <= y < y2) or (y2 <= y < y1):
                    crossings.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
            crossings.sort()
            for i in range(0, len(crossings) - 1, 2):
                self.rect(math.ceil(crossings[i]), y, math.floor(crossings[i + 1]) + 1, y + 1, color)

    def radial_glow(self, cx: int, cy: int, radius: int, color: Color, alpha: int) -> None:
        if radius <= 0:
            return
        for y in range(cy - radius, cy + radius + 1):
            for x in range(cx - radius, cx + radius + 1):
                distance = math.sqrt((x - cx) ** 2 + (y - cy) ** 2)
                if distance > radius:
                    continue
                amount = (1.0 - distance / radius) ** 2
                self.pixel(x, y, (color[0], color[1], color[2], round(alpha * amount)))

    def gradient_rect(self, left: int, top: int, right: int, bottom: int, upper: Color, lower: Color) -> None:
        total = max(1, bottom - top - 1)
        for y in range(top, bottom):
            color = blend(upper, lower, (y - top) / total)
            self.rect(left, y, right, y + 1, color)

    def scaled(self, multiplier: int) -> "Raster":
        output = Raster(self.width * multiplier, self.height * multiplier)
        for y in range(self.height):
            for x in range(self.width):
                index = (y * self.width + x) * 4
                color = tuple(self.data[index:index + 4])  # type: ignore[assignment]
                output.rect(x * multiplier, y * multiplier, (x + 1) * multiplier, (y + 1) * multiplier, color)
        return output

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = b"".join(
            b"\x00" + bytes(self.data[row * self.width * 4:(row + 1) * self.width * 4])
            for row in range(self.height)
        )

        def chunk(kind: bytes, payload: bytes) -> bytes:
            return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(raw, level=9))
        png += chunk(b"IEND", b"")
        path.write_bytes(png)


DARK = rgba("#070d0b")
BLACK = rgba("#030604")
STEEL = rgba("#1d2824")
STEEL_LIGHT = rgba("#475950")
STEEL_DIM = rgba("#17211d")
BRASS = rgba("#c68d3b")
BRASS_LIGHT = rgba("#f2d98f")
BRASS_DARK = rgba("#4d3010")
GREEN = rgba("#b9f05d")
GREEN_DARK = rgba("#286443")
CYAN = rgba("#69d9ed")
PURPLE = rgba("#cc9df9")
GRAY = rgba("#718077")


def octagon(left: int, top: int, right: int, bottom: int, cut: int) -> list[tuple[int, int]]:
    return [
        (left + cut, top), (right - cut, top), (right, top + cut), (right, bottom - cut),
        (right - cut, bottom), (left + cut, bottom), (left, bottom - cut), (left, top + cut),
    ]


def screw(image: Raster, x: int, y: int, radius: int = 3) -> None:
    image.circle(x, y, radius + 1, BLACK)
    image.circle(x, y, radius, BRASS_DARK)
    image.circle(x - 1, y - 1, max(1, radius - 1), BRASS_LIGHT)
    image.line(x - radius + 1, y + radius - 1, x + radius - 1, y - radius + 1, rgba("#5e3b13"), 1)


def gear(image: Raster, cx: int, cy: int, radius: int, *, green: bool = False) -> None:
    tooth = max(2, radius // 4)
    for index in range(12):
        angle = math.tau * index / 12
        x = round(cx + math.cos(angle) * radius * 0.92)
        y = round(cy + math.sin(angle) * radius * 0.92)
        image.rect(x - tooth // 2, y - tooth // 2, x + (tooth + 1) // 2, y + (tooth + 1) // 2, BLACK)
        image.rect(x - tooth // 2 + 1, y - tooth // 2 + 1, x + (tooth + 1) // 2 - 1, y + (tooth + 1) // 2 - 1, BRASS_DARK)
    image.circle(cx, cy, radius, BLACK)
    image.circle(cx, cy, radius - 2, BRASS_DARK)
    image.circle(cx, cy, radius - 4, BRASS)
    image.circle(cx, cy, max(2, round(radius * 0.62)), DARK)
    image.circle(cx, cy, max(1, round(radius * 0.58)), rgba("#10211a"))
    image.circle(cx, cy, max(2, round(radius * 0.26)), GREEN_DARK if green else STEEL_LIGHT)
    image.circle(cx - 1, cy - 1, max(1, round(radius * 0.12)), GREEN if green else BRASS_LIGHT)


def texture(image: Raster, left: int, top: int, right: int, bottom: int, seed: int = 0) -> None:
    # Deterministic tiny scuffs give the metal skin a hand-worked finish.
    for index in range(38):
        x = left + ((index * 29 + seed * 17) % max(1, right - left))
        y = top + ((index * 13 + seed * 23) % max(1, bottom - top))
        length = 2 + (index % 4)
        color = rgba("#90a291", 24 if index % 3 else 38)
        image.line(x, y, min(right - 1, x + length), y, color)
    for index in range(16):
        x = left + ((index * 47 + seed * 11) % max(1, right - left))
        y = top + ((index * 19 + seed * 31) % max(1, bottom - top))
        image.pixel(x, y, rgba("#000000", 95))


def frame(image: Raster, left: int, top: int, right: int, bottom: int, cut: int, accent: Color, *, warm: bool = True) -> None:
    image.polygon(octagon(left + 2, top + 3, right + 2, bottom + 3, cut), rgba("#000000", 115))
    image.polygon(octagon(left, top, right, bottom, cut), BLACK)
    image.polygon(octagon(left + 2, top + 2, right - 2, bottom - 2, max(2, cut - 1)), BRASS_DARK)
    image.polygon(octagon(left + 4, top + 4, right - 4, bottom - 4, max(2, cut - 2)), BRASS)
    image.polygon(octagon(left + 6, top + 6, right - 6, bottom - 6, max(2, cut - 3)), STEEL_LIGHT if warm else STEEL_DIM)
    image.polygon(octagon(left + 8, top + 8, right - 8, bottom - 8, max(2, cut - 4)), STEEL)
    image.line(left + cut, top + 6, right - cut, top + 6, BRASS_LIGHT, 1)
    image.line(left + cut, bottom - 7, right - cut, bottom - 7, BLACK, 2)
    image.line(left + 11, top + cut, left + 11, bottom - cut, shade(accent, 0.58, 150), 1)
    image.line(right - 12, top + cut, right - 12, bottom - cut, shade(accent, 0.58, 150), 1)


def draw_button(name: str, accent: Color, *, active: bool, pressed: bool = False) -> Raster:
    image = Raster(320, 76)
    shift = 3 if pressed else 0
    frame(image, 10, 10 + shift, 310, 66 + shift, 16, accent if active else GRAY, warm=active)
    body = GREEN_DARK if active else rgba("#25302b")
    image.polygon(octagon(19, 18 + shift, 301, 58 + shift, 12), body)
    image.gradient_rect(28, 21 + shift, 292, 55 + shift, shade(body, 1.24), shade(body, 0.62))
    image.rect(30, 23 + shift, 290, 24 + shift, rgba("#f1d586", 95))
    image.line(72, 25 + shift, 248, 25 + shift, shade(accent, 1.15, 78), 1)
    image.line(72, 52 + shift, 248, 52 + shift, BLACK, 1)
    texture(image, 30, 25 + shift, 290, 52 + shift, seed=1 if active else 4)
    image.radial_glow(160, 38 + shift, 30, accent, 46 if active else 15)
    gear(image, 23, 38 + shift, 10, green=active)
    gear(image, 297, 38 + shift, 10, green=active)
    for x, y in ((35, 20 + shift), (285, 20 + shift), (35, 56 + shift), (285, 56 + shift)):
        screw(image, x, y, 3)
    if pressed:
        image.line(56, 13, 264, 13, rgba("#f7e3a6", 145), 1)
    return image.scaled(2)


def draw_panel() -> Raster:
    image = Raster(380, 316)
    frame(image, 18, 14, 362, 302, 28, GREEN, warm=True)
    image.polygon(octagon(31, 27, 349, 289, 20), rgba("#08130e", 220))
    image.polygon(octagon(39, 35, 341, 281, 16), rgba("#102218", 205))
    texture(image, 45, 42, 335, 275, seed=8)
    image.line(50, 46, 330, 46, rgba("#ecce7c", 108), 1)
    image.line(50, 271, 330, 271, BLACK, 2)
    image.line(48, 81, 48, 235, rgba("#c38b37", 112), 2)
    image.line(332, 81, 332, 235, rgba("#c38b37", 112), 2)
    for x, y in ((45, 42), (335, 42), (45, 274), (335, 274)):
        gear(image, x, y, 16, green=True)
    for x in (27, 353):
        for y in (27, 289):
            screw(image, x, y, 4)
    return image.scaled(2)


def draw_crest() -> Raster:
    image = Raster(280, 126)
    frame(image, 36, 18, 244, 108, 22, GREEN, warm=True)
    image.polygon(octagon(51, 32, 229, 94, 15), rgba("#11271a", 245))
    image.line(58, 38, 222, 38, rgba("#d7ed9c", 104), 1)
    image.line(61, 86, 219, 86, BLACK, 2)
    gear(image, 56, 63, 20, green=True)
    gear(image, 224, 63, 20, green=True)
    image.radial_glow(140, 63, 35, GREEN, 95)
    image.circle(140, 63, 28, BLACK)
    image.circle(140, 63, 25, BRASS_DARK)
    image.circle(140, 63, 22, GREEN_DARK)
    image.circle(140, 63, 19, rgba("#387946"))
    image.radial_glow(140, 63, 18, GREEN, 185)
    star = [(140, 35), (146, 53), (165, 55), (151, 67), (155, 87), (140, 76), (125, 87), (129, 67), (115, 55), (134, 53)]
    image.polygon(star, rgba("#dfffc8"))
    image.circle(140, 63, 6, rgba("#f3ffd9"))
    for x, y in ((76, 41), (204, 41), (76, 85), (204, 85)):
        screw(image, x, y, 3)
    return image.scaled(2)


def draw_icon(name: str, kind: str, accent: Color) -> Raster:
    image = Raster(64, 64)
    image.circle(32, 32, 30, BLACK)
    gear(image, 32, 32, 25, green=True)
    image.circle(32, 32, 18, DARK)
    image.radial_glow(32, 32, 18, accent, 78)
    image.circle(32, 32, 16, shade(accent, 0.38))
    image.circle(32, 32, 14, rgba("#0c1c14"))
    if kind == "play":
        image.polygon([(25, 19), (45, 32), (25, 45)], BLACK)
        image.polygon([(27, 20), (45, 32), (27, 44)], accent)
        image.line(28, 21, 28, 42, rgba("#edffd8"), 1)
    elif kind == "continue":
        for index in range(24):
            angle = math.radians(20 + index * 11)
            x = round(32 + math.cos(angle) * 12)
            y = round(32 + math.sin(angle) * 12)
            image.circle(x, y, 2, accent)
        image.polygon([(42, 15), (50, 16), (46, 25)], rgba("#eaffd4"))
        image.line(42, 15, 48, 17, accent, 1)
    elif kind == "challenge":
        image.polygon([(18, 45), (38, 21), (43, 26), (23, 50)], BLACK)
        image.polygon([(20, 44), (38, 20), (41, 23), (23, 47)], accent)
        image.line(20, 20, 45, 45, rgba("#f6dc8c"), 4)
        image.line(16, 48, 28, 36, BRASS_DARK, 3)
        image.line(36, 28, 48, 16, BRASS_DARK, 3)
    elif kind == "sound":
        image.polygon([(16, 26), (25, 26), (37, 17), (37, 47), (25, 38), (16, 38)], BLACK)
        image.polygon([(18, 27), (26, 27), (35, 19), (35, 45), (26, 37), (18, 37)], accent)
        for radius in (10, 16):
            for index in range(11):
                angle = math.radians(-50 + index * 10)
                x = round(36 + math.cos(angle) * radius)
                y = round(32 + math.sin(angle) * radius)
                image.circle(x, y, 1, accent)
    else:
        image.polygon([(15, 26), (24, 26), (36, 17), (36, 47), (24, 38), (15, 38)], GRAY)
        image.line(42, 23, 53, 41, rgba("#e76e5a"), 3)
        image.line(53, 23, 42, 41, rgba("#e76e5a"), 3)
    return image.scaled(2)


def save(name: str, image: Raster) -> None:
    target = OUTPUT / f"{name}.png"
    image.save(target)
    print(target.relative_to(ROOT))


def main() -> None:
    save("menu_panel", draw_panel())
    save("menu_title_crest", draw_crest())
    save("menu_button_primary", draw_button("menu_button_primary", GREEN, active=True))
    save("menu_button_secondary", draw_button("menu_button_secondary", CYAN, active=True))
    save("menu_button_challenge", draw_button("menu_button_challenge", PURPLE, active=True))
    save("menu_button_disabled", draw_button("menu_button_disabled", GRAY, active=False))
    save("menu_button_primary_pressed", draw_button("menu_button_primary_pressed", BRASS, active=True, pressed=True))
    save("menu_button_secondary_pressed", draw_button("menu_button_secondary_pressed", CYAN, active=True, pressed=True))
    save("menu_button_challenge_pressed", draw_button("menu_button_challenge_pressed", PURPLE, active=True, pressed=True))
    save("menu_icon_play", draw_icon("menu_icon_play", "play", GREEN))
    save("menu_icon_continue", draw_icon("menu_icon_continue", "continue", CYAN))
    save("menu_icon_challenge", draw_icon("menu_icon_challenge", "challenge", PURPLE))
    save("menu_icon_sound_on", draw_icon("menu_icon_sound_on", "sound", GREEN))
    save("menu_icon_sound_off", draw_icon("menu_icon_sound_off", "muted", GRAY))


if __name__ == "__main__":
    main()
