#!/usr/bin/env python3
"""Generuje tla GUI dla obu piecow - W STYLU EKSTRAKTORA.

Poprzednia wersja rysowala ciemny panel w stylu moda (fiolet/ender). Efekt
byl taki, ze piece wygladaly jak z innej gry niz reszta moda - ekstraktor
uzywa klasycznego, waniliowego GUI. Ten skrypt odtwarza DOKLADNIE ten styl,
wyprobowany z tekstury ekstraktora:

    panel           #c6c6c6
    skos jasny      #ffffff   (gorna i lewa krawedz panelu)
    skos ciemny     #373737   (prawa i dolna krawedz panelu)
    cien wewnetrzny #8b8b8b   (1 px przy prawej i dolnej krawedzi)
    wnetrze slotu   #8b8b8b
    ramka slotu     #373737 (gora+lewo) / #ffffff (dol+prawo)

Uklad jest ten sam co w ekstraktorze: panel 212 x 166, ekwipunek gracza
w x=26, etykiety w x=26. Dzieki temu oba ekrany czyta sie jak jedna rodzina.

Uruchomienie:
    python3 scripts/gen_furnace_gui.py
"""

import os

from PIL import Image, ImageDraw

OUT = os.path.join("assets", "craftingveloce", "textures", "gui")

# Wymiary panelu - DOKLADNIE jak ekstraktor (i jak waniliowy kontener).
W, H = 212, 166
CANVAS = 256  # rozmiar pliku, jak w ekstraktorze

PANEL = (0xC6, 0xC6, 0xC6)
BEVEL_LIGHT = (0xFF, 0xFF, 0xFF)
BEVEL_DARK = (0x37, 0x37, 0x37)
SHADOW = (0x8B, 0x8B, 0x8B)
SLOT_BG = (0x8B, 0x8B, 0x8B)

# Pozycje slotow - MUSZA sie zgadzac z menu (VeloceVelocityFurnaceMenu).
FILTER_X, FILTER_Y = 26, 18
FUEL_X, FUEL_Y = 26, 60
PLAYER_X, PLAYER_Y = 26, 84

# Pozycje elementow rysowanych dynamicznie przez ekrany.
# JEDNO zrodlo: ekrany uzywaja tych samych liczb.
FLAME_X, FLAME_Y = 150, 30
BAR_X, BAR_Y, BAR_W, BAR_H = 150, 30, 18, 54


def panel(draw):
    """Panel w stylu waniliowego kontenera: wypukla ramka + cien wewnatrz."""
    draw.rectangle([0, 0, W - 1, H - 1], fill=PANEL)
    # Jasny skos: gora i lewo.
    draw.line([(0, 0), (W - 1, 0)], fill=BEVEL_LIGHT)
    draw.line([(0, 0), (0, H - 1)], fill=BEVEL_LIGHT)
    # Ciemny skos: dol i prawo.
    draw.line([(W - 1, 0), (W - 1, H - 1)], fill=BEVEL_DARK)
    draw.line([(0, H - 1), (W - 1, H - 1)], fill=BEVEL_DARK)
    # Cien wewnetrzny tuz przy ciemnym skosie - to on daje efekt wglebienia.
    draw.line([(W - 2, 1), (W - 2, H - 2)], fill=SHADOW)
    draw.line([(1, H - 2), (W - 2, H - 2)], fill=SHADOW)


def slot(draw, sx, sy):
    """Slot 16x16 w dokladnie waniliowym wzorze (wyprobowanym z ekstraktora).

    Ramka nie jest symetryczna: gora i lewo ciemne, dol i prawo jasne - dzieki
    temu slot wyglada na wciety, a nie namalowany.
    """
    draw.rectangle([sx, sy, sx + 15, sy + 15], fill=SLOT_BG)
    # UWAGA NA DLUGOSCI: prawa i dolna krawedz maja DOKLADNIE po 16 px.
    # Wersja z koncowka w (sx+16, sy+16) byla o piksel za dluga i ten piksel
    # nadpisywal potem narożnik - ramka wychodzila z bialym kleksem
    # w prawym dolnym rogu. Wyprobowane z tekstury ekstraktora.
    draw.line([(sx - 1, sy - 1), (sx + 15, sy - 1)], fill=BEVEL_DARK)   # gora, 17 px
    draw.line([(sx - 1, sy - 1), (sx - 1, sy + 15)], fill=BEVEL_DARK)   # lewo, 17 px
    draw.line([(sx + 16, sy), (sx + 16, sy + 15)], fill=BEVEL_LIGHT)    # prawo, 16 px
    draw.line([(sx, sy + 16), (sx + 15, sy + 16)], fill=BEVEL_LIGHT)    # dol, 16 px
    # Narozniki domykajace ramke (bez nich ramka nie spotyka sie z tlem).
    draw.point((sx + 16, sy - 1), fill=SLOT_BG)
    draw.point((sx - 1, sy + 16), fill=SLOT_BG)
    draw.point((sx + 16, sy + 16), fill=SLOT_BG)


def player_inventory(draw):
    for row in range(3):
        for col in range(9):
            slot(draw, PLAYER_X + col * 18, PLAYER_Y + row * 18)
    for col in range(9):
        slot(draw, PLAYER_X + col * 18, PLAYER_Y + 58)


def recess(draw, x0, y0, x1, y1):
    """Wciete pole (pod plomien albo pasek energii).

    Ta sama logika co slot, tylko o dowolnym rozmiarze - pole ma wygladac na
    wglebienie, a nie na doklejony prostokat.
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
    """Piec paliwowy: 6 filtrow + slot paliwa + okno plomienia."""
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d)

    # Filtry paliwa: 3 kolumny x 2 rzedy - ten sam start co filtry ekstraktora.
    for i in range(6):
        slot(d, FILTER_X + (i % 3) * 18, FILTER_Y + (i // 3) * 18)
    # Realny slot paliwa.
    slot(d, FUEL_X, FUEL_Y)

    # Okno plomienia po prawej - dokladnie tam, gdzie ekran rysuje sprite.
    recess(d, FLAME_X, FLAME_Y, FLAME_X + 13, FLAME_Y + 13)

    player_inventory(d)
    return img


def electric_furnace():
    """Piec elektryczny: tylko pasek energii (zadnych slotow na przedmioty)."""
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d)
    # Pasek energii - ekran wypelnia go od dolu.
    recess(d, BAR_X, BAR_Y, BAR_X + BAR_W - 1, BAR_Y + BAR_H - 1)
    player_inventory(d)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, img in (("velocity_furnace", velocity_furnace()),
                      ("electric_furnace", electric_furnace())):
        path = os.path.join(OUT, name + ".png")
        img.save(path)
        print("zapisano:", path, img.size)

    print()
    print("Pozycje do zgodnosci w ekranach:")
    print(f"  plomien:   x={FLAME_X} y={FLAME_Y} (14x14)")
    print(f"  pasek FE:  x={BAR_X} y={BAR_Y} w={BAR_W} h={BAR_H}")
    print(f"  ekwipunek: x={PLAYER_X} y={PLAYER_Y}, hotbar y={PLAYER_Y + 58}")


if __name__ == "__main__":
    main()
