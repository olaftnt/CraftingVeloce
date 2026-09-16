#!/usr/bin/env python3
"""
Generuje ikone itemu "stol craftingu w klatce Veloce Integrale".

Model itemu to model RAMY (te same 12 pretow + fioletowa szyba) plus mala
kostka stolu craftingu w srodku - dokladnie to, co gracz widzi w swiecie
(patrz renderer) i co widzi na ikonie w ekwipunku.

DLACZEGO GENERATOR, A NIE RECZNIE PISANY JSON. Model ramy ma kilkaset linii
i jest jedynym zrodlem prawdy o wygladzie klatki. Jesli zmieni sie rama,
recznie przepisana ikona rozjedzie sie z blokiem (dokladnie ten typ bledu,
ktory w tym projekcie wraca: dwa miejsca z ta sama regula). Tutaj rama jest
KOPIOWANA z pliku, wiec nie da sie jej rozjesc.

Uruchomienie:  python3 scripts/gen_integrale_crafting_item.py
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRAME = os.path.join(ROOT, "assets/craftingveloce/models/block/veloce_integrale_frame.json")
OUT = os.path.join(ROOT, "assets/craftingveloce/models/item/veloce_integrale_crafting.json")

# Kostka stolu w srodku: szyba klatki zajmuje 2..14, wiec stol musi byc mniejszy
# (4..12), inaczej jego sciany lezaly by dokladnie na szybie (z-fighting).
TABLE_FROM = 4.0
TABLE_TO = 12.0

# Tekstury stolu craftingu - te same, ktorych uzywa waniliowy model bloku,
# zeby ikona wygladala jak prawdziwy stol (a nie jak nasza interpretacja).
TABLE_TEXTURES = {
    "table_top": "minecraft:block/crafting_table_top",
    "table_front": "minecraft:block/crafting_table_front",
    "table_side": "minecraft:block/crafting_table_side",
    "table_bottom": "minecraft:block/oak_planks",
}


def table_element():
    """Kostka stolu craftingu w srodku klatki."""
    return {
        "from": [TABLE_FROM, TABLE_FROM, TABLE_FROM],
        "to": [TABLE_TO, TABLE_TO, TABLE_TO],
        "faces": {
            "north": {"texture": "#table_front"},
            "south": {"texture": "#table_side"},
            "east": {"texture": "#table_side"},
            "west": {"texture": "#table_side"},
            "up": {"texture": "#table_top"},
            "down": {"texture": "#table_bottom"},
        },
    }


def build_model():
    """Model itemu: rama klatki (skopiowana) + kostka stolu w srodku."""
    with open(FRAME, encoding="utf-8") as handle:
        model = json.load(handle)
    model["textures"] = dict(model.get("textures", {}))
    model["textures"].update(TABLE_TEXTURES)
    model["elements"] = list(model.get("elements", [])) + [table_element()]
    return model


def main():
    model = build_model()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(model, handle, indent=4)
        handle.write("\n")
    print(f"{os.path.relpath(OUT, ROOT)}: {len(model['elements'])} elementow "
          f"({len(model['elements']) - 1} ramy/szyby + 1 stol)")


if __name__ == "__main__":
    main()
