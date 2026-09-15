#!/usr/bin/env python3
"""Generuje loot table i tagi "mineable" dla WSZYSTKICH zarejestrowanych blokow.

Po co generator, a nie recznie pisane pliki
-------------------------------------------
Bez loot table blok nie wypada po zniszczeniu - w trybie przetrwania gracz
traci rure, terminal czy ekstraktor bezpowrotnie (w creative tego nie widac,
wiec latwo to przeoczyc). To dokladnie ta sama klasa bledu, ktora w tym
projekcie trafiala juz czterokrotnie: lista przepisana RECZNIE w drugim
miejscu, ktora z czasem sie rozjezdza. Dlatego lista blokow NIE jest tu
wypisana - czytamy ja z rejestru (VeloceRegistry.java), ktory jest jedynym
zrodlem prawdy.

Skrypt jest idempotentny:
  * brakujace loot table dopisuje,
  * ISTNIEJACYCH loot table nie rusza (recznie dostrojone zostaja),
  * tagi mineable przepisuje w calosci na podstawie rejestru.

Uruchomienie:  python3 scripts/gen_loot_tables.py
"""

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "src/com/craftingveloce/init/VeloceRegistry.java")

# Rejestry blokow modulow opcjonalnych (compat/<mod>/XBlocks.java). Czytamy je
# TAKZE wtedy, gdy mod jest nieobecny: dane (loot table, tag mineable) sa
# zwyklymi plikami JSON i nie szkodza, gdy blok sie nie rejestruje. Bez tego
# nowy blok modulu nie dostalby loot table - a to jest blad widoczny dopiero
# w grze, po zniszczeniu bloku.
COMPAT_REGISTRY_GLOB = "src/com/craftingveloce/compat/*/*Blocks.java"
LOOT_DIR = os.path.join(ROOT, "data/craftingveloce/loot_table/blocks")
TAG_DIR = os.path.join(ROOT, "data/minecraft/tags/block/mineable")

MODID = "craftingveloce"

# Narzedzie, ktorym blok zbiera sie najszybciej. Wypisane JAWNIE, zeby nowy
# blok bez decyzji od razu krzyczal ostrzezeniem, a nie po cichu dostawal kilof.
TOOL_BY_BLOCK = {
    "veloce_tom_terminal": "axe",        # drewniana obudowa terminala
    "veloce_crafting_table": "axe",      # jak vanilla crafting table
    "veloce_pipe": "pickaxe",            # metalowa rura
    "veloce_extractor": "pickaxe",       # maszyna
    "veloce_controller": "pickaxe",      # maszyna
    "velocity_furnace": "pickaxe",       # jak vanilla furnace
    "electric_furnace": "pickaxe",       # jak vanilla furnace
    "threshold_sensor": "pickaxe",       # jak vanilla observer
    "veloce_mekanism_crusher_module": "pickaxe",     # maszyny z modulu Mekanism
    "veloce_mekanism_enrichment_module": "pickaxe",
    "veloce_mekanism_combiner_module": "pickaxe",
    "veloce_mekanism_sawmill_module": "pickaxe",
    "veloce_alchemistry_compactor_module": "pickaxe",  # maszyny z modulu Alchemistry
    "veloce_alchemistry_combiner_module": "pickaxe",
    "veloce_alchemistry_fission_module": "pickaxe",
    "veloce_alchemistry_fusion_module": "pickaxe",
    "veloce_create_millstone_module": "pickaxe",       # maszyny z modulu Create
    "veloce_create_saw_module": "pickaxe",
    "veloce_create_crushing_module": "pickaxe",
    "veloce_create_mechanical_crafter_module": "pickaxe",
}
DEFAULT_TOOL = "pickaxe"

# Bloki, ktore NIE maja wypadac (np. techniczne). Puste - wszystkie nasze
# bloki sa normalnymi blokami do postawienia i zburzenia.
NO_DROP = set()


