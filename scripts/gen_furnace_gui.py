#!/usr/bin/env python3
"""Generates the GUI backgrounds for both furnaces - IN THE EXTRACTOR'S STYLE.

The previous version drew a dark panel in the mod's style (purple/ender). The
effect was that the furnaces looked like they came from a different game than
the rest of the mod - the extractor uses a classic, vanilla GUI. This script
reproduces EXACTLY that style, sampled from the extractor's texture:

    panel           #c6c6c6
    light bevel     #ffffff   (the panel's top and left edge)
    dark bevel      #373737   (the panel's right and bottom edge)
    inner shadow    #8b8b8b   (1 px along the right and bottom edge)
    slot interior   #8b8b8b
    slot frame      #373737 (top+left) / #ffffff (bottom+right)

The layout is the same as in the extractor: a 212 x 166 panel, the player
inventory at x=26, the labels at x=26. Thanks to that both screens read as one
family.

Usage:
    python3 scripts/gen_furnace_gui.py
"""

import os

from PIL import Image, ImageDraw

OUT = os.path.join("assets", "craftingveloce", "textures", "gui")

# Panel dimensions - EXACTLY like the extractor (and like a vanilla container).
W, H = 212, 166
CANVAS = 256  # file size, as in the extractor

PANEL = (0xC6, 0xC6, 0xC6)
BEVEL_LIGHT = (0xFF, 0xFF, 0xFF)
BEVEL_DARK = (0x37, 0x37, 0x37)
SHADOW = (0x8B, 0x8B, 0x8B)
SLOT_BG = (0x8B, 0x8B, 0x8B)

# Slot positions - they MUST match the menu (VeloceVelocityFurnaceMenu).
# NOTE: one constant per LINE. Build.py reads them with a regular expression
# so that it can compare them against the menu and the screen - the short form
# (A, B = 1, 2) does not allow that, and without that comparison the GUI layout
# can drift apart unnoticed.
FILTER_X = 63
FILTER_Y = 18
FUEL_X = 134     # flame+fuel column, one slot step past the filters
FUEL_Y = 36
PLAYER_X = 26
PLAYER_Y = 84

# Positions of the elements the screens draw dynamically.
# ONE source: the screens use the same numbers (build.py checks this).
FLAME_X = 135    # the flame ABOVE the fuel slot (aligned with the top filter row)
FLAME_Y = 19
# Note: we do NOT draw the flame itself in the texture. The screen assembles it
# from two vanilla sprites (an extinguished outline + the lit part), exactly as
# the vanilla furnace does - see VeloceVelocityFurnaceScreen.
BATTERY_X = 66          # HORIZONTAL battery, centred (an 80 px group with the slot)
BATTERY_Y = 32
BATTERY_W = 56
BATTERY_H = 14
NUB_W = 2               # the battery's terminal (on the right side of the body)
NUB_H = 6
BATTERY_SLOT_X = 130    # the slot for the energy item - to the right of the battery
BATTERY_SLOT_Y = 32


def panel(draw):
    """A panel in the vanilla container style: a raised frame + an inner shadow."""
    draw.rectangle([0, 0, W - 1, H - 1], fill=PANEL)
    # Light bevel: top and left.
    draw.line([(0, 0), (W - 1, 0)], fill=BEVEL_LIGHT)
    draw.line([(0, 0), (0, H - 1)], fill=BEVEL_LIGHT)
    # Dark bevel: bottom and right.
    draw.line([(W - 1, 0), (W - 1, H - 1)], fill=BEVEL_DARK)
    draw.line([(0, H - 1), (W - 1, H - 1)], fill=BEVEL_DARK)
    # The inner shadow right next to the dark bevel - it is what gives the
    # recessed effect.
    draw.line([(W - 2, 1), (W - 2, H - 2)], fill=SHADOW)
    draw.line([(1, H - 2), (W - 2, H - 2)], fill=SHADOW)


def slot(draw, sx, sy):
    """A 16x16 slot in exactly the vanilla pattern (sampled from the extractor).

    The frame is not symmetric: top and left dark, bottom and right light -
    thanks to that the slot looks recessed rather than painted on.
    """
    draw.rectangle([sx, sy, sx + 15, sy + 15], fill=SLOT_BG)
    # MIND THE LENGTHS: the right and bottom edge are EXACTLY 16 px each.
    # The version ending at (sx+16, sy+16) was one pixel too long and that
    # pixel then overwrote the corner - the frame came out with a white blob in
    # the bottom-right corner. Sampled from the extractor's texture.
    draw.line([(sx - 1, sy - 1), (sx + 15, sy - 1)], fill=BEVEL_DARK)   # top, 17 px
    draw.line([(sx - 1, sy - 1), (sx - 1, sy + 15)], fill=BEVEL_DARK)   # left, 17 px
    draw.line([(sx + 16, sy), (sx + 16, sy + 15)], fill=BEVEL_LIGHT)    # right, 16 px
    draw.line([(sx, sy + 16), (sx + 15, sy + 16)], fill=BEVEL_LIGHT)    # bottom, 16 px
    # Corners that close the frame (without them the frame does not meet the
    # background).
    draw.point((sx + 16, sy - 1), fill=SLOT_BG)
    draw.point((sx - 1, sy + 16), fill=SLOT_BG)
    draw.point((sx + 16, sy + 16), fill=SLOT_BG)


