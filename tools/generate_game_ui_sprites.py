#!/usr/bin/env python3
"""Generate the Fantasy Machinery gameplay HUD and in-game menu sprite family.

This companion to ``generate_menu_ui_sprites.py`` produces reusable raster skins
for the live HUD, build shelf, information panels, overlays, and controls. The
Android renderer only loads the resulting PNG files from drawable-nodpi.  Every
image is made at a low logical resolution and doubled with nearest-neighbour
scaling, keeping the chrome crisp and stylistically compatible with the title
menu and the game's existing sprite layer.

Usage:
    python3 tools/generate_game_ui_sprites.py
"""

from __future__ import annotations

import math
from pathlib import Path

from generate_menu_ui_sprites import (
    BLACK,
    BRASS,
    BRASS_DARK,
    BRASS_LIGHT,
    CYAN,
    DARK,
    GREEN,
    GREEN_DARK,
    GRAY,
    OUTPUT,
    PURPLE,
    ROOT,
    STEEL,
    STEEL_DIM,
    STEEL_LIGHT,
    Raster,
    clamp,
    frame,
    gear,
    octagon,
    rgba,
    screw,
    shade,
    texture,
)

Color = tuple[int, int, int, int]

EMBER = rgba("#e77555")
GOLD = rgba("#efc25f")
INK = rgba("#07100c")
MOSS = rgba("#13251a")
PANEL_DARK = rgba("#0b1711", 244)
PANEL_MID = rgba("#183124", 246)


def save(name: str, image: Raster) -> None:
    target = OUTPUT / f"{name}.png"
    image.save(target)
    print(target.relative_to(ROOT))


