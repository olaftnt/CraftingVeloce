#!/usr/bin/env python3
"""Generuje DWA zestawy tekstur moda w stylu Endera, jako resource packi.

Oba zestawy sa zbudowane na AUTENTYCZNEJ palecie wanilii - kolory wyprobowane
z prawdziwych tekstur Minecrafta:

    ender_pearl     #032620 #0b4d42 #105e51 #258474 #349988
    ender_eye       #102c31 #1e4835 #316364 #4e8386 #659b7d #71ac49
    end_portal_top  #061914 #24433b #2f584e #427367

Swiadomie POMIJAMY tony end stone (#c5be8b..#f6fabd) - zgodnie z ustaleniem
"jak ender portal, ale bez end stona". Ciemnozielone elementy Endera, jak
w ender chest, zostaja.

Dwa zestawy roznia sie Nastrojem, nie tylko odcieniem:

    ender_deep   - jak ender chest / rama portalu: prawie czarna zielen
                   z osobnymi jasnymi akcentami perly. Mroczny, kontrastowy.
    ender_pearl  - jak ender perla / oko: srednia zielen morska, wiecej
                   jasnych perełkowych refleksow. Miekszy, "zywszy".

Zestawy NIE sa wdrazane do moda. Leza jako gotowe resource packi, zeby dalo
sie je podmieniac w grze na zywo i wybrac lepszy.

Uruchomienie:
    python3 scripts/gen_ender_textures.py
"""

import json
import os
import random
import shutil

from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK_ROOT = os.path.join(REPO, "texture_packs")

# Wersja formatu resource packa dla 1.21.1 (1.20.5 - 1.21.1 => 34).
PACK_FORMAT = 34

# --- palety ---------------------------------------------------------------
# Kazdy kolor ma nazwe odpowiadajaca roli, a nie odcieniowi: dzieki temu oba
# zestawy korzystaja z TEGO SAMEGO kodu rysujacego, a roznia sie wylacznie
# paleta.
ENDER_DEEP = {
    "edge_dark": (0x03, 0x10, 0x0D),
    "body_dark": (0x06, 0x19, 0x14),
    "body": (0x0E, 0x2A, 0x26),
    "body_mid": (0x17, 0x3D, 0x35),
    "body_light": (0x24, 0x54, 0x4A),
    "edge_light": (0x2F, 0x6A, 0x5C),
    "accent_lo": (0x0B, 0x4D, 0x42),
    "accent": (0x25, 0x84, 0x74),
    "accent_hi": (0x43, 0xB3, 0x9C),
    "glow": (0x5F, 0xE0, 0xC0),
    "glass": (0x02, 0x0C, 0x0A),
}

ENDER_PEARL = {
    "edge_dark": (0x06, 0x19, 0x14),
    "body_dark": (0x10, 0x2C, 0x31),
    "body": (0x1E, 0x48, 0x35),
    "body_mid": (0x2F, 0x58, 0x4E),
    "body_light": (0x42, 0x73, 0x67),
    "edge_light": (0x65, 0x9B, 0x7D),
    "accent_lo": (0x1B, 0x5F, 0x52),
    "accent": (0x34, 0x99, 0x88),
    "accent_hi": (0x71, 0xAC, 0x49),
    "glow": (0x8F, 0xE0, 0x7A),
    "glass": (0x06, 0x14, 0x14),
}

PALETTES = {"ender_deep": ENDER_DEEP, "ender_pearl": ENDER_PEARL}

# --- narzedzia pikselowe --------------------------------------------------


def shade(c, amount):
    """Rozjasnia (amount > 0) albo przyciemnia (amount < 0) kolor."""
    r, g, b = c[:3]
    if amount >= 0:
        return (min(255, int(r + (255 - r) * amount)),
                min(255, int(g + (255 - g) * amount)),
                min(255, int(b + (255 - b) * amount)))
    k = 1.0 + amount
    return (max(0, int(r * k)), max(0, int(g * k)), max(0, int(b * k)))


def blank(size=16):
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def body_fill(px, pal, rng, x0=0, y0=0, x1=15, y1=15, base="body", spread=0.05):
    """Wypelnia obszar kolorem bazowym z drobnym szumem.

    Szum jest konieczny: duze plaskie powierzchnie w Minecrafcie wygladaja jak
    plastik, a waniliowe tekstury ZAWSZE maja mikro-zmiennosc.
    """
    color = pal[base]
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = shade(color, rng.uniform(-spread, spread)) + (255,)


