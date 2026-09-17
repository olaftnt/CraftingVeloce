#!/usr/bin/env python3
"""Generates 16x16 textures for the two new furnaces.

There is no one to draw the textures, and without them the blocks render as a
missing model (pink-black / transparent). This script makes DECENT placeholders
in the mod's palette, so that the furnaces immediately look like part of the
set and can be told apart at first glance:

    Velocity Furnace          - purple accent (heat / fuel)
    Velocity Electric Furnace - blue accent (electricity)

The same colour split already exists in the mod: the controller, the extractor
and the pipe are purple, and the crafting table is blue. So the colour says
right away what powers the block.

Replacing them with real graphics: overwrite the PNG with the same name and
size of 16x16 - nothing else needs to change.

Usage:
    python3 scripts/gen_furnace_textures.py
"""

import os
import random

from PIL import Image

OUT_DIR = os.path.join("assets", "craftingveloce", "textures", "block")

# --- the mod's palette ---------------------------------------------------
# Sampled from the existing textures (controller / extractor / table) so that
# the furnaces do not stick out stylistically.
CASING = (0x3C, 0x2F, 0x3C)      # the main casing
CASING_DARK = (0x23, 0x1A, 0x24)  # shadow / recess
CASING_LIGHT = (0x56, 0x48, 0x55)  # highlight
CASING_MID = (0x46, 0x38, 0x46)

HEAT_GLOW = (0xC9, 0x2D, 0xEA)   # purple glow - heat
HEAT_DEEP = (0x8C, 0x1E, 0xA5)
VOLT_GLOW = (0x3C, 0x46, 0xFF)   # blue glow - power
VOLT_DEEP = (0x21, 0x74, 0xAA)

FIREBOX = (0x16, 0x12, 0x1A)     # the firebox interior

SIZE = 16


def shade(color, amount):
    """Brightens (amount > 0) or darkens (amount < 0) a colour."""
    r, g, b = color[:3]
    if amount >= 0:
        return (
            min(255, int(r + (255 - r) * amount)),
            min(255, int(g + (255 - g) * amount)),
            min(255, int(b + (255 - b) * amount)),
        )
    k = 1.0 + amount
    return (int(r * k), int(g * k), int(b * k))


def noisy(px, x, y, base, rng, spread=0.045):
    """Noise - without it large flat surfaces look like plastic."""
    px[x, y] = shade(base, rng.uniform(-spread, spread))


def fill_casing(px, rng):
    """Fills the whole texture with the casing, with subtle noise."""
    for y in range(SIZE):
        for x in range(SIZE):
            noisy(px, x, y, CASING, rng)


def bevel(px):
    """Top edge brighter, bottom edge darker - a readable solid form."""
    for x in range(SIZE):
        px[x, 0] = shade(CASING_LIGHT, 0.10)
        px[x, SIZE - 1] = shade(CASING_DARK, -0.25)
    for y in range(SIZE):
        px[0, y] = shade(CASING_LIGHT, 0.05)
        px[SIZE - 1, y] = shade(CASING_DARK, -0.20)


def rivet(px, x, y):
    """A rivet: a bright pixel with a dark shadow underneath."""
    px[x, y] = shade(CASING_LIGHT, 0.35)
    if y + 1 < SIZE:
        px[x, y + 1] = shade(CASING_DARK, -0.30)


def side_texture(accent, seed):
    """Side: casing, two ventilation panels, rivets in the corners."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), CASING + (255,))
    px = img.load()
    fill_casing(px, rng)

    # Two horizontal ventilation panels - slits.
    for panel_y in (4, 9):
        for x in range(3, 13):
            px[x, panel_y] = shade(CASING_DARK, -0.30)
            px[x, panel_y + 1] = shade(CASING_DARK, -0.10)

    # Rivets in the corners - they suggest a bolted casing.
    for (rx, ry) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(px, rx, ry)

    # A thin accent stripe - it tells the furnaces apart from the side.
    for y in range(6, 8):
        px[15, y] = accent

    bevel(px)
    return img


def top_texture(accent, seed):
    """Top: an inset lid with cross bracing."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), CASING + (255,))
    px = img.load()
    fill_casing(px, rng)

    # The inset square.
    for y in range(3, 13):
        for x in range(3, 13):
            px[x, y] = shade(CASING_MID, rng.uniform(-0.06, 0.06))
    for x in range(3, 13):
        px[x, 3] = shade(CASING_DARK, -0.35)
        px[x, 12] = shade(CASING_DARK, -0.15)
    for y in range(3, 13):
        px[3, y] = shade(CASING_DARK, -0.35)
        px[12, y] = shade(CASING_DARK, -0.15)

    # Cross bracing.
    for x in range(5, 11):
        px[x, 7] = shade(CASING_LIGHT, 0.08)
        px[x, 8] = shade(CASING_DARK, -0.20)
    for y in range(5, 11):
        px[7, y] = shade(CASING_LIGHT, 0.08)
        px[8, y] = shade(CASING_DARK, -0.20)

    # The centre of the cross - the accent.
    px[7, 7] = shade(accent, 0.15)
    px[8, 8] = shade(accent, -0.25)

    for (rx, ry) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        rivet(px, rx, ry)

    bevel(px)
    return img


def front_texture(accent, deep, seed, bars):
    """Front: a recessed firebox with a grate and a power indicator.

    {@code bars} is the list of rows in which the grate glows - thanks to that
    the fuel furnace (full fire) and the electric one (an even field) differ not
    only in colour, but also in the drawing.
    """
    rng = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), CASING + (255,))
    px = img.load()
    fill_casing(px, rng)

    # The firebox frame.
    for y in range(4, 13):
        for x in range(2, 14):
            px[x, y] = shade(CASING_DARK, -0.40)

    # The firebox interior.
    for y in range(5, 12):
        for x in range(3, 13):
            px[x, y] = FIREBOX

    # The grate: alternating bright and dark rods.
    for i, y in enumerate(range(6, 11)):
        if y in bars:
            for x in range(3, 13):
                px[x, y] = shade(accent, 0.05 if i % 2 == 0 else -0.05)
            # The rod's top edge glows more strongly.
            for x in range(3, 13):
                px[x, y] = shade(px[x, y], 0.18)
        else:
            for x in range(3, 13):
                px[x, y] = shade(deep, -0.55)

    # The grate's transverse ribs.
    for x in (5, 8, 11):
        for y in range(5, 12):
            px[x, y] = shade(px[x, y], -0.35)

    # The power indicator in the top-right corner.
    px[13, 2] = shade(accent, 0.35)
    px[12, 2] = shade(accent, -0.20)

    # The fuel / cable intake slit at the bottom.
    for x in range(4, 12):
        px[x, 13] = shade(CASING_DARK, -0.30)

    bevel(px)
    return img


def save(img, name):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    img.save(path)
    print("saved:", path)


def build_furnace(prefix, accent, deep, seed, bars):
    save(front_texture(accent, deep, seed, bars), f"{prefix}_front.png")
    save(side_texture(accent, seed + 1), f"{prefix}_side.png")
    save(top_texture(accent, seed + 2), f"{prefix}_top.png")


def main():
    # Fuel furnace: the grate glows strongly at the bottom - like a lit firebox.
    build_furnace("velocity_furnace", HEAT_GLOW, HEAT_DEEP, seed=20260915,
                  bars={9, 10})

    # Electric furnace: an even heating field, no fire.
    build_furnace("electric_furnace", VOLT_GLOW, VOLT_DEEP, seed=20260916,
                  bars={6, 8, 10})


if __name__ == "__main__":
    main()