def inset_frame(image: Raster, left: int, top: int, right: int, bottom: int, accent: Color, *, seed: int, warm: bool = True) -> None:
    """A reusable forged panel with inset steel, brass rails, and corner hardware."""
    cut = max(5, min((right - left) // 10, (bottom - top) // 5))
    frame(image, left, top, right, bottom, cut, accent, warm=warm)
    inner_left, inner_top = left + 10, top + 10
    inner_right, inner_bottom = right - 10, bottom - 10
    inner_cut = max(3, cut - 5)
    image.polygon(octagon(inner_left, inner_top, inner_right, inner_bottom, inner_cut), PANEL_DARK)
    image.gradient_rect(
        inner_left + 3,
        inner_top + 3,
        inner_right - 3,
        inner_bottom - 3,
        shade(PANEL_MID, 1.06),
        shade(MOSS, 0.68),
    )
    texture(image, inner_left + 5, inner_top + 5, inner_right - 5, inner_bottom - 5, seed=seed)
    image.line(inner_left + cut, inner_top + 3, inner_right - cut, inner_top + 3, rgba("#f1d68a", 92), 1)
    image.line(inner_left + cut, inner_bottom - 4, inner_right - cut, inner_bottom - 4, BLACK, 2)
    for x, y in (
        (inner_left + 5, inner_top + 5),
        (inner_right - 6, inner_top + 5),
        (inner_left + 5, inner_bottom - 6),
        (inner_right - 6, inner_bottom - 6),
    ):
        screw(image, x, y, max(2, min(4, (right - left) // 70)))


def draw_hud_rail(width: int, height: int, *, lower: bool) -> Raster:
    image = Raster(width, height)
    image.gradient_rect(0, 0, width, height, rgba("#111d16"), rgba("#050a08"))
    rail_y = 4 if lower else height - 5
    image.rect(0, rail_y - 2, width, rail_y + 3, BLACK)
    image.rect(0, rail_y - 1, width, rail_y + 1, BRASS_DARK)
    image.line(0, rail_y - 1, width - 1, rail_y - 1, BRASS_LIGHT, 1)
    image.line(0, rail_y + 2, width - 1, rail_y + 2, rgba("#0b110d"), 1)
    # Repeating cogs are intentionally oversized so they still read after the rail stretches.
    gear_y = height - 17 if lower else 17
    for index, x in enumerate(range(24, width, 94)):
        radius = 12 if index % 2 == 0 else 9
        gear(image, x, gear_y, radius, green=index % 3 == 0)
        image.line(x + radius + 3, gear_y, min(width - 1, x + 70), gear_y, rgba("#71501d", 145), 2)
    for x in range(12, width, 64):
        screw(image, x, 10 if lower else height - 11, 3)
    texture(image, 0, 8, width, height - 8, seed=11 if lower else 7)
    return image.scaled(2)


def draw_panel(width: int, height: int, accent: Color, *, seed: int, active: bool = False) -> Raster:
    image = Raster(width, height)
    inset_frame(image, 4, 4, width - 4, height - 4, accent, seed=seed, warm=active)
    if active:
        image.radial_glow(width // 2, height // 2, max(12, min(width, height) // 2), accent, 34)
        image.line(width // 5, 12, width * 4 // 5, 12, shade(accent, 1.2, 130), 1)
    return image.scaled(2)


def draw_button(width: int, height: int, accent: Color, *, pressed: bool = False, disabled: bool = False, seed: int = 0) -> Raster:
    image = Raster(width, height)
    shift = max(1, height // 14) if pressed else 0
    left, top, right, bottom = 5, 5 + shift, width - 5, height - 5 + shift
    active_accent = GRAY if disabled else accent
    inset_frame(image, left, top, right, bottom, active_accent, seed=seed, warm=not disabled)
    body = rgba("#27332c", 248) if disabled else shade(active_accent, 0.24)
    image.polygon(octagon(15, 14 + shift, width - 15, height - 14 + shift, max(5, height // 7)), body)
    image.gradient_rect(20, 18 + shift, width - 20, height - 19 + shift, shade(body, 1.22), shade(body, 0.62))
    image.line(26, 20 + shift, width - 26, 20 + shift, rgba("#f3dda1", 82 if disabled else 132), 1)
    image.line(26, height - 22 + shift, width - 26, height - 22 + shift, BLACK, 1)
    image.radial_glow(width // 2, height // 2 + shift, max(10, height // 2), active_accent, 12 if disabled else 48)
    # Side cog mounts make even compact buttons feel manufactured rather than flat.
    radius = max(4, min(9, height // 5))
    gear(image, 17, height // 2 + shift, radius, green=not disabled)
    gear(image, width - 17, height // 2 + shift, radius, green=not disabled)
    if pressed:
        image.line(30, 8, width - 30, 8, rgba("#fff0bb", 170), 1)
    return image.scaled(2)


def draw_tab(selected: bool) -> Raster:
    image = Raster(100, 56)
    accent = GREEN if selected else STEEL_LIGHT
    inset_frame(image, 3, 3, 97, 53, accent, seed=18 if selected else 19, warm=selected)
    if selected:
        image.polygon(octagon(13, 13, 87, 43, 8), shade(GREEN, 0.37))
        image.radial_glow(50, 28, 27, GREEN, 64)
    else:
        image.polygon(octagon(13, 13, 87, 43, 8), rgba("#15231b"))
    return image.scaled(2)


def draw_build_slot(selected: bool, *, disabled: bool = False) -> Raster:
    image = Raster(100, 116)
    accent = GRAY if disabled else (GREEN if selected else STEEL_LIGHT)
    inset_frame(image, 3, 3, 97, 113, accent, seed=25 if selected else 26, warm=selected)
    inner = rgba("#202b25", 248) if disabled else (shade(GREEN, 0.27) if selected else rgba("#101d16", 244))
    image.polygon(octagon(13, 13, 87, 103, 10), inner)
    image.gradient_rect(18, 19, 82, 98, shade(inner, 1.16), shade(inner, 0.70))
    image.line(23, 22, 77, 22, rgba("#e7d18d", 70), 1)
    image.line(23, 94, 77, 94, BLACK, 1)
    if selected:
        image.radial_glow(50, 47, 37, GREEN, 54)
        image.line(20, 16, 80, 16, rgba("#efffcc", 156), 1)
    return image.scaled(2)


def draw_banner() -> Raster:
    image = Raster(320, 58)
    inset_frame(image, 3, 4, 317, 54, GREEN, seed=31, warm=True)
    image.polygon(octagon(16, 14, 304, 45, 10), rgba("#102119", 244))
    image.radial_glow(160, 29, 72, GREEN, 50)
    gear(image, 22, 29, 9, green=True)
    gear(image, 298, 29, 9, green=True)
    return image.scaled(2)


def draw_stat_frame() -> Raster:
    image = Raster(82, 56)
    inset_frame(image, 2, 4, 80, 52, GREEN, seed=37, warm=False)
    image.polygon(octagon(12, 13, 70, 43, 7), rgba("#0d1912", 246))
    return image.scaled(2)


def icon_outline(image: Raster, accent: Color) -> None:
    image.circle(32, 32, 30, BLACK)
    gear(image, 32, 32, 25, green=False)
    image.circle(32, 32, 17, rgba("#0a1710"))
    image.radial_glow(32, 32, 18, accent, 54)


def icon_chevron(image: Raster, *, right: bool, color: Color, y: int = 32, offset: int = 0) -> None:
    if right:
        image.polygon([(23 + offset, y - 12), (43 + offset, y), (23 + offset, y + 12)], BLACK)
        image.polygon([(25 + offset, y - 10), (43 + offset, y), (25 + offset, y + 10)], color)
    else:
        image.polygon([(41 - offset, y - 12), (21 - offset, y), (41 - offset, y + 12)], BLACK)
        image.polygon([(39 - offset, y - 10), (21 - offset, y), (39 - offset, y + 10)], color)


def draw_icon(kind: str, accent: Color) -> Raster:
    image = Raster(64, 64)
    icon_outline(image, accent)
    pale = rgba("#f4edc9")
    dark_accent = shade(accent, 0.55)

    if kind == "blocks":
        for left, top in ((16, 22), (28, 17), (28, 29), (40, 22)):
            image.polygon(octagon(left, top, left + 12, top + 10, 2), BLACK)
            image.polygon(octagon(left + 1, top + 1, left + 11, top + 9, 2), BRASS)
            image.line(left + 3, top + 3, left + 9, top + 3, BRASS_LIGHT, 1)
    elif kind == "core":
        shield = [(32, 15), (47, 22), (44, 40), (32, 50), (20, 40), (17, 22)]
        image.polygon(shield, BLACK)
        image.polygon([(32, 17), (45, 23), (42, 39), (32, 47), (22, 39), (19, 23)], EMBER)
        image.circle(32, 31, 8, BRASS_DARK)
        image.circle(32, 31, 5, pale)
    elif kind == "wave":
        image.line(17, 42, 47, 42, BRASS, 2)
        for x, height in ((21, 9), (30, 16), (39, 24)):
            image.rect(x - 3, 42 - height, x + 3, 42, BLACK)
            image.rect(x - 2, 41 - height, x + 2, 41, CYAN)
        image.polygon([(40, 16), (50, 20), (40, 25)], pale)
        image.line(40, 16, 40, 33, BRASS_LIGHT, 1)
    elif kind == "launch":
        image.polygon([(20, 44), (25, 21), (43, 18), (38, 40)], BLACK)
        image.polygon([(22, 42), (27, 23), (41, 20), (36, 38)], GREEN)
        image.polygon([(31, 29), (50, 14), (42, 35)], pale)
        image.line(17, 47, 27, 40, EMBER, 2)
    elif kind == "stack":
        icon_chevron(image, right=True, color=PURPLE, y=24, offset=-4)
        icon_chevron(image, right=True, color=PURPLE, y=39, offset=3)
    elif kind == "route":
        image.line(16, 44, 27, 44, BRASS, 4)
        image.line(27, 44, 27, 26, BRASS, 4)
        image.line(27, 26, 47, 26, BRASS, 4)
        image.circle(16, 44, 5, GREEN)
        image.circle(47, 26, 5, CYAN)
        image.line(18, 42, 25, 42, pale, 1)
    elif kind == "reforge":
        image.rect(18, 35, 46, 41, BLACK)
        image.polygon([(18, 35), (27, 26), (38, 26), (46, 35)], BRASS)
        image.rect(29, 18, 35, 35, STEEL_LIGHT)
        image.polygon([(25, 18), (39, 18), (35, 24), (29, 24)], pale)
        image.line(17, 44, 47, 44, EMBER, 2)
    elif kind == "reset":
        for step in range(19):
            angle = math.radians(25 + step * 15)
            image.circle(round(32 + math.cos(angle) * 15), round(32 + math.sin(angle) * 15), 2, CYAN)
        image.polygon([(41, 15), (51, 17), (46, 26)], pale)
    elif kind == "pause":
        for left in (22, 35):
            image.rect(left - 2, 18, left + 6, 46, BLACK)
            image.rect(left, 20, left + 4, 44, pale)
    elif kind == "feedback":
        image.polygon([(16, 19), (48, 19), (48, 40), (31, 40), (24, 48), (25, 40), (16, 40)], BLACK)
        image.polygon([(18, 21), (46, 21), (46, 38), (30, 38), (26, 44), (27, 38), (18, 38)], CYAN)
        for x in (24, 32, 40):
            image.circle(x, 30, 2, INK)
    elif kind == "back":
        icon_chevron(image, right=False, color=pale)
        image.line(22, 32, 47, 32, BRASS, 3)
    elif kind == "upgrade":
        image.line(32, 47, 32, 20, BRASS, 5)
        image.polygon([(18, 29), (32, 15), (46, 29)], BLACK)
        image.polygon([(20, 28), (32, 17), (44, 28)], GREEN)
        image.line(25, 43, 39, 43, pale, 1)
    elif kind == "evolve":
        star = [(32, 14), (37, 26), (50, 26), (40, 34), (44, 48), (32, 39), (20, 48), (24, 34), (14, 26), (27, 26)]
        image.polygon(star, BLACK)
        image.polygon([(32, 16), (36, 28), (47, 28), (38, 35), (41, 45), (32, 37), (23, 45), (26, 35), (17, 28), (28, 28)], PURPLE)
        image.circle(32, 32, 4, pale)
    elif kind == "store":
        image.rect(17, 25, 47, 44, BLACK)
        image.rect(19, 27, 45, 42, BRASS_DARK)
        image.rect(20, 28, 44, 34, BRASS)
        image.line(20, 35, 44, 35, pale, 1)
        image.rect(29, 31, 35, 39, GREEN_DARK)
    elif kind == "imbue":
        diamond = [(32, 14), (46, 32), (32, 50), (18, 32)]
        image.polygon(diamond, BLACK)
        image.polygon([(32, 17), (43, 32), (32, 47), (21, 32)], PURPLE)
        image.line(32, 20, 32, 44, pale, 2)
        image.line(24, 32, 40, 32, pale, 2)
    elif kind == "recycle":
        points = [(32, 15), (47, 25), (43, 29), (39, 27), (39, 37), (34, 37), (37, 42), (32, 48), (17, 38), (21, 34), (25, 36), (25, 26), (20, 28), (17, 23)]
        image.polygon(points, BLACK)
        image.polygon([(32, 18), (44, 26), (41, 27), (36, 25), (36, 40), (32, 44), (20, 37), (23, 36), (28, 38), (28, 23), (22, 26), (20, 23)], EMBER)
    elif kind == "towers":
        image.rect(25, 33, 39, 46, BLACK)
        image.polygon([(20, 33), (27, 19), (37, 19), (44, 33)], BLACK)
        image.polygon([(22, 32), (28, 21), (36, 21), (42, 32)], GREEN)
        image.rect(29, 14, 35, 26, BRASS)
        image.line(17, 46, 47, 46, pale, 1)
    elif kind == "traps":
        image.line(15, 43, 49, 43, BRASS, 3)
        for x in (21, 29, 37, 45):
            image.polygon([(x - 4, 42), (x, 22), (x + 4, 42)], BLACK)
            image.polygon([(x - 2, 40), (x, 25), (x + 2, 40)], CYAN)
    elif kind == "utilities":
        gear(image, 32, 32, 18, green=True)
        image.rect(29, 18, 35, 46, BRASS)
        image.rect(18, 29, 46, 35, BRASS)
        image.circle(32, 32, 6, INK)
    elif kind == "cache":
        image.polygon([(16, 25), (32, 17), (48, 25), (48, 43), (32, 51), (16, 43)], BLACK)
        image.polygon([(18, 26), (32, 20), (46, 26), (46, 41), (32, 48), (18, 41)], PURPLE)
        image.line(18, 30, 46, 30, pale, 1)
        image.circle(32, 36, 4, BRASS_LIGHT)
    elif kind == "lock":
        image.rect(20, 29, 44, 47, BLACK)
        image.rect(22, 31, 42, 45, GRAY)
        image.circle(32, 30, 12, BLACK, fill=False, width=4)
        image.circle(32, 30, 9, GRAY, fill=False, width=2)
        image.circle(32, 37, 3, INK)
    elif kind == "previous":
        icon_chevron(image, right=False, color=pale)
    elif kind == "next":
        icon_chevron(image, right=True, color=pale)
    elif kind == "craft":
        image.line(22, 43, 42, 22, BRASS, 5)
        image.polygon([(34, 15), (48, 19), (45, 28), (36, 25)], BLACK)
        image.polygon([(36, 17), (46, 20), (43, 26), (37, 23)], pale)
        image.rect(18, 40, 27, 49, EMBER)
    elif kind == "supplies":
        image.polygon([(27, 15), (37, 15), (40, 28), (45, 34), (40, 49), (24, 49), (19, 34), (24, 28)], BLACK)
        image.polygon([(29, 17), (35, 17), (37, 29), (42, 35), (38, 47), (26, 47), (22, 35), (27, 29)], CYAN)
        image.line(26, 37, 38, 37, pale, 1)
    elif kind == "seed":
        image.polygon([(32, 15), (45, 25), (42, 42), (32, 50), (22, 42), (19, 25)], BLACK)
        image.polygon([(32, 18), (42, 26), (39, 40), (32, 47), (25, 40), (22, 26)], GREEN)
        image.circle(32, 32, 5, BRASS_LIGHT)
        image.line(32, 24, 32, 40, INK, 1)
    else:
        image.circle(32, 32, 10, accent)
    return image.scaled(2)


def main() -> None:
    # Rails and panels.
    save("hud_top_rail", draw_hud_rail(512, 68, lower=False))
    save("hud_bottom_rail", draw_hud_rail(512, 128, lower=True))
    save("ui_panel", draw_panel(360, 116, STEEL_LIGHT, seed=42))
    save("ui_panel_active", draw_panel(360, 116, GREEN, seed=43, active=True))
    save("ui_modal", draw_panel(360, 240, GOLD, seed=44, active=True))
    save("ui_card", draw_panel(200, 260, STEEL_LIGHT, seed=45))
    save("ui_card_active", draw_panel(200, 260, GREEN, seed=46, active=True))
    save("ui_tab", draw_tab(False))
    save("ui_tab_selected", draw_tab(True))
    save("ui_build_slot", draw_build_slot(False))
    save("ui_build_slot_selected", draw_build_slot(True))
    save("ui_build_slot_disabled", draw_build_slot(False, disabled=True))
    save("ui_banner", draw_banner())
    save("ui_stat_frame", draw_stat_frame())

    # Controls share a consistent construction but retain functional color families.
    for name, color, seed in (
        ("primary", GREEN, 51),
        ("secondary", CYAN, 52),
        ("accent", PURPLE, 53),
        ("warning", EMBER, 54),
    ):
        save(f"ui_button_{name}", draw_button(200, 60, color, seed=seed))
        save(f"ui_button_{name}_pressed", draw_button(200, 60, color, pressed=True, seed=seed))
    save("ui_button_disabled", draw_button(200, 60, GRAY, disabled=True, seed=55))

    icons: tuple[tuple[str, Color], ...] = (
        ("blocks", BRASS),
        ("core", EMBER),
        ("wave", CYAN),
        ("launch", GREEN),
        ("stack", PURPLE),
        ("route", GOLD),
        ("reforge", BRASS),
        ("reset", CYAN),
        ("pause", BRASS),
        ("feedback", CYAN),
        ("back", BRASS),
        ("upgrade", GREEN),
        ("evolve", PURPLE),
        ("store", GOLD),
        ("imbue", PURPLE),
        ("recycle", EMBER),
        ("towers", GREEN),
        ("traps", CYAN),
        ("utilities", GOLD),
        ("cache", PURPLE),
        ("lock", GRAY),
        ("previous", BRASS),
        ("next", BRASS),
        ("craft", GOLD),
        ("supplies", CYAN),
        ("seed", GREEN),
    )
    for name, color in icons:
        save(f"ui_icon_{name}", draw_icon(name, color))


if __name__ == "__main__":
    main()
