#!/usr/bin/env python3
"""Generuje tekstury 16x16 dla dwoch nowych piecow.

Tekstur nie ma kto narysowac, a bez nich bloki renderuja sie jako brakujacy
model (rozowo-czarny / przezroczysty). Ten skrypt robi PORZADNE placeholdery
w palecie moda, zeby piece od razu wygladaly jak czesc zestawu i zeby dalo sie
je odroznic od siebie na pierwszy rzut oka:

    Velocity Furnace          - purpurowy akcent (cieplo / paliwo)
    Velocity Electric Furnace - niebieski akcent (elektrycznosc)

Taki sam podzial kolorow jest juz w modzie: kontroler, ekstraktor i rura sa
purpurowe, a stol craftingu niebieski. Czyli kolor mowi od razu, czym blok
jest napedzany.

Podmiana na prawdziwa grafike: nadpisz PNG o tej samej nazwie i rozmiarze
16x16 - nic wiecej nie trzeba zmieniac.

Uruchomienie:
    python3 scripts/gen_furnace_textures.py
"""

import os
import random

from PIL import Image

OUT_DIR = os.path.join("assets", "craftingveloce", "textures", "block")

# --- paleta moda ---------------------------------------------------------
# Wyprobowane z istniejacych tekstur (kontroler / ekstraktor / stol), zeby
# piece nie odstawaly stylistycznie.
CASING = (0x3C, 0x2F, 0x3C)      # zasadnicza obudowa
CASING_DARK = (0x23, 0x1A, 0x24)  # cien / zaglebienie
CASING_LIGHT = (0x56, 0x48, 0x55)  # wypuklosc
CASING_MID = (0x46, 0x38, 0x46)

HEAT_GLOW = (0xC9, 0x2D, 0xEA)   # purpurowy blysk - cieplo
HEAT_DEEP = (0x8C, 0x1E, 0xA5)
VOLT_GLOW = (0x3C, 0x46, 0xFF)   # niebieski blysk - prad
VOLT_DEEP = (0x21, 0x74, 0xAA)

FIREBOX = (0x16, 0x12, 0x1A)     # wnetrze paleniska

SIZE = 16


def shade(color, amount):
    """Rozjasnia (amount > 0) albo przyciemnia (amount < 0) kolor."""
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
    """Szum - bez niego duze plaskie powierzchnie wygladaja jak plastik."""
    px[x, y] = shade(base, rng.uniform(-spread, spread))


def fill_casing(px, rng):
    """Wypelnia cala teksture obudowa z delikatnym szumem."""
    for y in range(SIZE):
        for x in range(SIZE):
            noisy(px, x, y, CASING, rng)


def bevel(px):
    """Gorna krawedz jasniejsza, dolna ciemniejsza - czytelna bryla."""
    for x in range(SIZE):
        px[x, 0] = shade(CASING_LIGHT, 0.10)
        px[x, SIZE - 1] = shade(CASING_DARK, -0.25)
    for y in range(SIZE):
        px[0, y] = shade(CASING_LIGHT, 0.05)
        px[SIZE - 1, y] = shade(CASING_DARK, -0.20)


def rivet(px, x, y):
    """Nitro: jasny piksel z ciemnym cieniem pod spodem."""
    px[x, y] = shade(CASING_LIGHT, 0.35)
    if y + 1 < SIZE:
        px[x, y + 1] = shade(CASING_DARK, -0.30)


