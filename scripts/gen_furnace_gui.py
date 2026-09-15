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
# UWAGA: kazda stala w OSOBNEJ linii. Build.py czyta je wyrazeniem regularnym,
# zeby porownac z menu i z ekranem - zapis krotkowy (A, B = 1, 2) tego nie
# pozwala, a bez tego porownania uklad GUI moze sie rozjechac niezauwazony.
FILTER_X = 26
FILTER_Y = 18
FUEL_X = 88      # na prawo od filtrow (bylo pod nimi, nachodzilo na "Inventory")
FUEL_Y = 26
PLAYER_X = 26
PLAYER_Y = 84

# Pozycje elementow rysowanych dynamicznie przez ekrany.
# JEDNO zrodlo: ekrany uzywaja tych samych liczb (sprawdza to build.py).
FLAME_X = 112    # na prawo od slotu paliwa
FLAME_Y = 27
# Uwaga: samego plomienia NIE rysujemy w teksturze. Ekran sklada go
# z dwoch sprite'ow wanilii (wygaszony obrys + zapalona czesc), dokladnie
# tak, jak robi to waniliowy piec - patrz VeloceVelocityFurnaceScreen.
BAR_X = 150
BAR_Y = 30
BAR_W = 18
BAR_H = 54


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

    # Plomienia NIE malujemy: ekran rysuje go sprite'ami z waniliowego pieca
    # (obrys + zapalona czesc). Malowanie tu czegokolwiek pod nim tylko by
    # psulo wyglad - i tak bylo: wlasne wglebienie wygladalo jak slot.

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


def self_check(img):
    """Sprawdza WYGENEROWANY obraz: czy ramki slotow sa dokladnie tam, gdzie
    deklaruja stale tego pliku.

    <p>Po co: te same liczby zyja w TRZECH miejscach (ten generator, menu i
    ekran). Raz juz sie rozjechaly - slot paliwa stal w innym miejscu, niz
    mowilo menu, i nachodzil na napis "Inventory". Latwiej sprawdzic piksele
    niz wierzyc, ze ktos pamietal o wszystkich trzech.
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
            bad.append(f"filtr {i} @ {x},{y}")
    if not slot_ok(FUEL_X, FUEL_Y):
        bad.append(f"paliwo @ {FUEL_X},{FUEL_Y}")
    for row in range(3):
        for col in range(9):
            if not slot_ok(PLAYER_X + col * 18, PLAYER_Y + row * 18):
                bad.append(f"ekwipunek r{row}c{col}")
    for col in range(9):
        if not slot_ok(PLAYER_X + col * 18, PLAYER_Y + 58):
            bad.append(f"hotbar c{col}")

    # Miejsce plomienia musi byc czyste - plomien rysuje ekran, sprite'ami wanilii.
    dirty = sum(1 for y in range(FLAME_Y, FLAME_Y + 14)
                for x in range(FLAME_X, FLAME_X + 14)
                if img.getpixel((x, y)) != PANEL)
    if dirty:
        bad.append(f"miejsce plomienia zabrudzone ({dirty} px)")

    if bad:
        raise SystemExit("BLAD: tekstura nie zgadza sie ze stalymi: " + ", ".join(bad))
    print("  samokontrola: ramki slotow i miejsce plomienia OK")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, img in (("velocity_furnace", velocity_furnace()),
                      ("electric_furnace", electric_furnace())):
        path = os.path.join(OUT, name + ".png")
        img.save(path)
        print("zapisano:", path, img.size)
        if name == "velocity_furnace":
            self_check(img)

    print()
    print("Pozycje do zgodnosci w ekranach:")
    print(f"  plomien:   x={FLAME_X} y={FLAME_Y} (14x14)")
    print(f"  pasek FE:  x={BAR_X} y={BAR_Y} w={BAR_W} h={BAR_H}")
    print(f"  ekwipunek: x={PLAYER_X} y={PLAYER_Y}, hotbar y={PLAYER_Y + 58}")


if __name__ == "__main__":
    main()
