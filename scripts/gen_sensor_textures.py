#!/usr/bin/env python3
"""Tekstury Veloce Threshold Sensor: blok (off/on) + tlo GUI.

BLOCK: baza jest waniliowy `observer` - to blok redstone, wiec tematycznie
pasuje idealnie. Jego wlasna czerwona dioda (2x2 piksele) staje sie nasza
kontrolka: przygaszona, gdy sensor nie wystawia pradu, i rozjarzona, gdy
wystawia. Reszta korpusu zostaje bez zmian, wiec blok nadal wyglada jak
minecraftowy.

GUI: ta sama siatka i ten sam styl co ekstraktor (panel 212x166, sloty od
x=26, ekwipunek od y=84). Styl importujemy z generatora piecow, zeby oba
ekrany rysowaly sie jednym kodem - a nie dwiema kopiami, ktore maja sie
zgadzac.

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

# Pozycje w GUI - MUSZA sie zgadzac z menu i ekranem.
FILTER_X, FILTER_Y = 26, 18
FIELD_X, FIELD_Y, FIELD_W, FIELD_H = 50, 18, 76, 16
STATUS_X, STATUS_Y = 26, 58


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
    """Tlo GUI: panel jak ekstraktor + slot filtra + wglebienie na pole i status."""
    img = Image.new("RGBA", (style.CANVAS, style.CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    style.panel(d)

    # Slot filtra (widmo - ikone rysuje ekran).
    style.slot(d, FILTER_X, FILTER_Y)
    # Wglebienie pod pole z liczba, zeby pole nie wisialo "w powietrzu".
    style.recess(d, FIELD_X - 1, FIELD_Y - 1, FIELD_X + FIELD_W, FIELD_Y + FIELD_H)
    # Wglebienie pod tekst stanu.
    style.recess(d, STATUS_X - 1, STATUS_Y - 1, STATUS_X + 150, STATUS_Y + 20)

    style.player_inventory(d)
    return img


def main():
    os.makedirs(BLOCK_OUT, exist_ok=True)
    os.makedirs(GUI_OUT, exist_ok=True)

    for name, on in (("threshold_sensor", False), ("threshold_sensor_on", True)):
        path = os.path.join(BLOCK_OUT, name + ".png")
        sensor_block(on).save(path)
        print("zapisano:", path)

    path = os.path.join(GUI_OUT, "threshold_sensor.png")
    sensor_gui().save(path)
    print("zapisano:", path)

    print()
    print("Pozycje do zgodnosci w menu i ekranie:")
    print(f"  slot filtra: x={FILTER_X} y={FILTER_Y}")
    print(f"  pole liczby: x={FIELD_X} y={FIELD_Y} w={FIELD_W} h={FIELD_H}")
    print(f"  status:      x={STATUS_X} y={STATUS_Y}")


if __name__ == "__main__":
    main()
