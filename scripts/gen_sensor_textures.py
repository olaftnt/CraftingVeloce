#!/usr/bin/env python3
"""Veloce Threshold Sensor textures: the block (off/on) + the GUI background.

BLOCK: the base is the vanilla `observer` - it is a redstone block, so it fits
thematically perfectly. Its own red diode (2x2 pixels) becomes our indicator:
dimmed when the sensor is not outputting power, and glowing when it is. The rest
of the body is left unchanged, so the block still looks like Minecraft.

GUI: a panel in the extractor's style (212x166) + ONE item slot in a centred
row. The row (slot, number field, "+", "-", mode button) is drawn by the
screen, so there are no recesses for it in the texture - and there is no longer
the square for the status text, which stayed empty and looked like a graphics
bug. That square was exactly what a player reported.

Usage:
    python3 scripts/gen_sensor_textures.py
"""

import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_furnace_gui as style  # noqa: E402  (shared panel and slot style)

VANILLA = "/tmp/matref"
BLOCK_OUT = os.path.join("assets", "craftingveloce", "textures", "block")
GUI_OUT = os.path.join("assets", "craftingveloce", "textures", "gui")

# The diode in the vanilla observer - exactly these pixels.
LAMP = [(7, 7), (8, 7), (7, 8), (8, 8)]

LAMP_OFF = (0x5A, 0x0A, 0x06)
LAMP_ON = (0xFF, 0x3B, 0x2A)
LAMP_GLOW = (0xFF, 0x8A, 0x70)

# The item slot's position - it MUST match the menu and the screen.
# NOTE: one constant per LINE. Build.py reads them with a regular expression so
# that it can compare the generator against the menu (the short form
# "A, B = 1, 2" does not allow that, and that is precisely why this pair was
# outside the checks).
FILTER_SLOT_X = 41
FILTER_SLOT_Y = 41

# The bounds of the whole row - for the "the row is a clean panel" self-check.
# The menu has the same numbers (ROW_Y=39, ROW_H=20, MODE_X=151, BTN_W=20); they
# are here only to check PIXELS, not to be used for drawing.
ROW_TOP = 38
ROW_BOTTOM = 60
ROW_RIGHT = 171


def sensor_block(on):
    """The sensor block: a vanilla observer with a lit or dimmed diode."""
    src = Image.open(os.path.join(VANILLA, "observer_front.png")).convert("RGBA")
    img = src.copy()
    px = img.load()

    # There is no diode in observer_front (it is in _back), so we cut a small
    # recess for it and insert our own indicator.
    for (x, y) in LAMP:
        px[x, y] = (0x14, 0x10, 0x10, 255)
    if on:
        for (x, y) in LAMP:
            px[x, y] = LAMP_ON + (255,)
        px[7, 7] = LAMP_GLOW + (255,)
        # A brighter outline - the diode is meant to give the impression of glowing.
        for (x, y) in ((6, 7), (9, 7), (7, 6), (7, 9)):
            if 0 <= x < 16 and 0 <= y < 16:
                px[x, y] = (0x7A, 0x1A, 0x12, 255)
    else:
        # Dimmed, but visible - the player has to know where the indicator is.
        px[7, 7] = LAMP_OFF + (255,)
        px[8, 7] = (0x40, 0x08, 0x05, 255)
        px[7, 8] = (0x40, 0x08, 0x05, 255)
        px[8, 8] = (0x2A, 0x06, 0x04, 255)
    return img


def sensor_gui():
    """The GUI background: a panel like the extractor + ONE item slot. Nothing more."""
    img = Image.new("RGBA", (style.CANVAS, style.CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    style.panel(d)

    # The item slot (a ghost - the screen draws the icon). The rest of the row
    # are Minecraft widgets (a text field and buttons) that draw themselves.
    style.slot(d, FILTER_SLOT_X, FILTER_SLOT_Y)

    style.player_inventory(d)
    return img


def self_check(img):
    """Checks the GENERATED pixels: the slot frame where the constant says,
    and NO recess left over from the old status text.

    <p>Why: a player reported "a weird square where the text did not fit" - it
    was the recess for the status label, which we no longer draw. It is easy to
    bring it back by accident when copying an old fragment of the generator, and
    in game it looks like a graphics bug.
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
    if not slot_ok(FILTER_SLOT_X, FILTER_SLOT_Y):
        bad.append(f"item slot @ {FILTER_SLOT_X},{FILTER_SLOT_Y}")
    for row in range(3):
        for col in range(9):
            if not slot_ok(style.PLAYER_X + col * 18, style.PLAYER_Y + row * 18):
                bad.append(f"inventory r{row}c{col}")
    for col in range(9):
        if not slot_ok(style.PLAYER_X + col * 18, style.PLAYER_Y + 58):
            bad.append(f"hotbar c{col}")

    # The area where the recess for the status text used to be - it must be a
    # CLEAN panel.
    dirty = [(x, y) for y in range(58, 79) for x in range(26, 177)
             if img.getpixel((x, y)) != PANEL]
    if dirty:
        bad.append(f"the old square for the text is back ({len(dirty)} px, e.g. {dirty[0]})")

    # The whole row (slot + field + buttons) must be a clean panel apart from
    # the slot frame: the text field and the buttons draw themselves, so there
    # must be no recesses or frames there.
    row = [(x, y) for y in range(ROW_TOP, ROW_BOTTOM + 1)
           for x in range(FILTER_SLOT_X - 1, ROW_RIGHT + 1)
           if not inside_slot_frame(x, y) and img.getpixel((x, y)) != PANEL]
    if row:
        bad.append(f"the row is not a clean panel ({len(row)} px, e.g. {row[0]})")

    if bad:
        raise SystemExit("ERROR: the texture does not match the constants: " + ", ".join(bad))
    print("  self-check: item slot, inventory, no square and a clean row - OK")


def inside_slot_frame(x, y):
    """Whether a pixel belongs to the slot frame (including the 1 px border)."""
    return (FILTER_SLOT_X - 1 <= x <= FILTER_SLOT_X + 16
            and FILTER_SLOT_Y - 1 <= y <= FILTER_SLOT_Y + 16)


def main():
    os.makedirs(BLOCK_OUT, exist_ok=True)
    os.makedirs(GUI_OUT, exist_ok=True)

    if os.path.isdir(VANILLA):
        for name, on in (("threshold_sensor", False), ("threshold_sensor_on", True)):
            path = os.path.join(BLOCK_OUT, name + ".png")
            sensor_block(on).save(path)
            print("saved:", path)
    else:
        print("skipping the block textures - no vanilla reference", VANILLA)

    gui = sensor_gui()
    self_check(gui)
    path = os.path.join(GUI_OUT, "threshold_sensor.png")
    gui.save(path)
    print("saved:", path)

    print()
    print("Positions to keep in sync in the menu and the screen:")
    print(f"  item slot:  x={FILTER_SLOT_X} y={FILTER_SLOT_Y}")


if __name__ == "__main__":
    main()
