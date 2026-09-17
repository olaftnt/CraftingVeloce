#!/usr/bin/env python3
"""Texture sets "made of a different material" - as resource packs.

APPROACH (changed after feedback): instead of inventing graphics from scratch,
we take REAL vanilla blocks and repaint them into a material. Thanks to that
every block still looks like what it is - only made of something else:

    crafter           <- crafting_table_top   (crafting table)
    extractor         <- dropper_front        (dropper)
    velocity furnace  <- furnace_{front,side,top}
    electric furnace  <- blast_furnace_{front,side,top}
    pipe              <- the ORIGINAL pipe texture, only a different ACCENT
    controller        <- the ORIGINAL texture, only a different ACCENT
    wrench            <- the ORIGINAL texture, only a different ACCENT

The terminal is DELIBERATELY absent from the packs - it is meant to stay the
way it is.

The repaint works on luminance: we compute the luminance of every pixel of the
vanilla texture, stretch it to the full range (so that no contrast is lost) and
insert the colour from the material palette. The shape, the shading and the
details stay - only the material changes.

For the pipe, the controller and the wrench we repaint nothing but the accent:
we take the original hue (purple ~290 degrees) and swap only the hue for the
set's accent, preserving saturation and luminance. That is exactly "the same
scheme, a different accent colour".

Usage:
    python3 scripts/gen_material_textures.py
"""

import colorsys
import json
import os
import shutil

from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK_ROOT = os.path.join(REPO, "texture_packs")
VANILLA = "/tmp/matref"
MOD_BLOCK = os.path.join(REPO, "assets", "craftingveloce", "textures", "block")
MOD_ITEM = os.path.join(REPO, "assets", "craftingveloce", "textures", "item")

PACK_FORMAT = 34  # 1.20.5 - 1.21.1

# --- material palettes -----------------------------------------------------
# Each one is a ramp from the DARKEST to the BRIGHTEST, within the colour
# family of the given material. The output values were sampled from vanilla
# blocks (netherite_block, iron_block, obsidian, copper_block) and then
# stretched so that the range has enough contrast for machine details
# (e.g. the vanilla iron_block is an all-bright range only - there is nothing
# to paint a dark firebox with).
MATERIALS = {
    "netherite": {
        "label": "Netherite",
        "ramp": ["#141011", "#241e1f", "#332c2e", "#463f42",
                 "#5f575b", "#837a7e", "#a9a0a4"],
        "accent": (0xE0, 0x78, 0x20),      # red-hot metal
    },
    "iron": {
        "label": "Iron",
        "ramp": ["#33363b", "#4b4f55", "#666b72", "#868c94",
                 "#a8aeb6", "#ccd1d8", "#eef1f5"],
        "accent": (0x4D, 0x9E, 0xE0),      # steel blue
    },
    "obsidian": {
        "label": "Obsidian",
        "ramp": ["#050308", "#0b0714", "#140e22", "#1f1733",
                 "#2c2148", "#3f2f63", "#584387"],
        "accent": (0x9B, 0x5C, 0xFF),      # obsidian purple
    },
    "copper": {
        "label": "Copper",
        "ramp": ["#3b1d13", "#5c2f1e", "#84452b", "#a75a40",
                 "#c26b4c", "#d8865f", "#e8ab7e"],
        "accent": (0x2F, 0xBF, 0xA0),      # patina
    },
}


# --- helpers ---------------------------------------------------------------

