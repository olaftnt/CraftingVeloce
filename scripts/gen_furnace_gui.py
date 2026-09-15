#!/usr/bin/env python3
"""Generuje tla GUI dla obu piecow (176x166, jak waniliowy piec).

Bez tla ekran pokazalby sie na czarnym prostokacie albo (gorzej) wygladalby
jak brakujaca tekstura. Rysujemy wiec panel w stylu moda: ciemna obudowa,
wciete ramki slotow i pole na pasku energii.

Podmiana na prawdziwa grafike: nadpisz PNG o tym samym rozmiarze.
"""
import os
from PIL import Image, ImageDraw

OUT = os.path.join("assets", "craftingveloce", "textures", "gui")
W, H = 176, 166

PANEL = (0x2A, 0x22, 0x2A)
PANEL_HI = (0x3C, 0x2F, 0x3C)
PANEL_LO = (0x18, 0x12, 0x18)
SLOT_BG = (0x1A, 0x14, 0x1A)
SLOT_HI = (0x4A, 0x3C, 0x4A)
SLOT_LO = (0x12, 0x0E, 0x12)
ACCENT = (0xC9, 0x2D, 0xEA)
VOLT = (0x3C, 0x46, 0xFF)


def panel(draw):
    draw.rectangle([0, 0, W - 1, H - 1], fill=PANEL)
    # Wypukle krawedzie - panel ma wygladac jak obudowa, nie jak plaski prostokat.
    draw.rectangle([0, 0, W - 1, H - 1], outline=PANEL_HI)
    draw.line([(1, H - 2), (W - 2, H - 2)], fill=PANEL_LO)
    draw.line([(W - 2, 1), (W - 2, H - 2)], fill=PANEL_LO)
    # Pasek na tytul.
    draw.rectangle([4, 4, W - 5, 16], fill=PANEL_LO)


def slot(draw, x, y):
    """Wciete pole slotu 18x18 z ramka 16x16."""
    draw.rectangle([x - 1, y - 1, x + 16, y + 16], fill=SLOT_HI)
    draw.rectangle([x, y, x + 15, y + 15], fill=SLOT_BG)


def player_inventory(draw, x0=8, y0=84):
    for row in range(3):
        for col in range(9):
            slot(draw, x0 + col * 18, y0 + row * 18)
    for col in range(9):
        slot(draw, x0 + col * 18, y0 + 58)


def velocity_furnace():
    img = Image.new("RGBA", (W, H), PANEL + (255,))
    d = ImageDraw.Draw(img)
    panel(d)
    # 6 filtrow paliwa: 3 kolumny x 2 rzedy, tak jak w menu ekstraktora.
    for i in range(6):
        slot(d, 26 + (i % 3) * 18, 18 + (i // 3) * 18)
    # Realny slot paliwa.
    slot(d, 26, 60)
    # Tlo plomienia (sprite rysuje ekran z tekstury waniliowej).
    d.rectangle([100, 40, 113, 53], fill=(0x14, 0x10, 0x16))
    d.rectangle([100, 40, 113, 53], outline=PANEL_LO)
    # Akcent: purpurowy pasek - piec na paliwo.
    d.rectangle([100, 58, 113, 60], fill=ACCENT)
    player_inventory(d)
    return img


def electric_furnace():
    img = Image.new("RGBA", (W, H), PANEL + (255,))
    d = ImageDraw.Draw(img)
    panel(d)
    # Pole paska energii - ekran wypelnia je od dolu.
    d.rectangle([79, 19, 96, 72], fill=SLOT_HI)
    d.rectangle([80, 20, 95, 71], fill=SLOT_BG)
    # Akcent: niebieski pasek - piec elektryczny.
    d.rectangle([80, 76, 95, 78], fill=VOLT)
    player_inventory(d)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, img in (("velocity_furnace", velocity_furnace()),
                      ("electric_furnace", electric_furnace())):
        path = os.path.join(OUT, name + ".png")
        img.save(path)
        print("zapisano:", path)


if __name__ == "__main__":
    main()
