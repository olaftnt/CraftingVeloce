#!/usr/bin/env python3
"""Tekstury Veloce Threshold Sensor: blok (off/on) + tlo GUI.

BLOCK: baza jest waniliowy `observer` - to blok redstone, wiec tematycznie
pasuje idealnie. Jego wlasna czerwona dioda (2x2 piksele) staje sie nasza
kontrolka: przygaszona, gdy sensor nie wystawia pradu, i rozjarzona, gdy
wystawia. Reszta korpusu zostaje bez zmian, wiec blok nadal wyglada jak
minecraftowy.

GUI: panel w stylu ekstraktora (212x166) + JEDEN slot itemu w wysrodkowanym
wierszu. Wiersz (slot, pole liczby, "+", "-", guzik trybu) rysuje ekran, wiec
w teksturze nie ma po nim zadnych wglebien - i nie ma juz kwadratu na tekst
stanu, ktory zostawal pusty i wygladal jak blad grafiki. To wlasnie ten
kwadrat zglosil gracz.

Uruchomienie:
    python3 scripts/gen_sensor_textures.py
"""

import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_furnace_gui as style  # noqa: E402  (wspolny styl panelu i slotow)

VANILLA = "/tmp/matref"
BLOCK_OUT = os.path.join("assets", "craftingveloce", "textures", "block")
GUI_OUT = os.path.join("assets", "craftingveloce", "textures", "gui")

# Dioda w waniliowym observerze - dokladnie te piksele.
LAMP = [(7, 7), (8, 7), (7, 8), (8, 8)]

LAMP_OFF = (0x5A, 0x0A, 0x06)
LAMP_ON = (0xFF, 0x3B, 0x2A)
LAMP_GLOW = (0xFF, 0x8A, 0x70)

# Pozycja slotu itemu - MUSI sie zgadzac z menu i ekranem.
# UWAGA: kazda stala w OSOBNEJ linii. Build.py czyta je wyrazeniem regularnym,
# zeby porownac generator z menu (zapis krotkowy "A, B = 1, 2" tego nie pozwala
# i wlasnie dlatego ta para byla poza kontrola).
FILTER_SLOT_X = 41
FILTER_SLOT_Y = 28

# Granice calego wiersza - do samokontroli "wiersz jest czystym panelem".
# Te same liczby ma menu (ROW_Y, ROW_H, MODE_X, BTN_W); tutaj sa tylko po to,
# zeby sprawdzic PIKSELI, a nie zeby ich uzywac do rysowania.
ROW_TOP = 25
ROW_BOTTOM = 46
ROW_RIGHT = 171


def sensor_block(on):
    """Blok czujnika: waniliowy observer z podswietlona albo przygaszona dioda."""
    src = Image.open(os.path.join(VANILLA, "observer_front.png")).convert("RGBA")
    img = src.copy()
    px = img.load()

    # Diody w observer_front nie ma (jest w _back), wiec wycinamy dla niej
    # male wglebienie i wstawiamy wlasna kontrolke.
    for (x, y) in LAMP:
        px[x, y] = (0x14, 0x10, 0x10, 255)
    if on:
        for (x, y) in LAMP:
            px[x, y] = LAMP_ON + (255,)
        px[7, 7] = LAMP_GLOW + (255,)
        # Jasniejsza obwodka - dioda ma sprawiac wrazenie, ze swieci.
        for (x, y) in ((6, 7), (9, 7), (7, 6), (7, 9)):
            if 0 <= x < 16 and 0 <= y < 16:
                px[x, y] = (0x7A, 0x1A, 0x12, 255)
    else:
        # Przygaszona, ale widoczna - gracz ma wiedziec, gdzie jest kontrolka.
        px[7, 7] = LAMP_OFF + (255,)
        px[8, 7] = (0x40, 0x08, 0x05, 255)
        px[7, 8] = (0x40, 0x08, 0x05, 255)
        px[8, 8] = (0x2A, 0x06, 0x04, 255)
    return img