def hex_rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def luminance(p):
    return (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / 255.0


def ramp_color(ramp, t):
    """A colour from the ramp for position t (0..1) - interpolated between steps."""
    t = max(0.0, min(1.0, t))
    pos = t * (len(ramp) - 1)
    i = int(pos)
    if i >= len(ramp) - 1:
        return ramp[-1]
    f = pos - i
    a, b = ramp[i], ramp[i + 1]
    return tuple(int(a[k] + (b[k] - a[k]) * f) for k in range(3))


def recolor_to_material(img, ramp):
    """Repaints a texture into a material, preserving SHAPE and SHADING.

    We stretch the source luminance to the full range - vanilla textures often
    use a narrow interval (e.g. iron_block is almost entirely light grey), so
    without the stretch the material would come out flat and detail-less.
    """
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size

    lums = [luminance(px[x, y]) for x in range(w) for y in range(h) if px[x, y][3] > 0]
    if not lums:
        return img
    lo, hi = min(lums), max(lums)
    span = max(1e-6, hi - lo)

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if p[3] == 0:
                continue
            t = (luminance(p) - lo) / span
            op[x, y] = ramp_color(ramp, t) + (p[3],)
    return out


def swap_accent_hue(img, accent):
    """Swaps ONLY the accent hue, leaving saturation and luminance alone.

    That is exactly how the original pipe and controller texture works: one
    vivid purple (hue ~290, saturation ~0.8) on a dark body. We therefore take
    only the hue of the target accent and leave saturation and luminance from
    the original - thanks to that the accent's shading does not disappear.
    """
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    ah, asat, av = colorsys.rgb_to_hsv(accent[0] / 255, accent[1] / 255, accent[2] / 255)

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if p[3] == 0:
                continue
            hue, sat, val = colorsys.rgb_to_hsv(p[0] / 255, p[1] / 255, p[2] / 255)
            deg = hue * 360.0
            # Accent = saturated purple. The body (sat < 0.3) is left as is,
            # because it is what gives "the same scheme" we were after.
            if sat > 0.30 and 250.0 <= deg <= 330.0:
                r, g, b = colorsys.hsv_to_rgb(ah, sat, val)
                op[x, y] = (int(r * 255), int(g * 255), int(b * 255), p[3])
            else:
                op[x, y] = p
    return out


def load_vanilla(name):
    path = os.path.join(VANILLA, name + ".png")
    if not os.path.exists(path):
        raise FileNotFoundError(f"missing vanilla texture: {path}")
    return Image.open(path)


# --- what we assemble ------------------------------------------------------
# Key = path under assets/craftingveloce/textures/.
# Value = function(material data) -> image.
def vanilla_base(name):
    """A repainted vanilla block."""
    def build(mat):
        return recolor_to_material(load_vanilla(name), [hex_rgb(c) for c in mat["ramp"]])
    return build


def mod_accent(rel_dir, name):
    """The mod's original texture with the accent swapped."""
    def build(mat):
        img = Image.open(os.path.join(rel_dir, name + ".png"))
        return swap_accent_hue(img, mat["accent"])
    return build


def sensor_base(on):
    """Sensor: vanilla observer + a diode in the material's accent colour."""
    def build(mat):
        img = recolor_to_material(load_vanilla("observer_front"),
                                  [hex_rgb(c) for c in mat["ramp"]])
        px = img.load()
        acc = mat["accent"]
        if on:
            px[7, 7] = tuple(min(255, int(c + (255 - c) * 0.5)) for c in acc) + (255,)
            for (x, y) in ((8, 7), (7, 8), (8, 8)):
                px[x, y] = acc + (255,)
        else:
            dim = tuple(int(c * 0.35) for c in acc)
            px[7, 7] = dim + (255,)
            for (x, y) in ((8, 7), (7, 8), (8, 8)):
                px[x, y] = tuple(int(c * 0.22) for c in acc) + (255,)
        return img
    return build


TEXTURES = {
    # Threshold sensor - the same body, the diode either lit or not.
    "block/threshold_sensor": sensor_base(False),
    "block/threshold_sensor_on": sensor_base(True),
    # Vanilla bases - every block looks like what it is.
    "block/veloce_crafting_table": vanilla_base("crafting_table_top"),
    "block/veloce_extractor": vanilla_base("dropper_front"),
    "block/velocity_furnace_front": vanilla_base("furnace_front"),
    "block/velocity_furnace_side": vanilla_base("furnace_side"),
    "block/velocity_furnace_top": vanilla_base("furnace_top"),
    "block/electric_furnace_front": vanilla_base("blast_furnace_front"),
    "block/electric_furnace_side": vanilla_base("blast_furnace_side"),
    "block/electric_furnace_top": vanilla_base("furnace_top"),
    # The mod's original scheme, a different accent.
    "block/veloce_pipe": mod_accent(MOD_BLOCK, "veloce_pipe"),
    "block/veloce_controller": mod_accent(MOD_BLOCK, "veloce_controller"),
    "item/wrench": mod_accent(MOD_ITEM, "wrench"),
}

# The terminal is DELIBERATELY outside the set - it is meant to stay unchanged.


def build_pack(key, mat):
    root = os.path.join(PACK_ROOT, key)
    if os.path.isdir(root):
        shutil.rmtree(root)
    tex = os.path.join(root, "assets", "craftingveloce", "textures")
    os.makedirs(tex, exist_ok=True)

    with open(os.path.join(root, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {
            "pack_format": PACK_FORMAT,
            "description": f"Veloce - {mat['label']} machines (preview)",
        }}, f, indent=2)
        f.write("\n")

    made = []
    for rel, build in TEXTURES.items():
        img = build(mat)
        path = os.path.join(tex, rel + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        made.append((rel, img))

    # Pack icon: the crafting table in this material - you can tell at a glance
    # which one it is.
    icon = dict(made)["block/veloce_crafting_table"]
    icon.resize((128, 128), Image.NEAREST).save(os.path.join(root, "pack.png"))
    return made


def contact_sheet(made, path):
    scale = 6
    cell = 16 * scale
    cols = 6
    rows = (len(made) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * (cell + 8) + 8, rows * (cell + 8) + 8),
                      (198, 198, 198, 255))
    for i, (_, img) in enumerate(made):
        big = img.resize((cell, cell), Image.NEAREST)
        sheet.alpha_composite(big, (8 + (i % cols) * (cell + 8),
                                    8 + (i // cols) * (cell + 8)))
    sheet.save(path)
    return path


def main():
    os.makedirs(PACK_ROOT, exist_ok=True)
    # Old ender sets - rejected as too garish.
    for old in ("ender_deep", "ender_pearl"):
        d = os.path.join(PACK_ROOT, old)
        if os.path.isdir(d):
            shutil.rmtree(d)
            print("removed old set:", old)

    for key, mat in MATERIALS.items():
        made = build_pack(key, mat)
        sheet = contact_sheet(made, os.path.join(PACK_ROOT, key, "PREVIEW.png"))
        print(f"{key:10s} {len(made)} textures -> {os.path.join(PACK_ROOT, key)}")
        print(f"           preview: {sheet}")


if __name__ == "__main__":
    main()