def side_texture(accent, seed):
    """Bok: obudowa, dwa panele wentylacyjne, nity w narozach."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), CASING + (255,))
    px = img.load()
    fill_casing(px, rng)

    # Dwa poziome panele wentylacyjne - szczeliny.
    for panel_y in (4, 9):
        for x in range(3, 13):
            px[x, panel_y] = shade(CASING_DARK, -0.30)
            px[x, panel_y + 1] = shade(CASING_DARK, -0.10)

    # Nity w narozach - sugeruja skrecana obudowe.
    for (rx, ry) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(px, rx, ry)

    # Cienki pasek akcentu - odroznia piece od siebie z boku.
    for y in range(6, 8):
        px[15, y] = accent

    bevel(px)
    return img


def top_texture(accent, seed):
    """Gora: wpuszczona pokrywa z krzyzowym wzmocnieniem."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), CASING + (255,))
    px = img.load()
    fill_casing(px, rng)

    # Wpuszczony kwadrat.
    for y in range(3, 13):
        for x in range(3, 13):
            px[x, y] = shade(CASING_MID, rng.uniform(-0.06, 0.06))
    for x in range(3, 13):
        px[x, 3] = shade(CASING_DARK, -0.35)
        px[x, 12] = shade(CASING_DARK, -0.15)
    for y in range(3, 13):
        px[3, y] = shade(CASING_DARK, -0.35)
        px[12, y] = shade(CASING_DARK, -0.15)

    # Wzmocnienie na krzyz.
    for x in range(5, 11):
        px[x, 7] = shade(CASING_LIGHT, 0.08)
        px[x, 8] = shade(CASING_DARK, -0.20)
    for y in range(5, 11):
        px[7, y] = shade(CASING_LIGHT, 0.08)
        px[8, y] = shade(CASING_DARK, -0.20)

    # Srodek krzyza - akcent.
    px[7, 7] = shade(accent, 0.15)
    px[8, 8] = shade(accent, -0.25)

    for (rx, ry) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        rivet(px, rx, ry)

    bevel(px)
    return img


def front_texture(accent, deep, seed, bars):
    """Przod: zaglebione palenisko z kratownica i kontrolka zasilania.

    {@code bars} to lista wierszy, w ktorych swieci kratownica - dzieki temu
    piec na paliwo (pelny ogien) i elektryczny (rownomierne pole) roznia sie
    nie tylko kolorem, ale i rysunkiem.
    """
    rng = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), CASING + (255,))
    px = img.load()
    fill_casing(px, rng)

    # Ramka paleniska.
    for y in range(4, 13):
        for x in range(2, 14):
            px[x, y] = shade(CASING_DARK, -0.40)

    # Wnetrze paleniska.
    for y in range(5, 12):
        for x in range(3, 13):
            px[x, y] = FIREBOX

    # Kratownica: naprzemiennie jasne i ciemne prety.
    for i, y in enumerate(range(6, 11)):
        if y in bars:
            for x in range(3, 13):
                px[x, y] = shade(accent, 0.05 if i % 2 == 0 else -0.05)
            # Gorna krawedz preta mocniej swieci.
            for x in range(3, 13):
                px[x, y] = shade(px[x, y], 0.18)
        else:
            for x in range(3, 13):
                px[x, y] = shade(deep, -0.55)

    # Poprzeczne zebra kratownicy.
    for x in (5, 8, 11):
        for y in range(5, 12):
            px[x, y] = shade(px[x, y], -0.35)

    # Kontrolka zasilania w prawym gornym narozu.
    px[13, 2] = shade(accent, 0.35)
    px[12, 2] = shade(accent, -0.20)

    # Szczelina wlotu paliwa / kabla na dole.
    for x in range(4, 12):
        px[x, 13] = shade(CASING_DARK, -0.30)

    bevel(px)
    return img


def save(img, name):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    img.save(path)
    print("zapisano:", path)


def build_furnace(prefix, accent, deep, seed, bars):
    save(front_texture(accent, deep, seed, bars), f"{prefix}_front.png")
    save(side_texture(accent, seed + 1), f"{prefix}_side.png")
    save(top_texture(accent, seed + 2), f"{prefix}_top.png")


def main():
    # Piec na paliwo: kratownica swieci mocno przy dole - jak rozpalone palenisko.
    build_furnace("velocity_furnace", HEAT_GLOW, HEAT_DEEP, seed=20260915,
                  bars={9, 10})

    # Piec elektryczny: rownomierne pole grzewcze, bez ognia.
    build_furnace("electric_furnace", VOLT_GLOW, VOLT_DEEP, seed=20260916,
                  bars={6, 8, 10})


if __name__ == "__main__":
    main()