def sensor_gui():
    """Tlo GUI: panel jak ekstraktor + JEDEN slot itemu. Nic wiecej."""
    img = Image.new("RGBA", (style.CANVAS, style.CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    style.panel(d)

    # Slot itemu (widmo - ikone rysuje ekran). Reszta wiersza to widgety
    # Minecrafta (pole tekstowe i guziki), ktore rysuja sie same.
    style.slot(d, FILTER_SLOT_X, FILTER_SLOT_Y)

    style.player_inventory(d)
    return img


def self_check(img):
    """Sprawdza WYGENEROWANE piksele: ramka slotu tam, gdzie mowi stala,
    i ZADNEGO wglebienia po starym tekscie stanu.

    <p>Po co: gracz zglosil "dziwny kwadrat, w ktorym tekst sie nie miescil" -
    to bylo wglebienie pod napis stanu, ktorego juz nie rysujemy. Łatwo je
    przywrocic przypadkiem, kopiujac stary fragment generatora, a w grze
    wyglada to jak blad grafiki.
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
        bad.append(f"slot itemu @ {FILTER_SLOT_X},{FILTER_SLOT_Y}")
    for row in range(3):
        for col in range(9):
            if not slot_ok(style.PLAYER_X + col * 18, style.PLAYER_Y + row * 18):
                bad.append(f"ekwipunek r{row}c{col}")
    for col in range(9):
        if not slot_ok(style.PLAYER_X + col * 18, style.PLAYER_Y + 58):
            bad.append(f"hotbar c{col}")

    # Obszar, gdzie kiedys bylo wglebienie pod tekst stanu - ma byc CZYSTY panel.
    dirty = [(x, y) for y in range(58, 79) for x in range(26, 177)
             if img.getpixel((x, y)) != PANEL]
    if dirty:
        bad.append(f"stary kwadrat na tekst wrocil ({len(dirty)} px, np. {dirty[0]})")

    # Caly wiersz (slot + pole + guziki) ma byc czystym panelem poza ramka
    # slotu: pole tekstowe i guziki rysuja sie same, wiec zadnych wglebien
    # ani ramek nie może tam byc.
    row = [(x, y) for y in range(ROW_TOP, ROW_BOTTOM + 1)
           for x in range(FILTER_SLOT_X - 1, ROW_RIGHT + 1)
           if not inside_slot_frame(x, y) and img.getpixel((x, y)) != PANEL]
    if row:
        bad.append(f"wiersz nie jest czystym panelem ({len(row)} px, np. {row[0]})")

    if bad:
        raise SystemExit("BLAD: tekstura nie zgadza sie ze stalymi: " + ", ".join(bad))
    print("  samokontrola: slot itemu, ekwipunek, brak kwadratu i czysty wiersz - OK")


def inside_slot_frame(x, y):
    """Czy piksel nalezy do ramki slotu (razem z 1 px obwodki)."""
    return (FILTER_SLOT_X - 1 <= x <= FILTER_SLOT_X + 16
            and FILTER_SLOT_Y - 1 <= y <= FILTER_SLOT_Y + 16)


def main():
    os.makedirs(BLOCK_OUT, exist_ok=True)
    os.makedirs(GUI_OUT, exist_ok=True)

    if os.path.isdir(VANILLA):
        for name, on in (("threshold_sensor", False), ("threshold_sensor_on", True)):
            path = os.path.join(BLOCK_OUT, name + ".png")
            sensor_block(on).save(path)
            print("zapisano:", path)
    else:
        print("pomijam tekstury bloku - brak waniliowego wzorca", VANILLA)

    gui = sensor_gui()
    self_check(gui)
    path = os.path.join(GUI_OUT, "threshold_sensor.png")
    gui.save(path)
    print("zapisano:", path)

    print()
    print("Pozycje do zgodnosci w menu i ekranie:")
    print(f"  slot itemu:  x={FILTER_SLOT_X} y={FILTER_SLOT_Y}")


if __name__ == "__main__":
    main()