def minus(draw, cx, cy):
    """A short dash in the middle of the slot.

    The battery slot's purpose is known up front, so we draw a "minus" in it
    (as other mods do) - the player sees that this is a place to PUT an item in,
    and not, say, for an output.
    """
    draw.rectangle([cx - 3, cy - 1, cx + 2, cy], fill=BEVEL_DARK)


def player_inventory(draw):
    for row in range(3):
        for col in range(9):
            slot(draw, PLAYER_X + col * 18, PLAYER_Y + row * 18)
    for col in range(9):
        slot(draw, PLAYER_X + col * 18, PLAYER_Y + 58)


def recess(draw, x0, y0, x1, y1):
    """A recessed field (for the flame or the energy bar).

    The same logic as the slot, only of any size - the field has to look like a
    recess rather than a glued-on rectangle.
    """
    draw.rectangle([x0, y0, x1, y1], fill=SLOT_BG)
    draw.line([(x0 - 1, y0 - 1), (x1 + 1, y0 - 1)], fill=BEVEL_DARK)
    draw.line([(x0 - 1, y0 - 1), (x0 - 1, y1 + 1)], fill=BEVEL_DARK)
    draw.line([(x1 + 1, y0), (x1 + 1, y1)], fill=BEVEL_LIGHT)
    draw.line([(x0, y1 + 1), (x1, y1 + 1)], fill=BEVEL_LIGHT)
    draw.point((x1 + 1, y0 - 1), fill=SLOT_BG)
    draw.point((x0 - 1, y1 + 1), fill=SLOT_BG)
    draw.point((x1 + 1, y1 + 1), fill=SLOT_BG)