def registered_block_ids():
    """Wyciaga identyfikatory blokow z rejestrow - jedynych zrodel prawdy."""
    files = [REGISTRY] + sorted(glob.glob(os.path.join(ROOT, COMPAT_REGISTRY_GLOB)))
    ids = []
    for path in files:
        if not os.path.exists(path):
            continue
        ids += re.findall(r'\bBLOCKS\.register\(\s*"([a-z0-9_]+)"',
                          open(path, encoding="utf-8").read())
    # Rejestr MENU_TYPES uzywa tego samego wzorca, ale innego DeferredRegister,
    # wiec powyzszy wzorzec go nie lapie. Uprzedzamy sie jednak na przyszlosc.
    ids = [i for i in ids if not i.endswith("_menu")]
    # Kolejnosc rejestracji = kolejnosc w plikach wynikowych (stabilne diffy).
    seen, ordered = set(), []
    for i in ids:
        if i not in seen:
            seen.add(i)
            ordered.append(i)
    return ordered


def loot_table_for(block_id):
    """Standardowy drop: sam blok, zachowany przy wybuchu."""
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "rolls": 1,
                "entries": [{"type": "minecraft:item", "name": f"{MODID}:{block_id}"}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }
        ],
    }


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def generate_loot_tables(block_ids):
    created, kept, skipped = [], [], []
    for b in block_ids:
        path = os.path.join(LOOT_DIR, f"{b}.json")
        if b in NO_DROP:
            skipped.append(b)
            continue
        if os.path.exists(path):
            kept.append(b)
            continue
        write_json(path, loot_table_for(b))
        created.append(b)
    return created, kept, skipped


def generate_mineable_tags(block_ids):
    """Grupuje bloki po narzedziu i przepisuje tagi mineable."""
    groups = {}
    for b in block_ids:
        if b in NO_DROP:
            continue
        groups.setdefault(TOOL_BY_BLOCK.get(b, DEFAULT_TOOL), []).append(b)

    written = {}
    for tool, blocks in sorted(groups.items()):
        tag = {
            "replace": False,
            "values": sorted(f"{MODID}:{b}" for b in blocks),
        }
        path = os.path.join(TAG_DIR, f"{tool}.json")
        write_json(path, tag)
        written[tool] = len(blocks)

    # Tag dla narzedzia, ktore nie ma juz zadnego bloku, usuwamy - inaczej
    # zostaje plik "na zapas" i myli przy czytaniu danych.
    for name in os.listdir(TAG_DIR) if os.path.isdir(TAG_DIR) else []:
        if not name.endswith(".json"):
            continue
        tool = name[: -len(".json")]
        if tool not in written:
            os.remove(os.path.join(TAG_DIR, name))
    return written


def main():
    if not os.path.exists(REGISTRY):
        print(f"BLAD: nie znajduje rejestru {REGISTRY}", file=sys.stderr)
        return 1

    blocks = registered_block_ids()
    if not blocks:
        print("BLAD: nie znalazlem zadnego bloku w rejestrze", file=sys.stderr)
        return 1

    print(f"blokow w rejestrze: {len(blocks)}")
    created, kept, skipped = generate_loot_tables(blocks)
    print(f"  loot table dopisane: {len(created)}" + (f" -> {created}" if created else ""))
    print(f"  loot table istniejace (nietkniete): {len(kept)}")
    if skipped:
        print(f"  celowo bez dropu: {skipped}")

    tools = generate_mineable_tags(blocks)
    for tool, n in sorted(tools.items()):
        print(f"  tag mineable/{tool}: {n} blok(ow)")

    known = set(TOOL_BY_BLOCK) | NO_DROP
    unknown = [b for b in blocks if b not in known]
    if unknown:
        print(f"  uwaga: nowe bloki bez przypisanego narzedzia (dostaly '{DEFAULT_TOOL}'): {unknown}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