def bevel(px, pal, x0=0, y0=0, x1=15, y1=15):
    """Gorna i lewa krawedz jasniejsza, dolna i prawa ciemniejsza."""
    for x in range(x0, x1 + 1):
        px[x, y0] = shade(pal["edge_light"], 0.05) + (255,)
        px[x, y1] = shade(pal["edge_dark"], -0.15) + (255,)
    for y in range(y0, y1 + 1):
        px[x0, y] = shade(pal["edge_light"], 0.0) + (255,)
        px[x1, y] = shade(pal["edge_dark"], -0.10) + (255,)


def panel(px, pal, x0, y0, x1, y1, rng, base="body_dark", top_hi=True):
    """Wciete pole: ciemne tlo z jasna krawedzia od gory, ciemna od dolu."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = shade(pal[base], rng.uniform(-0.04, 0.04)) + (255,)
    for x in range(x0, x1 + 1):
        px[x, y0] = shade(pal["edge_dark"], 0.0) + (255,)
    if top_hi:
        for x in range(x0, x1 + 1):
            px[x, y0 + 1] = shade(pal["body_light"], 0.0) + (255,)


def rivet(px, pal, x, y):
    px[x, y] = shade(pal["edge_light"], 0.30) + (255,)
    if y + 1 <= 15:
        px[x, y + 1] = shade(pal["edge_dark"], -0.30) + (255,)


def rivets(px, pal):
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        rivet(px, pal, x, y)


def pearl_vein(px, pal, rng, count=3):
    """Kilka perelkowych pikseli - slad Endera, jak na ender perle."""
    for _ in range(count):
        x = rng.randint(2, 13)
        y = rng.randint(2, 13)
        px[x, y] = pal["accent"] + (255,)
        if rng.random() < 0.5 and x + 1 <= 15:
            px[x + 1, y] = pal["accent_lo"] + (255,)


# --- generatory poszczegolnych tekstur ------------------------------------


def tex_pipe(pal, rng):
    """Rura: jednolity stop Endera.

    Model rury tnie te teksture na plastry, wiec NIE moze miec wyraznego
    kierunku ani duzego motywu - kazdy wzor powtarzalby sie na kazdym odcinku.
    Dlatego: jednolita faktura + jeden dlugi akcent, ktory na rurze czyta sie
    jako podswietlony rdzen.
    """
    img = blank()
    px = img.load()
    body_fill(px, pal, rng, base="body_mid", spread=0.06)
    # Rdzen: pozioma linia akcentu przez srodek.
    for x in range(16):
        px[x, 7] = pal["accent_lo"] + (255,)
        px[x, 8] = pal["accent"] + (255,)
    # Podswietlenie rdzenia tylko na srodku szerokosci - daje efekt rury.
    for x in range(4, 12):
        px[x, 8] = pal["accent_hi"] + (255,)
    # Delikatna faktura zamiast gladkiej plamy.
    for _ in range(14):
        x, y = rng.randint(0, 15), rng.randint(0, 15)
        px[x, y] = shade(px[x, y], rng.uniform(-0.10, 0.10)) + (255,)
    bevel(px, pal)
    return img


def tex_controller(pal, rng):
    """Kontroler: panel z ekranem i rzedem kontrolek."""
    img = blank()
    px = img.load()
    body_fill(px, pal, rng)
    # Ekran.
    panel(px, pal, 3, 3, 12, 8, rng, base="glass")
    # "Dane" na ekranie: krotkie paski w trzech rzedach.
    rows = [(4, 2, 9), (6, 2, 7), (7, 3, 10)]
    for y, x0, x1 in rows:
        for x in range(x0, x1 + 1):
            px[x, y] = pal["accent"] + (255,)
    px[4, 7] = pal["glow"] + (255,)   # kursor
    # Trzy kontrolki pod ekranem.
    for i, key in enumerate(("accent_lo", "accent", "accent_hi")):
        px[5 + i * 3, 11] = pal[key] + (255,)
    # Szczelina wentylacyjna.
    for x in range(3, 13):
        px[x, 13] = shade(pal["edge_dark"], -0.2) + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_crafting_table(pal, rng):
    """Stol craftingu: wcieta plyta z siatka 3x3."""
    img = blank()
    px = img.load()
    body_fill(px, pal, rng)
    panel(px, pal, 2, 2, 13, 13, rng)
    # Siatka 3x3 - rowek miedzy komorkami.
    for x in range(2, 14):
        px[x, 6] = shade(pal["edge_dark"], -0.1) + (255,)
        px[x, 10] = shade(pal["edge_dark"], -0.1) + (255,)
    for y in range(2, 14):
        px[6, y] = shade(pal["edge_dark"], -0.1) + (255,)
        px[10, y] = shade(pal["edge_dark"], -0.1) + (255,)
    # Perelka w srodkowej komorce - znak, ze to stol Veloce.
    px[8, 8] = pal["accent"] + (255,)
    px[9, 8] = pal["accent_hi"] + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_extractor(pal, rng):
    """Ekstraktor: dysza z perelkowym rdzeniem."""
    img = blank()
    px = img.load()
    body_fill(px, pal, rng)
    # Dysza: wciety pierscien.
    panel(px, pal, 4, 4, 11, 11, rng, base="glass")
    for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
        px[x, y] = shade(pal["edge_dark"], 0.0) + (255,)
    # Rdzen - swieci.
    px[7, 7] = pal["accent"] + (255,)
    px[8, 7] = pal["accent_hi"] + (255,)
    px[7, 8] = pal["accent_hi"] + (255,)
    px[8, 8] = pal["glow"] + (255,)
    # Cztery szczeliny wokol dyszy.
    for i in range(4):
        px[2 + i * 4, 2] = shade(pal["edge_dark"], -0.1) + (255,)
        px[2 + i * 4, 13] = shade(pal["edge_dark"], -0.1) + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_terminal_back(pal, rng):
    """Tyl terminala: gniazdo kabla."""
    img = blank()
    px = img.load()
    body_fill(px, pal, rng)
    panel(px, pal, 5, 6, 10, 9, rng, base="glass")
    for x in range(6, 10):
        px[x, 7] = pal["accent_lo"] + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_terminal_side(pal, rng):
    """Bok terminala: pionowe szczeliny."""
    img = blank()
    px = img.load()
    body_fill(px, pal, rng)
    for x in (4, 7, 10):
        for y in range(4, 12):
            px[x, y] = shade(pal["edge_dark"], -0.15) + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_terminal_front(pal, rng):
    """Przod terminala: ekran z liniami danych.

    UWAGA: model probkuje z tego pliku obszar 0..16, mimo ze plik jest 32x32 -
    czyli realnie uzywa LEWEGO GORNEGO kwadratu 16x16. Rysujemy wiec 16x16,
    a potem kafelkujemy 2x2, zeby plik byl spojny niezaleznie od tego, co
    kiedykolwiek go odczyta.
    """
    img = blank()
    px = img.load()
    # Rama.
    body_fill(px, pal, rng)
    # Ekran.
    panel(px, pal, 1, 1, 14, 12, rng, base="glass")
    # Linie tekstu: kazda inna dlugosc, jak prawdziwy log.
    widths = [13, 9, 12, 6, 11, 8, 10]
    for i, w in enumerate(widths):
        y = 2 + i
        for x in range(2, 2 + w):
            px[x, y] = pal["accent_lo"] + (255,)
    # Podswietlone dwie linie - "aktywne".
    for x in range(2, 10):
        px[x, 3] = pal["accent"] + (255,)
    for x in range(2, 7):
        px[x, 8] = pal["accent_hi"] + (255,)
    # Pasek statusu na dole ekranu.
    for x in range(1, 15):
        px[x, 13] = pal["accent_lo"] + (255,)
    px[13, 13] = pal["glow"] + (255,)
    rivets(px, pal)
    bevel(px, pal)

    # Kafelkowanie 2x2 do 32x32.
    out = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    for ox in (0, 16):
        for oy in (0, 16):
            out.paste(img, (ox, oy))
    return out


def _furnace_shell(pal, rng):
    img = blank()
    px = img.load()
    body_fill(px, pal, rng)
    return img, px


def tex_velocity_furnace_front(pal, rng):
    """Piec paliwowy: palenisko z kratownica, cieplo w tonacji Endera."""
    img, px = _furnace_shell(pal, rng)
    # Ramka paleniska.
    panel(px, pal, 2, 4, 13, 12, rng, base="glass")
    # Kratownica: dolne prety rozgrzane najmocniej.
    heat = {10: "glow", 9: "accent_hi", 7: "accent", 6: "accent_lo"}
    for y, key in heat.items():
        for x in range(3, 13):
            px[x, y] = pal[key] + (255,)
    # Zimne prety miedzy nimi.
    for y in (5, 8, 11):
        for x in range(3, 13):
            px[x, y] = shade(pal["edge_dark"], -0.1) + (255,)
    # Poprzeczne zebra.
    for x in (5, 8, 11):
        for y in range(5, 12):
            px[x, y] = shade(px[x, y], -0.30) + (255,)
    # Kontrolka.
    px[13, 2] = pal["accent_hi"] + (255,)
    bevel(px, pal)
    return img


def tex_velocity_furnace_side(pal, rng):
    img, px = _furnace_shell(pal, rng)
    for py in (4, 9):
        for x in range(3, 13):
            px[x, py] = shade(pal["edge_dark"], -0.25) + (255,)
            px[x, py + 1] = shade(pal["edge_dark"], -0.05) + (255,)
    for y in range(6, 8):
        px[15, y] = pal["accent"] + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_velocity_furnace_top(pal, rng):
    img, px = _furnace_shell(pal, rng)
    panel(px, pal, 3, 3, 12, 12, rng, base="body_dark")
    for x in range(5, 11):
        px[x, 7] = shade(pal["body_light"], 0.05) + (255,)
        px[x, 8] = shade(pal["edge_dark"], -0.15) + (255,)
    for y in range(5, 11):
        px[7, y] = shade(pal["body_light"], 0.05) + (255,)
        px[8, y] = shade(pal["edge_dark"], -0.15) + (255,)
    px[7, 7] = pal["accent"] + (255,)
    px[8, 8] = pal["accent_lo"] + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_electric_furnace_front(pal, rng):
    """Piec elektryczny: komora z lukiem, bez ognia."""
    img, px = _furnace_shell(pal, rng)
    panel(px, pal, 2, 4, 13, 12, rng, base="glass")
    # Rownomierne pole grzewcze - nie ogien, tylko prad.
    for y in (6, 8, 10):
        for x in range(3, 13):
            px[x, y] = pal["accent"] + (255,)
    for x in range(3, 13):
        px[x, 7] = pal["accent_lo"] + (255,)
        px[x, 9] = pal["accent_lo"] + (255,)
    # Błyskawica - czytelny znak "elektryczny".
    bolt = [(8, 5), (8, 6), (7, 7), (8, 7), (9, 8), (8, 9), (8, 10)]
    for (x, y) in bolt:
        px[x, y] = pal["glow"] + (255,)
    px[13, 2] = pal["glow"] + (255,)
    bevel(px, pal)
    return img


def tex_electric_furnace_side(pal, rng):
    img, px = _furnace_shell(px_pal := pal, rng)
    # Pionowy przewod energii.
    for y in range(3, 13):
        px[7, y] = pal["accent_lo"] + (255,)
        px[8, y] = pal["accent"] + (255,)
    for y in (5, 9):
        for x in range(3, 13):
            px[x, y] = shade(pal["edge_dark"], -0.2) + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_electric_furnace_top(pal, rng):
    img, px = _furnace_shell(pal, rng)
    panel(px, pal, 3, 3, 12, 12, rng, base="body_dark")
    # Perla w srodku - akumulator.
    px[7, 7] = pal["accent"] + (255,)
    px[8, 7] = pal["accent_hi"] + (255,)
    px[7, 8] = pal["accent_hi"] + (255,)
    px[8, 8] = pal["glow"] + (255,)
    for (x, y) in ((8, 5), (8, 10), (5, 8), (10, 8)):
        px[x, y] = pal["accent_lo"] + (255,)
    rivets(px, pal)
    bevel(px, pal)
    return img


def tex_wrench(pal, rng):
    """Klucz: ksztalt na przezroczystym tle.

    Rysowany z JAWNEJ mapy znakow, a nie z rownan. Przy 16 px kazdy piksel
    decyduje o czytelnosci, a proba opisania klucza petlami dawala cienkie,
    urywane ramie. Mapa pozwala zobaczyc ksztalt w kodzie i sprawdzic go
    bez uruchamiania gry.

        o = dziob (akcent), # = trzon, + = rozjasnienie krawedzi,
        - = cien, . = przezroczyste

    Uklad: otwarty dziob u gory po prawej, grube (3 px) ramie w dol po lewo.
    """
    art = [
        "................",
        "..........oo....",
        ".........o..o...",
        ".........o..o...",
        ".........o..o...",
        "..........oo....",
        "........+##-....",
        ".......+##-.....",
        "......+##-......",
        ".....+##-.......",
        "....+##-........",
        "...+##-.........",
        "..+##-..........",
        "..+##-..........",
        "................",
        "................",
    ]
    img = blank()
    px = img.load()
    keys = {"o": "accent", "#": "body_mid", "+": "body_light", "-": "edge_dark"}
    for y, row in enumerate(art):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            px[x, y] = pal[keys[ch]] + (255,)
    # Rozjasnienie dzioba: gorny prawy luk lapie swiatlo.
    px[11, 1] = pal["accent_hi"] + (255,)
    px[12, 2] = pal["glow"] + (255,)
    px[10, 2] = pal["accent_hi"] + (255,)
    return img


# --- co generujemy --------------------------------------------------------
# Klucz to sciezka wewnatrz assets/craftingveloce/textures/.
TEXTURES = {
    "block/veloce_pipe": tex_pipe,
    "block/veloce_controller": tex_controller,
    "block/veloce_crafting_table": tex_crafting_table,
    "block/veloce_extractor": tex_extractor,
    "block/veloce_tom_terminal_back": tex_terminal_back,
    "block/veloce_tom_terminal_side": tex_terminal_side,
    "block/veloce_tom_terminal_front": tex_terminal_front,
    "block/velocity_furnace_front": tex_velocity_furnace_front,
    "block/velocity_furnace_side": tex_velocity_furnace_side,
    "block/velocity_furnace_top": tex_velocity_furnace_top,
    "block/electric_furnace_front": tex_electric_furnace_front,
    "block/electric_furnace_side": tex_electric_furnace_side,
    "block/electric_furnace_top": tex_electric_furnace_top,
    "item/wrench": tex_wrench,
}

PACK_META = {
    "pack": {
        "pack_format": PACK_FORMAT,
        "description": "Veloce - %s ender textures (preview)",
    }
}


def build_pack(name, pal, description):
    """Sklada kompletny resource pack dla jednej palety."""
    root = os.path.join(PACK_ROOT, name)
    if os.path.isdir(root):
        shutil.rmtree(root)
    tex_dir = os.path.join(root, "assets", "craftingveloce", "textures")
    os.makedirs(tex_dir, exist_ok=True)

    with open(os.path.join(root, "pack.mcmeta"), "w") as f:
        meta = json.loads(json.dumps(PACK_META))
        meta["pack"]["description"] = meta["pack"]["description"] % description
        json.dump(meta, f, indent=2)
        f.write("\n")

    made = []
    for rel, gen in TEXTURES.items():
        # Staly seed na plik: dwa uruchomienia daja IDENTYCZNA grafike, wiec
        # "podoba mi sie bardziej" nie zalezy od losowego szumu.
        rng = random.Random(hash((name, rel)) & 0xFFFFFF)
        img = gen(pal, rng)
        path = os.path.join(tex_dir, rel + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        made.append((rel, img))

    # Ikona packa: powiekszenie kontrolera - od razu widac, ktory to zestaw.
    icon_src = dict(made)["block/veloce_controller"]
    icon_src.resize((128, 128), Image.NEAREST).save(os.path.join(root, "pack.png"))
    return made


def contact_sheet(made, path):
    """Tablica podgladowa: wszystko obok siebie, powiekszone 6x."""
    scale = 6
    cell = 16 * scale
    cols = 5
    rows = (len(made) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * (cell + 8) + 8, rows * (cell + 8) + 8),
                      (24, 24, 28, 255))
    for i, (rel, img) in enumerate(made):
        big = img.resize((cell, cell), Image.NEAREST)
        cx = 8 + (i % cols) * (cell + 8)
        cy = 8 + (i // cols) * (cell + 8)
        sheet.alpha_composite(big, (cx, cy))
    sheet.save(path)
    return path


def main():
    os.makedirs(PACK_ROOT, exist_ok=True)
    for name, pal, desc in (("ender_deep", ENDER_DEEP, "Deep"),
                            ("ender_pearl", ENDER_PEARL, "Pearl")):
        made = build_pack(name, pal, desc)
        sheet = os.path.join(PACK_ROOT, name, "PREVIEW.png")
        contact_sheet(made, sheet)
        print(f"{name}: {len(made)} tekstur -> {os.path.join(PACK_ROOT, name)}")
        print(f"  podglad: {sheet}")


if __name__ == "__main__":
    main()