def velocity_furnace():
    """Fuel furnace: 6 filters + a fuel slot + the flame window."""
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d)

    # Fuel filters: 3 columns x 2 rows - the same start as the extractor's filters.
    for i in range(6):
        slot(d, FILTER_X + (i % 3) * 18, FILTER_Y + (i // 3) * 18)
    # The actual fuel slot.
    slot(d, FUEL_X, FUEL_Y)

    # We do NOT paint the flame: the screen draws it with sprites from the
    # vanilla furnace (the outline + the lit part). Painting anything under it
    # here would only spoil the look - and so it did: our own recess looked
    # like a slot.

    player_inventory(d)
    return img


def electric_furnace():
    """Electric furnace: a horizontal battery + a slot for the energy item."""
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d)

    # The battery body - the screen fills it FROM LEFT TO RIGHT.
    recess(d, BATTERY_X, BATTERY_Y, BATTERY_X + BATTERY_W - 1, BATTERY_Y + BATTERY_H - 1)
    # The terminal on the right - without it this would be an ordinary bar
    # rather than a battery.
    nub_x = BATTERY_X + BATTERY_W
    nub_y = BATTERY_Y + (BATTERY_H - NUB_H) // 2
    recess(d, nub_x, nub_y, nub_x + NUB_W - 1, nub_y + NUB_H - 1)

    # The slot for the energy item, with a "minus" in the background.
    slot(d, BATTERY_SLOT_X, BATTERY_SLOT_Y)
    minus(d, BATTERY_SLOT_X + 8, BATTERY_SLOT_Y + 8)

    player_inventory(d)
    return img


def self_check(img):
    """Checks the GENERATED image: whether the slot frames are exactly where
    this file's constants declare them to be.

    <p>Why: the same numbers live in THREE places (this generator, the menu and
    the screen). They already drifted apart once - the fuel slot stood in a
    different place than the menu said, and it overlapped the "Inventory" label.
    It is easier to check pixels than to trust that someone remembered all three.
    """
    DARK = (0x37, 0x37, 0x37, 255)
    LIGHT = (0xFF, 0xFF, 0xFF, 255)
    BG = (0x8B, 0x8B, 0x8B, 255)
    PANEL = (0xC6, 0xC6, 0xC6, 255)

    def slot_ok(sx, sy):
        return (all(img.getpixel((sx - 1 + i, sy - 1)) == DARK for i in range(17))
                and all(img.getpixel((sx - 1, sy + i)) == DARK for i in range(16))
                and all(img.getpixel((sx + 16, sy + i)) == LIGHT for i in range(16))
                and all(img.getpixel((sx + i, sy + 16)) == LIGHT for i in range(16))
                and all(img.getpixel((sx + 2 + i, sy + 2)) == BG for i in range(12)))

    bad = []
    for i in range(6):
        x, y = FILTER_X + (i % 3) * 18, FILTER_Y + (i // 3) * 18
        if not slot_ok(x, y):
            bad.append(f"filter {i} @ {x},{y}")
    if not slot_ok(FUEL_X, FUEL_Y):
        bad.append(f"fuel @ {FUEL_X},{FUEL_Y}")
    for row in range(3):
        for col in range(9):
            if not slot_ok(PLAYER_X + col * 18, PLAYER_Y + row * 18):
                bad.append(f"inventory r{row}c{col}")
    for col in range(9):
        if not slot_ok(PLAYER_X + col * 18, PLAYER_Y + 58):
            bad.append(f"hotbar c{col}")

    # The flame's place must be clean - the screen draws the flame, with vanilla
    # sprites.
    dirty = sum(1 for y in range(FLAME_Y, FLAME_Y + 14)
                for x in range(FLAME_X, FLAME_X + 14)
                if img.getpixel((x, y)) != PANEL)
    if dirty:
        bad.append(f"flame place is soiled ({dirty} px)")

    if bad:
        raise SystemExit("ERROR: the texture does not match the constants: " + ", ".join(bad))
    print("  self-check: slot frames and the flame place OK")


def self_check_electric(img):
    """Electric furnace: only the battery slot (the screen draws the rest)."""
    DARK = (0x37, 0x37, 0x37, 255)
    LIGHT = (0xFF, 0xFF, 0xFF, 255)
    BG = (0x8B, 0x8B, 0x8B, 255)

    def slot_ok(sx, sy):
        return (all(img.getpixel((sx - 1 + i, sy - 1)) == DARK for i in range(17))
                and all(img.getpixel((sx - 1, sy + i)) == DARK for i in range(16))
                and all(img.getpixel((sx + 16, sy + i)) == LIGHT for i in range(16))
                and all(img.getpixel((sx + i, sy + 16)) == LIGHT for i in range(16)))

    bad = []
    if not slot_ok(BATTERY_SLOT_X, BATTERY_SLOT_Y):
        bad.append(f"battery slot @ {BATTERY_SLOT_X},{BATTERY_SLOT_Y}")
    # The slot background (apart from the dash) + the dash itself in the middle.
    if img.getpixel((BATTERY_SLOT_X + 3, BATTERY_SLOT_Y + 3)) != BG:
        bad.append("battery slot: no background")
    if img.getpixel((BATTERY_SLOT_X + 8, BATTERY_SLOT_Y + 8)) != DARK:
        bad.append("battery slot: no dash (minus)")
    for row in range(3):
        for col in range(9):
            if not slot_ok(PLAYER_X + col * 18, PLAYER_Y + row * 18):
                bad.append(f"inventory r{row}c{col}")
    for col in range(9):
        if not slot_ok(PLAYER_X + col * 18, PLAYER_Y + 58):
            bad.append(f"hotbar c{col}")
    # The battery body must be a recess (the slot background), not the panel.
    if img.getpixel((BATTERY_X + 2, BATTERY_Y + 2)) != BG:
        bad.append("the battery body is not a recess")

    if bad:
        raise SystemExit("ERROR: the electric furnace texture does not match: " + ", ".join(bad))
    print("  self-check (electric): battery slot, body and inventory OK")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, img in (("velocity_furnace", velocity_furnace()),
                      ("electric_furnace", electric_furnace())):
        path = os.path.join(OUT, name + ".png")
        img.save(path)
        print("saved:", path, img.size)
        if name == "velocity_furnace":
            self_check(img)
        else:
            self_check_electric(img)

    print()
    print("Positions to keep in sync in the screens:")
    print(f"  flame:     x={FLAME_X} y={FLAME_Y} (14x14)")
    print(f"  battery:   x={BATTERY_X} y={BATTERY_Y} w={BATTERY_W} h={BATTERY_H}"
          f" (terminal {NUB_W}x{NUB_H} on the right)")
    print(f"  bat. slot: x={BATTERY_SLOT_X} y={BATTERY_SLOT_Y} (16x16)")
    print(f"  inventory: x={PLAYER_X} y={PLAYER_Y}, hotbar y={PLAYER_Y + 58}")


if __name__ == "__main__":
    main()
