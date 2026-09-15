#!/usr/bin/env python3
"""Zestawy tekstur "z innego materialu" - jako resource packi.

PODEJSCIE (zmienione po uwagach): zamiast wymyslac grafike od zera, bierzemy
PRAWDZIWE waniliowe bloki i przemalowujemy je na material. Dzieki temu kazdy
blok nadal wyglada jak to, czym jest - tylko zrobiony z czegos innego:

    crafter           <- crafting_table_top   (stol craftingu)
    extractor         <- dropper_front        (podajnik)
    velocity furnace  <- furnace_{front,side,top}
    electric furnace  <- blast_furnace_{front,side,top}
    pipe              <- ORYGINALNA tekstura rury, tylko inny AKCENT
    controller        <- ORYGINALNA tekstura, tylko inny AKCENT
    wrench            <- ORYGINALNA tekstura, tylko inny AKCENT

Terminala CELOWO nie ma w paczkach - ma zostac taki, jaki jest.

Przemalowanie dziala na jasnosci: liczymy jasnosc kazdego piksela waniliowej
tekstury, rozciagamy ja do pelnego zakresu (zeby nie stracic kontrastu)
i wstawiamy kolor z palety materialu. Ksztalt, cienie i detale zostaja -
zmienia sie tylko material.

Dla rury, kontrolera i klucza NIE przemalowujemy niczego poza akcentem:
bierzemy oryginalny odcien (fiolet ~290 stopni) i podmieniamy sam odcien na
akcent zestawu, zachowujac nasycenie i jasnosc. Czyli dokladnie "ten sam
schemat, inny kolor akcentowy".

Uruchomienie:
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

# --- palety materialow -----------------------------------------------------
# Kazda to prog od NAJCIEMNIEJSZEGO do NAJAJASNIEJSZEGO, w rodzinie koloru
# danego materialu. Wartosci wyjsciowe wyprobowane z waniliowych blokow
# (netherite_block, iron_block, obsidian, copper_block), a nastepnie
# rozciagniete tak, zeby zakres mial dosc kontrastu na detale maszyn
# (np. waniliowy iron_block to sam jasny zakres - nie ma czym pomalowac
# ciemnego paleniska).
MATERIALS = {
    "netherite": {
        "label": "Netherite",
        "ramp": ["#141011", "#241e1f", "#332c2e", "#463f42",
                 "#5f575b", "#837a7e", "#a9a0a4"],
        "accent": (0xE0, 0x78, 0x20),      # rozzartzony metal
    },
    "iron": {
        "label": "Iron",
        "ramp": ["#33363b", "#4b4f55", "#666b72", "#868c94",
                 "#a8aeb6", "#ccd1d8", "#eef1f5"],
        "accent": (0x4D, 0x9E, 0xE0),      # stalowy błękit
    },
    "obsidian": {
        "label": "Obsidian",
        "ramp": ["#050308", "#0b0714", "#140e22", "#1f1733",
                 "#2c2148", "#3f2f63", "#584387"],
        "accent": (0x9B, 0x5C, 0xFF),      # fiolet obsydianu
    },
    "copper": {
        "label": "Copper",
        "ramp": ["#3b1d13", "#5c2f1e", "#84452b", "#a75a40",
                 "#c26b4c", "#d8865f", "#e8ab7e"],
        "accent": (0x2F, 0xBF, 0xA0),      # patyna
    },
}


# --- narzedzia -------------------------------------------------------------

def hex_rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def luminance(p):
    return (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / 255.0


def ramp_color(ramp, t):
    """Kolor z progu dla pozycji t (0..1) - z interpolacja miedzy stopniami."""
    t = max(0.0, min(1.0, t))
    pos = t * (len(ramp) - 1)
    i = int(pos)
    if i >= len(ramp) - 1:
        return ramp[-1]
    f = pos - i
    a, b = ramp[i], ramp[i + 1]
    return tuple(int(a[k] + (b[k] - a[k]) * f) for k in range(3))


def recolor_to_material(img, ramp):
    """Przemalowuje teksture na material, zachowujac KSZTALT i CIENIE.

    Jasnosc zrodla rozciagamy do pelnego zakresu - waniliowe tekstury czesto
    uzywaja waskiego przedzialu (np. iron_block to prawie sam jasny szary),
    wiec bez rozciagniecia material wyszedlby plaski i bez detali.
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
    """Podmienia TYLKO odcien akcentu, zostawiajac nasycenie i jasnosc.

    Tak wlasnie dziala oryginalna tekstura rury i kontrolera: jeden wyrazisty
    fiolet (hue ~290, nasycenie ~0.8) na ciemnym korpusie. Bierzemy wiec sam
    odcien docelowego akcentu, a nasycenie i jasnosc zostawiamy z oryginalu -
    dzieki temu cieniowanie akcentu nie zginie.
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
            # Akcent = nasycony fiolet. Korpus (sat < 0.3) zostaje bez zmian,
            # bo to on daje "ten sam schemat", o ktory chodzilo.
            if sat > 0.30 and 250.0 <= deg <= 330.0:
                r, g, b = colorsys.hsv_to_rgb(ah, sat, val)
                op[x, y] = (int(r * 255), int(g * 255), int(b * 255), p[3])
            else:
                op[x, y] = p
    return out


def load_vanilla(name):
    path = os.path.join(VANILLA, name + ".png")
    if not os.path.exists(path):
        raise FileNotFoundError(f"brak waniliowej tekstury: {path}")
    return Image.open(path)


# --- co skladamy -----------------------------------------------------------
# Klucz = sciezka w assets/craftingveloce/textures/.
# Wartosc = funkcja(dane materialu) -> obraz.
def vanilla_base(name):
    """Przemalowany waniliowy blok."""
    def build(mat):
        return recolor_to_material(load_vanilla(name), [hex_rgb(c) for c in mat["ramp"]])
    return build


def mod_accent(rel_dir, name):
    """Oryginalna tekstura moda z podmienionym akcentem."""
    def build(mat):
        img = Image.open(os.path.join(rel_dir, name + ".png"))
        return swap_accent_hue(img, mat["accent"])
    return build


TEXTURES = {
    # Waniliowe podstawy - kazdy blok wyglada jak to, czym jest.
    "block/veloce_crafting_table": vanilla_base("crafting_table_top"),
    "block/veloce_extractor": vanilla_base("dropper_front"),
    "block/velocity_furnace_front": vanilla_base("furnace_front"),
    "block/velocity_furnace_side": vanilla_base("furnace_side"),
    "block/velocity_furnace_top": vanilla_base("furnace_top"),
    "block/electric_furnace_front": vanilla_base("blast_furnace_front"),
    "block/electric_furnace_side": vanilla_base("blast_furnace_side"),
    "block/electric_furnace_top": vanilla_base("furnace_top"),
    # Oryginalny schemat moda, inny akcent.
    "block/veloce_pipe": mod_accent(MOD_BLOCK, "veloce_pipe"),
    "block/veloce_controller": mod_accent(MOD_BLOCK, "veloce_controller"),
    "item/wrench": mod_accent(MOD_ITEM, "wrench"),
}

# Terminal CELOWO poza zestawem - ma zostac bez zmian.


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

    # Ikona packa: stol craftingu w tym materiale - od razu widac, ktory to.
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
    # Stare zestawy endera - odrzucone jako zbyt oczojebne.
    for old in ("ender_deep", "ender_pearl"):
        d = os.path.join(PACK_ROOT, old)
        if os.path.isdir(d):
            shutil.rmtree(d)
            print("usunieto stary zestaw:", old)

    for key, mat in MATERIALS.items():
        made = build_pack(key, mat)
        sheet = contact_sheet(made, os.path.join(PACK_ROOT, key, "PREVIEW.png"))
        print(f"{key:10s} {len(made)} tekstur -> {os.path.join(PACK_ROOT, key)}")
        print(f"           podglad: {sheet}")


if __name__ == "__main__":
    main()
