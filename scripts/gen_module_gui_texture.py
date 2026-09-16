#!/usr/bin/env python3
"""Generuje teksture okna modulu Veloce (GUI w stylu vanilla).

Okno modulu (prawy klik na module: predkosc/SU albo energia) bylo w pierwszej
wersji polprzezroczystym prostokatem na rozmytym swiecie - gracz zobaczyl wtedy
tekst "za blurem", jak tooltip w tle. Panel jest teraz NIEPRZEZROCZYSTY i ma ten
sam uklad co okna pozostalych naszych blokow (176x166, paleta vanilla).

Uruchomienie:
    python3 scripts/gen_module_gui_texture.py
Wymaga Pillow (PIL). Plik jest zasobem w JARze, wiec po zmianie wystarczy build.
"""
import os
import sys

from PIL import Image, ImageDraw

# Paleta dokladnie jak w pozostalych GUI moda i w vanilla:
#   C6C6C6 - tlo panelu, 8B8B8B - wgłębienia, 373737 - ciemna krawedz,
#   555555 - cien dolnej/prawej krawedzi, FFFFFF - rozjasnienie gornej/lewej.
BLACK = (0, 0, 0, 255)
GREY = (198, 198, 198, 255)
DARK = (139, 139, 139, 255)
SHADOW = (85, 85, 85, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)

PANEL_WIDTH = 176
PANEL_HEIGHT = 166
CANVAS = 256
TITLE_SEPARATOR_Y = 19
OUT = os.path.join("assets", "craftingveloce", "textures", "gui", "module_info.png")


def draw_panel(image):
    """Panel 176x166 z ramka i separatorem pod tytulem."""
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, PANEL_WIDTH - 1, PANEL_HEIGHT - 1], fill=GREY)

    # Rozjasnienie gornej/lewej i cien dolnej/prawej krawedzi - klasyczny relief.
    draw.line([(1, 1), (PANEL_WIDTH - 2, 1)], fill=WHITE)
    draw.line([(1, 1), (1, PANEL_HEIGHT - 2)], fill=WHITE)
    draw.line([(1, PANEL_HEIGHT - 2), (PANEL_WIDTH - 2, PANEL_HEIGHT - 2)], fill=SHADOW)
    draw.line([(PANEL_WIDTH - 2, 1), (PANEL_WIDTH - 2, PANEL_HEIGHT - 2)], fill=SHADOW)

    # Czarna obwodka na wierzchu reliefu.
    draw.rectangle([0, 0, PANEL_WIDTH - 1, PANEL_HEIGHT - 1], outline=BLACK)

    # Linia oddzielajaca tytul od tresci (jak w oknach kontenerow).
    draw.line([(7, TITLE_SEPARATOR_Y), (PANEL_WIDTH - 8, TITLE_SEPARATOR_Y)], fill=SHADOW)


def main():
    image = Image.new("RGBA", (CANVAS, CANVAS), CLEAR)
    draw_panel(image)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    image.save(OUT)
    print(f"zapisano {OUT} ({image.size[0]}x{image.size[1]}, "
          f"panel {PANEL_WIDTH}x{PANEL_HEIGHT})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
