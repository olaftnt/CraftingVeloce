#!/usr/bin/env python3
"""
Buduje craftingveloce-1.0.0.jar i wdraza go do profilu testing.

Uzycie:
    python3 scripts/build.py

Skrypt robi wszystko po kolei i PRZERYWA przy pierwszym bledzie, zamiast
zostawiac polowiczny JAR:

  1. kompiluje wszystkie zrodla OPROCZ src/moze_intel i src/eatawesome
     (to klasy obcego moda - w naszym JARze powodowalyby ResolutionException)
  2. sprawdza, ze kazda klasa importowana przez kod faktycznie istnieje
     w skompilowanym wyniku
  3. pakuje JAR, wykluczajac smieci systemowe (.DS_Store, __MACOSX)
  4. sprawdza wynik: sciezka klasy, brak duplikatow com/com, META-INF,
     brak wyciekow obcych pakietow
  5. kopiuje JAR do profilu testing

UWAGA: nie podmieniaj JARa gdy Minecraft dziala. Gra trzyma juz zaladowane
klasy w pamieci i po podmianie pliku moze rzucic NoClassDefFoundError dla
klas, ktorych w zaladowanej wersji nie bylo.
"""
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

MODS = ("/Users/olafalencynowicz/Library/Application Support/ModrinthApp/"
        "profiles/testing/mods")
DEPLOYED = os.path.join(MODS, "craftingveloce-1.0.0.jar")
JAR_NAME = "craftingveloce-1.0.0.jar"
STAGING = "craftingveloce_jar_root"
BUILD_OUT = "/tmp/craftingveloce_build"

# Katalog na zaleznosci kompilacyjne (compileOnly). W .gitignore (*.jar),
# bo to cudze mody - nigdy nie moga trafic do naszego JARa.
LIBS = "libs"

# Zaleznosci kompilacyjne opcjonalnych integracji (compileOnly).
#
# Klucz = prefiks pakietu obcego moda. Po nim POZNAJEMY, ktore JAR-y sa
# potrzebne: jesli zaden plik w src/ nie importuje tego pakietu, JAR nie jest
# wymagany. Dzieki temu etap "same bramki, zero blokow" kompiluje sie takze
# bez tych modow, a etap z blokami od razu zglasza brakujacy JAR.
#
# Wartosc = krotka WYMAGANYCH artefaktow; kazdy artefakt to lista NAZW
# (wariantow) do wyboru - pierwszy znaleziony wygrywa. Np. Mekanism ma wariant
# "sam API" i "pelny JAR": oba wystarcza do kompilacji, ale API jest maly.
# JAR-y szukamy w kilku katalogach (rozne maszyny trzymaja je roznie), a
# brakujace biblioteki z META-INF/jarjar wyciagamy do libs/.
COMPILE_ONLY = {
    "com.simibubi.create": (
        ("create-1.21.1-6.0.10.jar",),
        ("ponder-neoforge-1.0.82+mc1.21.1.jar",),
    ),
    "net.createmod": (
        ("create-1.21.1-6.0.10.jar",),
        ("ponder-neoforge-1.0.82+mc1.21.1.jar",),
    ),
    "com.smashingmods.alchemistry": (
        ("alchemistry-1.21.1-2.4.5.jar",),
        ("alchemylib-1.21.1-1.1.6.jar",),
        ("chemlib-1.21.1-2.1.5.jar",),
    ),
    "com.smashingmods.alchemylib": (
        ("alchemylib-1.21.1-1.1.6.jar",),
        ("chemlib-1.21.1-2.1.5.jar",),
    ),
    "mekanism": (
        ("Mekanism-1.21.1-10.7.19.85-api.jar",
         "Mekanism-1.21.1-10.7.19.85.jar"),
    ),
}

# Katalogi, w ktorych szukamy JAR-ow compileOnly (w tej kolejnosci).
COMPILE_ONLY_DIRS = (LIBS, MODS, os.path.expanduser("~/Downloads"))

# Pakiety obcego moda - nigdy nie moga trafic do naszego JARa.
EXCLUDED_SRC = ("eatawesome", "moze_intel")

# Pakiety obcych modow = granica izolacji. Rdzen (wszystko poza compat/)
# nie moze ich importowac, a modul compat/ danego moda nie moze importowac
# innego obcego moda (latwo o pomylke przy kopiowaniu pliku).
FOREIGN_PACKAGES = ("com.simibubi.create", "net.createmod",
                    "com.smashingmods", "mekanism")
FOREIGN_JAR_PATHS = ("com/simibubi/create/", "net/createmod/",
                     "com/smashingmods/", "mekanism/")


# Smieci systemowe, ktore nie moga trafic do JARa.
JUNK = (".DS_Store", "__MACOSX", ".git")

# Lista blokow pochodzi z generatora danych, a NIE z drugiej, recznej listy.
# Ten projekt czterokrotnie ugryzl juz wlasnie taki rozjazd dwoch spisow.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_loot_tables import registered_block_ids  # noqa: E402


def validate_block_data(jar_names):
    """
    Kazdy zarejestrowany blok musi miec w JARze komplet danych.

    Sprawdzamy trzy rzeczy, ktorych brak jest widoczny dopiero w grze:
      * loot table    - bez niej blok NIE WYPADA po zniszczeniu,
      * blockstate    - bez niego blok sie nie renderuje,
      * model itemu   - bez niego blok nie ma ikony w ekwipunku i kreatywnym.
    """
    names = set(jar_names)
    missing = []
    for block_id in registered_block_ids():
        for opis, sciezka in (
            ("loot table", f"data/craftingveloce/loot_table/blocks/{block_id}.json"),
            ("blockstate", f"assets/craftingveloce/blockstates/{block_id}.json"),
            ("model itemu", f"assets/craftingveloce/models/item/{block_id}.json"),
        ):
            if sciezka not in names:
                missing.append(f"{block_id}: brak {opis} ({sciezka})")
    if missing:
        fail("bloki bez kompletu danych:\n  " + "\n  ".join(missing))
    print(f"    OK (dane {len(registered_block_ids())} blokow kompletne)")


def _record_components(path):
    """(nazwa, lista skladnikow) rekordu-pakietu z jego pliku zrodlowego."""
    text = open(path, encoding="utf-8").read()
    m = re.search(r"public record\s+(\w+)\s*\(", text)
    if not m:
        return None, None
    start = m.end() - 1
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return m.group(1), text[start + 1:i]
    return m.group(1), None


def _split_components(body):
    """Dzieli naglowek rekordu po przecinkach NA POZIOMIE 0 (generyki!)."""
    out, depth, cur = [], 0, ""
    for ch in body:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def _normalize_signature(text):
    """Skladniki bez pakietow: 'net.minecraft.core.BlockPos pos' -> 'BlockPos pos'."""
    text = text.replace("`", "").replace("@Nullable", "")
    text = re.sub(r"(?<![A-Za-z0-9_])(?:[a-z][a-z0-9_]*\.)+", "", text)  # segmenty-pakiety
    return re.sub(r"\s+", " ", text).strip()


def validate_packet_docs():
    """
    Kazdy zarejestrowany pakiet musi byc wymieniony w README - Z SYGNATURA.

    Tabela pakietow w README jest pisana recznie, a rejestracja pakietow zyje
    w VelocePacketHandler.java. To dwa spisy tej samej rzeczy, wiec bez kontroli
    rozjezdzaja sie same - i juz sie rozjezdaly: README wymienial dwa pakiety,
    ktorych nie ma, nie znal osmiu nowych, a potem przez kilka zmian opisywal
    `OpenControllerScreenPKT` z polem `hotbar`, ktorego juz nie bylo.

    Dlatego sprawdzamy nie tylko NAZWE, ale i SKLADNIKI: naglowek rekordu
    kontra komorka tabeli. Nazwa bez pol to dokument, ktory klamie polowicznie.
    """
    handler = os.path.join("src/com/craftingveloce/network/VelocePacketHandler.java")
    readme = "README.md"
    if not os.path.exists(handler) or not os.path.exists(readme):
        return
    registered = sorted(set(re.findall(
        r"playTo(?:Server|Client)\((\w+)\.TYPE", open(handler, encoding="utf-8").read())))
    doc = open(readme, encoding="utf-8").read()
    missing = [p for p in registered if p not in doc]
    if missing:
        fail("pakiety zarejestrowane, ale nieopisane w README.md:\n  " + "\n  ".join(missing))

    # Sygnatury: naglowek rekordu kontra trzecia kolumna wiersza tabeli.
    mismatches = []
    for name in registered:
        path = os.path.join("src/com/craftingveloce/network", name + ".java")
        if not os.path.exists(path):
            continue
        _, body = _record_components(path)
        if body is None:
            continue
        row = re.search(r"^\|\s*`" + re.escape(name) + r"`\s*\|[^|]*\|([^|]*)\|",
                        doc, re.MULTILINE)
        if not row:
            mismatches.append(f"{name}: brak wiersza w tabeli")
            continue
        code = _normalize_signature(body)
        docs = _normalize_signature(row.group(1))
        if code != docs:
            mismatches.append(f"{name}:\n    kod:   {code}\n    README: {docs}")
    if mismatches:
        fail("sygnatury pakietow niezgodne z README.md:\n  " + "\n  ".join(mismatches))
    print(f"    OK (README opisuje wszystkie {len(registered)} pakietow - nazwy i sygnatury)")


def validate_lang_keys():
    """
    Kazdy klucz tlumaczenia uzyty w kodzie musi istniec w en_us.json.

    Brakujacy klucz NIE jest bledem kompilacji - gracz zobaczy w GUI surowy
    napis "gui.craftingveloce.controller.flow.churn" i nikt tego nie zauwazy
    w testach, bo kod "dziala". To dokladnie ta klasa bledu, ktora ten projekt
    juz raz mial (klucz skasowany razem z metoda, a uzywany gdzie indziej).
    """
    lang_path = os.path.join("assets/craftingveloce/lang/en_us.json")
    if not os.path.exists(lang_path):
        return
    lang = json.load(open(lang_path, encoding="utf-8"))
    prefixes = {k.split(".")[0] for k in lang}
    used = set()
    for root, _, files in os.walk("src"):
        for name in files:
            if not name.endswith(".java"):
                continue
            text = open(os.path.join(root, name), encoding="utf-8").read()
            for lit in re.findall(r'"([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"', text):
                if lit.split(".")[0] in prefixes:
                    used.add(lit)
    missing = sorted(k for k in used if k not in lang)
    if missing:
        fail("klucze jezykowe uzywane w kodzie, a nieobecne w en_us.json:\n  "
             + "\n  ".join(missing))
    unused = sorted(k for k in lang
                    if k not in used and not k.startswith(("block.", "item.")))
    info = f"    OK ({len(used)} kluczy uzywanych, wszystkie obecne"
    if unused:
        info += f"; nieuzywane: {', '.join(unused)}"
    print(info + ")   [block./item. pomijam - te tworzy rejestr]")


def _balanced(text, open_index):
    """Zawartosc nawiasow, ktore zaczynaja sie na open_index (poziom 0 w srodku)."""
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i]
    return None


def validate_helper_docs():
    """
    Sygnatury metod ClientTerminalHelper w README musza sie zgadzac z kodem.

    README sam o to prosi ("pilnuj, zeby ta lista nie zostala w tyle"), a i tak
    zostala: `openControllerScreen` jeszcze dlugo opisywal parametr `hotbar`,
    ktorego nie bylo od dwoch sesji. Dokladnie ta sama klasa bledu, co tabela
    pakietow - dlatego dostaje ten sam rodzaj kontroli.

    Sprawdzamy:
      * kazda metoda opisana w README istnieje w kodzie,
      * liczba parametrow sie zgadza,
      * gdy README podaje pelne typy - takze same typy.
    Metody zapisane skrotowo (bez typow, np. `handleSyncCounts(a, b)`) sprawdzamy
    tylko po liczbie parametrow, bo nie da sie ich porownac doslownie.
    """
    readme = "README.md"
    src = "src/com/craftingveloce/client/ClientTerminalHelper.java"
    if not os.path.exists(readme) or not os.path.exists(src):
        return
    doc = open(readme, encoding="utf-8").read()
    m = re.search(r"^### ClientTerminalHelper\s*$(.*?)^(?:---|## )", doc,
                  re.MULTILINE | re.DOTALL)
    if not m:
        return
    section = m.group(1)
    java = open(src, encoding="utf-8").read()

    methods = {}
    for mm in re.finditer(r"public static [\w<>,\[\]\. ]+?\s(\w+)\s*\(", java):
        params = _balanced(java, mm.end() - 1)
        if params is None:
            continue
        methods.setdefault(mm.group(1), []).append(_normalize_signature(params))

    problems = []
    documented = set()
    for dm in re.finditer(r"`(\w+)\(([^`]*)\)`", section):
        name, raw = dm.group(1), dm.group(2)
        documented.add(name)
        if name not in methods:
            problems.append(f"{name}: opisana w README, a nie ma jej w kodzie")
            continue
        parts = [p for p in _split_components(raw) if p.strip()]
        typed = all(" " in p.strip() for p in parts)
        candidates = [p for p in methods[name]
                      if len([x for x in _split_components(p) if x.strip()]) == len(parts)]
        if not candidates:
            arities = [len([x for x in _split_components(p) if x.strip()])
                       for p in methods[name]]
            problems.append(f"{name}: README podaje {len(parts)} parametr(ow), "
                            f"w kodzie jest {arities}")
        elif typed and _normalize_signature(raw) not in candidates:
            problems.append(f"{name}:\n    kod:    {candidates[0]}\n"
                            f"    README: {_normalize_signature(raw)}")

    missing = sorted(n for n in methods if n not in documented)
    if missing:
        problems.append("metody publiczne bez opisu w README: " + ", ".join(missing))

    if problems:
        fail("ClientTerminalHelper w README rozjechany z kodem:\n  "
             + "\n  ".join(problems))
    print(f"    OK (README opisuje {len(documented)} metod ClientTerminalHelper - "
          f"sygnatury zgodne)")


def validate_filter_labels():
    """
    Etykiety guzikow filtra kontrolera musza sie zmiescic w guziku.

    Ten przycisk ma 52 px, a poprzednie napisy ("Show all" = 8 znakow,
    "Not available" = 13) wychodzily za niego i nachodzily na sasiedni guzik.
    Szerokosci czcionki nie da sie tu zmierzyc dokladnie (to zalezy od
    zasobow gry), wiec liczymy szacunek: ~6 px na znak + 8 px na wewnetrzny
    odstep guzika. Kalibracja: ten wzor flaguje OBA napisy, ktore kiedys
    naprawde sie nie miescily, a przepuszcza obecne ("All", "Active", "None").
    """
    lang_path = os.path.join("assets/craftingveloce/lang/en_us.json")
    screen = "src/com/craftingveloce/client/gui/VeloceControllerScreen.java"
    if not os.path.exists(lang_path) or not os.path.exists(screen):
        return
    text = open(screen, encoding="utf-8").read()
    m = re.search(r"FILTER_BUTTON_W\s*=\s*(\d+)", text)
    if not m:
        fail("brak stalej FILTER_BUTTON_W w VeloceControllerScreen")
    width = int(m.group(1))
    lang = json.load(open(lang_path, encoding="utf-8"))

    keys = [k for k in lang if k.startswith("gui.craftingveloce.controller.filter.")
            and not k.endswith(".tip")]
    too_long = []
    for key in sorted(keys):
        label = lang[key]
        # ~6 px na znak + 8 px odstepu wewnatrz guzika
        estimated = len(label) * 6 + 8
        if estimated > width:
            too_long.append(f"{key} = '{label}' (~{estimated} px > {width} px)")
    if too_long:
        fail("etykiety filtra nie mieszcza sie w guziku:\n  " + "\n  ".join(too_long))
    print(f"    OK (etykiety filtra mieszcza sie w {width} px: "
          + ", ".join(f"'{lang[k]}'" for k in sorted(keys)) + ")")


def validate_gui_layout():
    """
    Wspolrzedne GUI zyja w kilku plikach i musza sie zgadzac.

    Kto z kim, zalezy od tego, kto czego uzywa:
      * FILTER_X/Y  - menu stawia sloty, ekran rysuje ikony filtrow,
                      generator maluje ramki  -> wszystkie trzy,
      * FUEL_X/Y    - menu (slot) + generator (ramka),
      * PLAYER_X/Y  - menu (ekwipunek) + generator (ramki),
      * FLAME_X/Y   - ekran (sprite plomienia) + generator (pozycja do opisu).

    Raz juz sie to rozjechalo: slot paliwa stal tam, gdzie mowilo menu, a
    tekstura miala ramke gdzie indziej - slot nachodzil na napis "Inventory".
    Kompilator tego nie widzi, a w grze wyglada jak blad grafiki.
    """
    def consts(path, names):
        text = open(path, encoding="utf-8").read()
        out = {}
        for n in names:
            m = re.search(r"\b" + re.escape(n) + r"\s*=\s*(-?\d+)", text)
            out[n] = int(m.group(1)) if m else None
        return out

    paths = {
        "menu": "src/com/craftingveloce/inventory/VeloceVelocityFurnaceMenu.java",
        "ekran": "src/com/craftingveloce/client/gui/VeloceVelocityFurnaceScreen.java",
        "generator": "scripts/gen_furnace_gui.py",
    }
    # Bateria i jej slot zyja w menu pieca ELEKTRYCZNEGO i w jego ekranie.
    paths["menu_el"] = "src/com/craftingveloce/inventory/VeloceElectricFurnaceMenu.java"
    paths["ekran_el"] = "src/com/craftingveloce/client/gui/VeloceElectricFurnaceScreen.java"
    # Sensor: slot itemu stawia menu, a ramke pod nim maluje generator GUI.
    # To ta sama para co w piecu - i wlasnie ta para byla poza kontrola, dopoki
    # generator zapisywal ja krotko ("FILTER_X, FILTER_Y = 26, 18"), czego
    # wyrazenie regularne nie widzi.
    paths["menu_s"] = "src/com/craftingveloce/inventory/VeloceThresholdSensorMenu.java"
    paths["gen_s"] = "scripts/gen_sensor_textures.py"
    names = ["FILTER_X", "FILTER_Y", "FUEL_X", "FUEL_Y", "PLAYER_X", "PLAYER_Y",
             "FLAME_X", "FLAME_Y",
             "BATTERY_X", "BATTERY_Y", "BATTERY_W", "BATTERY_H", "NUB_W", "NUB_H",
             "BATTERY_SLOT_X", "BATTERY_SLOT_Y",
             "FILTER_SLOT_X", "FILTER_SLOT_Y"]
    # Domyslnie porownujemy trojke pieca PALIWOWEGO: menu, ekran, generator.
    # Pozostale elementy maja wlasne listy nizej.
    who = {n: ["menu", "ekran", "generator"] for n in names}
    for n in ("FILTER_SLOT_X", "FILTER_SLOT_Y"):
        who[n] = ["menu_s", "gen_s"]            # sensor: menu + generator
    for n in ("FUEL_X", "FUEL_Y", "PLAYER_X", "PLAYER_Y"):
        who[n] = ["menu", "generator"]          # ekran ich nie potrzebuje
    for n in ("FLAME_X", "FLAME_Y"):
        who[n] = ["ekran", "generator"]         # menu ich nie potrzebuje
    for n in ("BATTERY_X", "BATTERY_Y", "BATTERY_W", "BATTERY_H", "NUB_W", "NUB_H"):
        who[n] = ["ekran_el", "generator"]      # bateria pieca elektrycznego
    for n in ("BATTERY_SLOT_X", "BATTERY_SLOT_Y"):
        # Ekran elektryczny UZYWA stalej z menu (nie ma wlasnej kopii) - i tak
        # ma byc: jedno zrodlo. Generator maluje ramke pod ta pozycja.
        who[n] = ["menu_el", "generator"]

    values = {n: {k: consts(paths[k], [n])[n] for k in who[n]} for n in names}

    problems = []
    for n in names:
        v = values[n]
        if any(x is None for x in v.values()):
            problems.append(f"{n}: brak stalej (" + ", ".join(
                f"{k}={'brak' if x is None else x}" for k, x in v.items()) + ")")
        elif len(set(v.values())) > 1:
            problems.append(f"{n}: " + ", ".join(f"{k}={x}" for k, x in v.items()))

    if problems:
        fail("uklad GUI nie zgadza sie miedzy plikami:\n  " + "\n  ".join(problems))
    print("    OK (uklad GUI zgodny: " + ", ".join(
        f"{n}={values[n][who[n][0]]}" for n in names) + ")")


def validate_sensor_row():
    """
    Wiersz sensora: wysrodkowany W OBIE STRONY, rowne odstepy, nic na sobie.

    Gracz poprosil wprost o JEDEN wyrownany rzad (slot itemu, pole liczby, "+",
    "-", guzik trybu), wycentrowany - najpierw w poziomie, a potem doszedl
    drugi zglos: "w poziomie ok, ale w pionie za wysoko". Wspolrzedne sa recznie
    policzonymi liczbami, a taka literowke widac dopiero w grze - jako krzywy
    GUI. Tutaj liczymy to samo, co widzi gracz: marginesy poziome, marginesy
    pionowe w polu roboczym (tytul -> ekwipunek) i wysrodkowanie slotu w rzedzie.
    """
    path = "src/com/craftingveloce/inventory/VeloceThresholdSensorMenu.java"
    if not os.path.exists(path):
        return
    text = open(path, encoding="utf-8").read()
    names = ["PANEL_WIDTH", "GAP", "SLOT_SIZE", "FIELD_W", "FIELD_X",
             "BTN_W", "FILTER_SLOT_X", "STEP_PLUS_X", "STEP_MINUS_X", "MODE_X",
             "ROW_Y", "ROW_H", "FILTER_SLOT_Y", "TITLE_BOTTOM", "PLAYER_Y"]
    v = {}
    for n in names:
        m = re.search(r"\b" + re.escape(n) + r"\s*=\s*(-?\d+)", text)
        if not m:
            fail(f"brak stalej {n} w {path} (build.py czyta je pojedynczo)")
        v[n] = int(m.group(1))

    pieces = [("slot", v["FILTER_SLOT_X"], v["SLOT_SIZE"]),
              ("pole", v["FIELD_X"], v["FIELD_W"]),
              ("+", v["STEP_PLUS_X"], v["BTN_W"]),
              ("-", v["STEP_MINUS_X"], v["BTN_W"]),
              ("tryb", v["MODE_X"], v["BTN_W"])]

    problems = []
    for (an, ax, aw), (bn, bx, _bw) in zip(pieces, pieces[1:]):
        gap = bx - (ax + aw)
        if gap != v["GAP"]:
            problems.append(f"odstep {an} -> {bn} = {gap} px, ma byc {v['GAP']}")
    left = pieces[0][1]
    right = v["PANEL_WIDTH"] - (pieces[-1][1] + pieces[-1][2])
    if left != right:
        problems.append(f"wiersz nie jest wysrodkowany poziomo: "
                        f"lewy margines {left}, prawy {right}")

    # Pion: polem roboczym jest odstep miedzy tytulem a ekwipunkiem gracza.
    above = v["ROW_Y"] - v["TITLE_BOTTOM"]
    below = v["PLAYER_Y"] - (v["ROW_Y"] + v["ROW_H"])
    if below < 0:
        problems.append(f"wiersz (y={v['ROW_Y']}..{v['ROW_Y'] + v['ROW_H']}) "
                        f"wchodzi na ekwipunek gracza (y={v['PLAYER_Y']})")
    elif abs(above - below) > 1:
        problems.append(f"wiersz nie jest wysrodkowany w pionie: nad nim "
                        f"{above} px, pod nim {below} px")

    # Slot 16 px ma byc wysrodkowany w rzedzie 20 px.
    slot_above = v["FILTER_SLOT_Y"] - v["ROW_Y"]
    slot_below = v["ROW_Y"] + v["ROW_H"] - (v["FILTER_SLOT_Y"] + v["SLOT_SIZE"])
    if abs(slot_above - slot_below) > 1:
        problems.append(f"slot nie jest wysrodkowany w rzedzie: nad nim "
                        f"{slot_above} px, pod nim {slot_below} px")

    if problems:
        fail("wiersz sensora:\n  " + "\n  ".join(problems))
    row_w = pieces[-1][1] + pieces[-1][2] - pieces[0][1]
    print(f"    OK (wiersz sensora: {row_w} px, margines {left} px z bokow, "
          f"{above} px nad i {below} px pod)")


def validate_node_blocks():
    """
    Kto jest wezlem sieci, musi to mowic JEDNYM sposobem - interfejsem.

    Wezel sieci rur ma dwa obowiazki, ktore musza isc w parze:
      * implementowac {@code VeloceNetworkNode} (inaczej rdzen go nie
        rozpozna: nie trafi do terminali i jego chunk nie bedzie utrzymywany),
      * wolac {@code VeloceNodeBlocks.onNodePlaced} / {@code onNodeRemoved}
        (inaczej siec nie dowie sie o postawieniu/zniszczeniu bloku).

    Ta para juz sie raz rozjechala: kontroler nie byl rozpoznawany jako wezel,
    wiec jego GUI pokazywalo pusty stock, a gniazda filtrów mogly zostac
    wystawione sieci jako zwykly magazyn. Oba bledy sa niewidoczne dla
    kompilatora - dlatego pilnuje ich build.
    """
    # Bloki rdzenia ORAZ bloki modulow: maszyna z compat/ tez jest wezlem sieci
    # (inaczej crafter by jej nie widzial), a latwo o tym zapomniec wlasnie
    # w module, ktory dopiero powstaje.
    block_dirs = ["src/com/craftingveloce/block"]
    block_dirs += sorted(glob.glob("src/com/craftingveloce/compat/*/block"))
    files = []
    for block_dir in block_dirs:
        if not os.path.isdir(block_dir):
            continue
        files += [os.path.join(block_dir, name)
                  for name in sorted(os.listdir(block_dir))
                  if name.endswith(".java")]
    if not files:
        return
    nodes, hooked = [], []
    for path in files:
        name = os.path.relpath(path, "src/com/craftingveloce")
        text = open(path, encoding="utf-8").read()
        is_interface = re.search(r"class\s+\w+[^{]*\bimplements\b[^{]*\bVeloceNetworkNode\b",
                                 text) is not None
        has_hook = "VeloceNodeBlocks.onNodePlaced(" in text
        has_remove = "VeloceNodeBlocks.onNodeRemoved(" in text
        if is_interface:
            nodes.append(name)
            if not (has_hook and has_remove):
                fail(f"{name} implementuje VeloceNetworkNode, ale nie wola "
                     f"{'onNodePlaced' if not has_hook else 'onNodeRemoved'} - "
                     f"siec nie dowie sie o zmianie tego bloku")
        elif has_hook or has_remove:
            hooked.append(name)
    if hooked:
        fail("te bloki zglaszaja sie do sieci, ale nie sa wezlami "
             "(brak VeloceNetworkNode):\n  " + "\n  ".join(hooked))
    if not nodes:
        fail("zaden blok nie implementuje VeloceNetworkNode - "
             "rozpoznawanie wezlow sieci jest zepsute")
    print(f"    OK ({len(nodes)} wezlow sieci: interfejs + hooki parami)")


def validate_recipe_model():
    """
    Jedna regula o recepturach = jedno miejsce w kodzie.

    Ten projekt ma powtarzalny blad: te sama regule zapisano recznie w dwoch
    miejscach i miejsca sie rozjechaly (lista wezlow sieci, lista packetow,
    uklad GUI). Przy recepturach byly az TRZY kopie listy typow (rejestr,
    graf, GUI craftera) i DWIE kopie filtra "special" - przez ten drugi filtr
    receptury modow byly po cichu wyrzucane (Mekanism oznacza tak wszystkie
    swoje), wiec zaden modul z innego moda nie mialby czego liczyc.

    Pilnujemy trzech niezmiennikow:
      1. istnieje JEDEN model receptury (ProcessingEntry) - bez drugiego
         rekordu CraftingEntry,
      2. liste typow receptur wolno deklarowac WYLACZNIE w
         VeloceRecipeFamilies.java (reszta musi ja brac z tamtej klasy),
      3. filtr "special" wolno stosowac WYLACZNIE w
         VeloceRecipeRegistry.isVanillaSpecial (zawężenie do namespace
         minecraft), a nie bezposrednio przez recipe.isSpecial().
    """
    src_root = "src"
    if not os.path.isdir(src_root):
        return
    family_list = re.compile(
        r"(?:static\s+)?(?:final\s+)?Set\s*<\s*RecipeType\s*<\s*\?\s*>\s*>\s+\w+\s*=\s*Set\.of")
    dup_lists, dup_special, dup_model = [], [], []
    for root, _dirs, files in os.walk(src_root):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8").read()
            rel = path.replace(os.sep, "/")
            if rel.endswith("VeloceRecipeFamilies.java"):
                continue
            if re.search(r"\brecord\s+CraftingEntry\s*\(", text):
                dup_model.append(rel)
            if family_list.search(text):
                dup_lists.append(rel)
            if ".isSpecial()" in text:
                if rel.endswith("crafting/VeloceRecipeRegistry.java"):
                    # Filtr WOLNO stosowac tylko tutaj - i dokladnie w jednym
                    # miejscu (isVanillaSpecial). Druga proba w tym samym pliku
                    # znaczylaby drugi filtr obok zawężonego do minecraft.
                    uses = text.count(".isSpecial()")
                    if uses != 1:
                        dup_special.append(rel + f" ({uses}x, ma byc 1x)")
                else:
                    dup_special.append(rel)
    problems = []
    if dup_model:
        problems.append("drugi model receptury (record CraftingEntry) w: "
                        + ", ".join(dup_model))
    if dup_lists:
        problems.append("wlasna kopia listy typow receptur (ma byc tylko "
                        "VeloceRecipeFamilies) w: " + ", ".join(dup_lists))
    if dup_special:
        problems.append("filtrowanie po recipe.isSpecial() poza "
                        "isVanillaSpecial() w: " + ", ".join(dup_special))
    if problems:
        fail("model receptur:\n  " + "\n  ".join(problems))
    entries = os.path.join(src_root, "com/craftingveloce/crafting/ProcessingEntry.java")
    if not os.path.exists(entries):
        fail("brak ProcessingEntry - jedynego modelu receptury")
    print("    OK (jeden model receptury, jedna lista rodzin, jeden filtr special)")


def _imports_of(path):
    """Pelen zbiór importowanych nazw w pliku (prosto i bez parsera)."""
    text = open(path, encoding="utf-8").read()
    return [m.group(1) for m in re.finditer(r"^import\s+([\w.]+);", text, re.M)]


def foreign_packages_used():
    """
    Prefiksy obcych pakietow, ktore sa FAKTYCZNIE importowane w src/.

    Dzieki temu etap "same bramki, zero blokow" nie wymaga JAR-ow obcych
    modow, a pierwszy plik z obcym typem od razu je wymusza.
    """
    used = set()
    for path in glob.glob("src/**/*.java", recursive=True):
        if any(x in path for x in EXCLUDED_SRC):
            continue
        for name in _imports_of(path):
            for pkg in COMPILE_ONLY:
                if name == pkg or name.startswith(pkg + "."):
                    used.add(pkg)
    return used


def _find_jar(name):
    for directory in COMPILE_ONLY_DIRS:
        path = os.path.join(directory, name)
        if os.path.exists(path):
            return path
    return None


def _extract_nested_jar(name):
    """
    Wyciaga JAR z META-INF/jarjar innego moda do libs/.

    Create dolacza swoje biblioteki (Ponder, Flywheel, Registrate) jako
    zagniezdzone JAR-y. Do kompilacji wystarczy Ponder - bez niego javac
    nie widzi klasy bazowej bloku kinetycznego ("cannot access
    VirtualBlockEntity").
    """
    for directory in COMPILE_ONLY_DIRS:
        if not os.path.isdir(directory):
            continue
        for jar in sorted(glob.glob(os.path.join(directory, "*.jar"))):
            try:
                with zipfile.ZipFile(jar) as archive:
                    member = "META-INF/jarjar/" + name
                    if member not in archive.namelist():
                        continue
                    os.makedirs(LIBS, exist_ok=True)
                    dest = os.path.join(LIBS, name)
                    with archive.open(member) as src, open(dest, "wb") as out:
                        shutil.copyfileobj(src, out)
                    print(f"    wyciagnieto {name} z {os.path.basename(jar)}")
                    return dest
            except zipfile.BadZipFile:
                continue
    return None


def resolve_compile_only():
    """JAR-y compileOnly potrzebne do TEJ kompilacji (moze byc pusto)."""
    used = foreign_packages_used()
    if not used:
        return []
    paths, missing, seen = [], [], set()
    for pkg in sorted(used):
        for variants in COMPILE_ONLY[pkg]:
            if variants[0] in seen:
                continue
            found = None
            for name in variants:
                found = _find_jar(name) or _extract_nested_jar(name)
                if found:
                    seen.add(variants[0])
                    break
            if found:
                paths.append(found)
            else:
                missing.append(" albo ".join(variants))
    if missing:
        fail("brak zaleznosci kompilacyjnych: " + ", ".join(sorted(set(missing)))
             + "\n  importy obcych pakietow w src/: " + ", ".join(sorted(used))
             + "\n  szukalem w: " + ", ".join(COMPILE_ONLY_DIRS)
             + "\n  wloz JAR-y tam (albo do libs/) i uruchom ponownie")
    return paths


def validate_core_isolation():
    """
    Rdzen nie zna obcych modow, a modul compat zna TYLKO swojego moda.

    Dwa niezmienniki:
      1. zaden plik poza {@code compat/} nie importuje obcego pakietu - inaczej
         mod nie wstaje bez tamtego moda (NoClassDefFoundError przy linkowaniu),
      2. modul {@code compat/<mod>} nie importuje innego obcego moda - to
         najczestszy blad przy kopiowaniu pliku z jednej integracji do drugiej
         i objawia sie dopiero u gracza, ktory ma tylko jeden z tych modow.
    """
    owners = {
        "create": ("com.simibubi.create", "net.createmod"),
        "alchemistry": ("com.smashingmods",),
        "mekanism": ("mekanism",),
    }
    core, cross, checked = [], [], 0
    for path in glob.glob("src/com/craftingveloce/**/*.java", recursive=True):
        rel = path.replace(os.sep, "/")
        foreign = [n for n in _imports_of(path)
                   if any(n == p or n.startswith(p + ".") for p in FOREIGN_PACKAGES)]
        if not foreign:
            continue
        checked += 1
        marker = "/compat/"
        if marker not in rel:
            core.append(f"{rel} -> {foreign[0]}")
            continue
        owner = rel.split(marker, 1)[1].split("/", 1)[0]
        allowed = owners.get(owner, ())
        for name in foreign:
            if not any(name == p or name.startswith(p + ".") for p in allowed):
                cross.append(f"{rel} -> {name} (modul '{owner}')")
    problems = []
    if core:
        problems.append("rdzen importuje obce mody:\n  " + "\n  ".join(core))
    if cross:
        problems.append("modul compat importuje obcego moda:\n  " + "\n  ".join(cross))
    if problems:
        fail("izolacja modulow:\n  " + "\n  ".join(problems))
    print(f"    OK (izolacja: {checked} plikow z obcymi importami, wszystkie w compat/)")


def validate_compat_gates(z):
    """
    Klasy ladowane ZAWSZE nie moga miec obcego typu w swojej sygnaturze.

    To najwazniejszy niezmiennik izolacji i jednoczesnie najlatwiejszy do
    zlamania: dopisanie pola albo parametru typu obcego moda do klasy bramki
    wywala moda bez tego moda. Powod jest mechaniczny - JVM musi znac typy
    z sygnatur, zeby zweryfikowac klase, a ciala metod tylko przy ich
    WYWOLANIU.

    Sprawdzamy cztery grupy klas (wszystkie ladowane bezwarunkowo):
      1. rdzen (poza {@code compat/}),
      2. klasy w samym {@code compat/} (np. VeloceMods - enum modow),
      3. klasy-bramki {@code compat/<mod>/XCompat},
      4. klasy w {@code compat/<mod>/} - z wyjatkiem bramek - sa ladowane
         TYLKO po sprawdzeniu obecnosci moda, wiec one moga miec obce typy.

    UWAGA: to musi byc kontrola SYGNATUR, a nie test ladowania klasy. HotSpot
    rozwiazuje typy leniwie, wiec klasa z nieuzywanym polem obcego typu
    zaladuje sie bez obcego moda - i wywali sie dopiero, gdy ktos dotknie tego
    pola (np. rok pozniej, przy okazji innej zmiany). Kalibracja: wstrzykniecie
    pola typu Create do VeloceMods przechodzi test L1, a MUSI byc zlapane tutaj.
    """
    gates = {
        "create": "com.craftingveloce.compat.create.CreateCompat",
        "alchemistry": "com.craftingveloce.compat.alchemistry.AlchemistryCompat",
        "mekanism": "com.craftingveloce.compat.mekanism.MekanismCompat",
    }
    module_dirs = ("com/craftingveloce/compat/create/",
                   "com/craftingveloce/compat/alchemistry/",
                   "com/craftingveloce/compat/mekanism/")
    gate_paths = {cls.replace(".", "/") + ".class" for cls in gates.values()}

    targets = []
    for name in z.namelist():
        if not name.startswith("com/craftingveloce/") or not name.endswith(".class"):
            continue
        in_module = any(name.startswith(d) for d in module_dirs)
        if in_module and name not in gate_paths:
            continue   # ladowane warunkowo - obce typy sa tu dozwolone
        targets.append(name[:-len(".class")].replace("/", "."))

    problems, checked = [], 0
    for cls in sorted(targets):
        res = subprocess.run(["javap", "-p", "-s", "-cp", BUILD_OUT, cls],
                             capture_output=True, text=True)
        if res.returncode != 0:
            problems.append(f"{cls}: javap nie powiodl sie ({res.stderr.strip()[:120]})")
            continue
        checked += 1
        for line in res.stdout.splitlines():
            stripped = line.strip()
            if not (stripped.endswith(";") or stripped.startswith("descriptor:")):
                continue
            # Nasze wlasne nazwy moga zawierac slowo "mekanism" (pakiet modulu
            # compat), wiec najpierw usuwamy cala nasza kwalifikacje - szukamy
            # obcego pakietu, a nie slowa.
            probe = re.sub(r"com\.craftingveloce(\.\w+)+", "", line)
            probe = re.sub(r"com/craftingveloce(/[\w$]+)+", "", probe)
            for pkg in FOREIGN_PACKAGES:
                if pkg in probe or pkg.replace(".", "/") in probe:
                    problems.append(f"{cls}: obcy typ w sygnaturze -> {stripped}")
    if problems:
        fail("izolacja sygnatur:\n  " + "\n  ".join(problems[:8]))
    missing = [c for c in gates.values() if c not in targets]
    if missing:
        fail("brak klas bramek w JARze: " + ", ".join(missing))
    print(f"    OK ({checked} klas ladowanych zawsze: zero obcych typow w sygnaturach)")


def validate_jar_isolation(z):
    """
    Poziom BAJTKODU: rdzen nie odwoluje sie do zadnej klasy obcego moda.

    Kontrola zrodel patrzy na linie {@code import} - a obcy typ moze byc
    uzyty bez importu (pelna nazwa w kodzie). Dlatego sprawdzamy gotowy
    artefakt: zadna nasza klasa SPOZA {@code compat/} nie moze miec w stalej
    puli odwolania do obcego pakietu. To ten sam niezmiennik, ale odporny na
    sposob zapisu w zrodle.
    """
    root = "com/craftingveloce/"
    compat = root + "compat/"
    own_prefix = compat.encode()
    patterns = [p.encode() for p in FOREIGN_JAR_PATHS]

    def foreign_refs(data):
        """Odwolania do obcych pakietow, pomijajac nasz wlasny katalog compat/."""
        found = []
        for pattern in patterns:
            start = 0
            while True:
                i = data.find(pattern, start)
                if i < 0:
                    break
                # Odwolanie do NASZEGO modulu compat
                # (com/craftingveloce/compat/mekanism/...) tez zawiera slowo
                # "mekanism/" - to nie wyciek.
                if not data[max(0, i - len(own_prefix)):i].endswith(own_prefix):
                    found.append(pattern.decode())
                    break
                start = i + 1
        return found

    bad = []
    for name in z.namelist():
        if not name.startswith(root) or name.startswith(compat) or not name.endswith(".class"):
            continue
        found = foreign_refs(z.read(name))
        if found:
            bad.append(f"{name} -> {', '.join(found)}")
    if bad:
        fail("rdzen odwoluje sie do obcych modow (poziom bajtkodu):\n  "
             + "\n  ".join(bad[:5]))
    print("    OK (bajtkod rdzenia bez odwolan do obcych modow)")


def validate_isolation_runtime(cp, toms, rs):
    """
    L1 na zywo: klasy bramek MUSZA dac sie zaladowac BEZ obcych modow.

    Statyczne kontrole (importy, bajtkod) pilnuja, gdzie lezy obcy typ.
    Ten test sprawdza SKUTEK: uruchamia maly program z classpath BEZ Create,
    Alchemistry i Mekanism, ktory laduje klasy bramek i pyta je o obecnosc
    modow. Gdyby bramka miala obcy typ w sygnaturze, samo jej zaladowanie
    rzuciloby NoClassDefFoundError - dokladnie to, co zobaczylby gracz bez
    tamtego moda (i czego nie da sie zobaczyc w kompilacji).
    """
    src = os.path.join("scripts", "isolation", "L1Test.java")
    if not os.path.exists(src):
        return
    out = "/tmp/craftingveloce_isolation"
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    classpath = os.pathsep.join([BUILD_OUT, cp, toms, rs])

    res = subprocess.run(["javac", "-nowarn", "-cp", classpath, "-d", out, src],
                         capture_output=True, text=True)
    if res.returncode != 0:
        fail("nie kompiluje sie test izolacji (scripts/isolation/L1Test.java):\n"
             + res.stderr[:600])
    # Uruchamiamy w katalogu tymczasowym, zeby logger (log4j z classpath MC)
    # nie tworzyl katalogu logs/ w repozytorium.
    res = subprocess.run(["java", "-cp", os.pathsep.join([out, classpath]), "L1Test"],
                         capture_output=True, text=True, cwd=out)
    if res.returncode != 0:
        lines = [l for l in res.stdout.splitlines() if l.startswith("FAIL") or l.startswith("BLAD")]
        fail("L1 (mod bez obcych modow) nie przeszedl:\n  " + "\n  ".join(lines[:8]))
    print("    OK (L1: bramki laduja sie bez obcych modow i mowia 'brak')")


def validate_module_block_ids():
    """
    Id bloku modulu musi zaczynac sie od {@code veloce_<mod>_}.

    Bez tego dwa moduly moga niezaleznie wybrac te sama nazwe (np. "combiner"
    istnieje i w Mekanism, i w Alchemistry) i jeden blok nadpisze drugi -
    a blad wyjdzie dopiero u gracza, ktory ma oba mody. Prefiks moda rozwiazuje
    to raz na zawsze i mowi od razu, z ktorej integracji pochodzi blok.
    """
    problems = []
    checked = 0
    for path in sorted(glob.glob("src/com/craftingveloce/compat/*/*Blocks.java")):
        rel = path.replace(os.sep, "/")
        mod = rel.split("/compat/", 1)[1].split("/", 1)[0]
        text = open(path, encoding="utf-8").read()
        for block_id in re.findall(r'BLOCKS\.register\(\s*"([a-z0-9_]+)"', text):
            checked += 1
            expected = f"veloce_{mod}_"
            if not block_id.startswith(expected):
                problems.append(f"{rel}: '{block_id}' nie zaczyna sie od '{expected}'")
    if problems:
        fail("nazwy blokow modulow:\n  " + "\n  ".join(problems))
    if checked:
        print(f"    OK ({checked} blokow modulow: nazwy z prefiksem moda)")


def validate_create_kinetics():
    """
    Blok kinetyczny Create MUSI implementowac {@code IBE}.

    Create tickuje swoje maszyny przez domyslny {@code getTicker} z
    {@code IBE} - bez tego interfejsu block entity nie jest tickowany nigdy,
    {@code getSpeed()} zostaje zerem, a maszyna na zawsze jest "bez napedu".

    To najgorszy rodzaj bledu: blok sie stawia, wyglada dobrze, w logach nie ma
    nic, a integracja po prostu nie dziala. Dlatego pilnuje tego build.
    """
    problems, checked = [], 0
    for path in sorted(glob.glob("src/com/craftingveloce/compat/*/block/*.java")):
        text = open(path, encoding="utf-8").read()
        if "extends KineticBlock" not in text:
            continue
        checked += 1
        if "IBE<" not in text:
            problems.append(path.replace(os.sep, "/")
                            + ": dziedziczy po KineticBlock, ale nie implementuje IBE<> "
                              "(BE nie bylby tickowany - maszyna nigdy sie nie kreci)")
    if problems:
        fail("kinetyka Create:\n  " + "\n  ".join(problems))
    if checked:
        print(f"    OK ({checked} blokow kinetycznych: IBE zapewnia tickowanie BE)")


def game_running():

    """
    Czy Minecraft z tego profilu wlasnie dziala?

    Potrzebne, zeby ostrzec, ze podmiana JARa nie zmieni DZIALAJACEJ sesji -
    jej classloader trzyma stary plik otwarty. Wczesniej nadpisywalismy JAR
    w miejscu i konczylo sie to ClassNotFoundException w losowym miejscu.
    """
    try:
        out = subprocess.run(["pgrep", "-fl", "MinecraftLaunch"],
                             capture_output=True, text=True, timeout=10)
        return out.returncode == 0 and bool(out.stdout.strip())
    except Exception:
        return False


def fail(msg):
    print(f"\nBLAD: {msg}")
    sys.exit(1)


def step(n, text):
    print(f"\n[{n}] {text}")


def main():
    # --- 1. kompilacja -------------------------------------------------
    step(1, "Kompilacja zrodel")
    cp = open("scripts/cp.txt").read().strip()
    toms = os.path.join(MODS, "toms_storage-1.21-2.4.2.jar")
    rs = os.path.join(MODS, "refinedstorage-neoforge-2.0.9.jar")
    for dep in (toms, rs):
        if not os.path.exists(dep):
            fail(f"brak zaleznosci: {dep}")

    sources = [f for f in glob.glob("src/**/*.java", recursive=True)
               if not any(x in f for x in EXCLUDED_SRC)]
    print(f"    plikow: {len(sources)} (pominieto {', '.join(EXCLUDED_SRC)})")

    extra_cp = resolve_compile_only()
    if extra_cp:
        print("    compileOnly: " + ", ".join(os.path.basename(p) for p in extra_cp))

    shutil.rmtree(BUILD_OUT, ignore_errors=True)
    os.makedirs(BUILD_OUT, exist_ok=True)
    res = subprocess.run(
        ["javac", "--release", "21", "-nowarn",
         "-cp", ":".join([cp, toms, rs] + extra_cp), "-d", BUILD_OUT] + sources,
        capture_output=True, text=True)
    if res.returncode != 0:
        errs = [l for l in res.stderr.split("\n") if "error" in l.lower()]
        fail("kompilacja nie powiodla sie:\n" + "\n".join(errs[:20]))
    print("    OK")

    # --- 2. weryfikacja klas ------------------------------------------
    step(2, "Weryfikacja, czy wszystkie importowane klasy istnieja")
    built = {os.path.relpath(os.path.join(dp, f), BUILD_OUT).replace(os.sep, "/")
             for dp, _, fs in os.walk(BUILD_OUT) for f in fs if f.endswith(".class")}
    imports = set()
    for f in glob.glob("src/com/**/*.java", recursive=True):
        for line in open(f):
            line = line.strip()
            if line.startswith("import com.craftingveloce."):
                t = line[len("import "):].rstrip(";").strip()
                if not t.endswith("*"):
                    imports.add(t)
    missing = []
    for t in imports:
        # Import a.b.C.D moze oznaczac:
        #   - klase a/b/C/D.class
        #   - klase zagniezdzona a/b/C$D.class  (albo glebiej: a/b/C$D$E)
        # Nie da sie tego rozstrzygnac bez parsowania zrodel, wiec probujemy
        # wszystkie podzialy: zamieniamy od konca kolejne kropki na '$'.
        parts = t.split(".")
        found = False
        # level = ile ostatnich segmentow traktujemy jako klasy zagniezdzone
        for level in range(1, len(parts)):
            pkg = parts[:len(parts) - level]
            nested = "$".join(parts[len(parts) - level:])
            cand = "/".join(pkg + [nested]) + ".class"
            if cand in built:
                found = True
                break
        # Wariant bez zagniezdzen: a/b/C/D.class
        if not found and t.replace(".", "/") + ".class" in built:
            found = True
        if not found:
            missing.append(t)

    if missing:
        fail("kod uzywa klas, ktorych nie ma w wyniku kompilacji:\n  "
             + "\n  ".join(sorted(missing)))
    print(f"    OK ({len(imports)} importow, wszystkie obecne)")

    # --- 3. pakowanie --------------------------------------------------
    step(3, "Pakowanie JAR")
    for sub in ("com", "assets", "data"):
        p = os.path.join(STAGING, sub)
        if os.path.exists(p):
            shutil.rmtree(p)
    shutil.copytree(os.path.join(BUILD_OUT, "com"), os.path.join(STAGING, "com"),
                    dirs_exist_ok=True)

    def copy_clean(src, dst):
        """Kopiuje drzewo pomijajac smieci systemowe."""
        shutil.copytree(src, dst, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns(*JUNK))

    copy_clean("assets", os.path.join(STAGING, "assets"))
    copy_clean("data", os.path.join(STAGING, "data"))

    # META-INF bierzemy z src_meta/, a NIE ze stagingu.
    #
    # Wczesniej neoforge.mods.toml istnial WYLACZNIE w katalogu staging
    # (craftingveloce_jar_root/) i nie byl wersjonowany. Wyczyszczenie stagingu
    # oznaczalo build bez metadanych moda - JAR, ktorego loader nie widzi.
    META_SRC = "src_meta"
    if os.path.exists(os.path.join(META_SRC, "META-INF")):
        copy_clean(os.path.join(META_SRC, "META-INF"),
                   os.path.join(STAGING, "META-INF"))

    if not os.path.exists(os.path.join(STAGING, "META-INF", "neoforge.mods.toml")):
        fail("brak META-INF/neoforge.mods.toml (zrodlo: src_meta/META-INF/)")

    if os.path.exists(JAR_NAME):
        os.remove(JAR_NAME)
    res = subprocess.run(["jar", "cf", JAR_NAME, "-C", STAGING, "."],
                         capture_output=True, text=True)
    if res.returncode != 0:
        fail("jar nie powstal: " + res.stderr[:400])

    # --- 4. kontrola jakosci -------------------------------------------
    step(4, "Kontrola jakosci JAR")
    z = zipfile.ZipFile(JAR_NAME)
    names = z.namelist()

    main_class = "com/craftingveloce/CraftingVeloceMod.class"
    if main_class not in names:
        fail(f"brak {main_class} - zly uklad pakietow (com/com?)")

    dupes = [n for n in names if n.startswith("com/com/")]
    if dupes:
        fail(f"zdublowany prefiks com/com ({len(dupes)} plikow)")

    if "META-INF/neoforge.mods.toml" not in names:
        fail("brak META-INF/neoforge.mods.toml")

    # Wyciek = klasa obcego moda w NASZYM JARze. Nasze wlasne klasy pod
    # com/craftingveloce/ moga miec w sciezce slowo "mekanism" (pakiet modulu
    # compat), wiec granica jest prefiks naszego pakietu, a nie sama nazwa.
    foreign = [n for n in names
               if not n.startswith("com/craftingveloce/")
               and any(p in n for p in FOREIGN_JAR_PATHS)]
    leaks = [n for n in names if "moze_intel" in n or "eatawesome" in n] + foreign
    if leaks:
        fail(f"wyciek obcych pakietow: {leaks[:5]}")

    junk = [n for n in names if any(j in n for j in JUNK)]
    if junk:
        fail(f"smieci systemowe w JARze: {junk}")

    # KAZDY zarejestrowany blok MUSI miec komplet danych.
    #
    # Bez loot table blok nie wypada po zniszczeniu (w creative tego nie widac,
    # wiec latwo przeoczyc), a brakiem modelu/blockstate blok jest niewidzialny.
    # Liste blokow bierzemy z TEGO SAMEGO miejsca co generator danych - patrz
    # scripts/gen_loot_tables.py - zeby nie powstal drugi, recznie pisany spis.
    validate_block_data(names)
    validate_packet_docs()
    validate_lang_keys()
    validate_filter_labels()
    validate_gui_layout()
    validate_helper_docs()
    validate_sensor_row()
    validate_node_blocks()
    validate_recipe_model()
    validate_module_block_ids()
    validate_core_isolation()
    validate_compat_gates(z)
    validate_jar_isolation(z)
    validate_isolation_runtime(cp, toms, rs)
    validate_create_kinetics()

    classes = sum(1 for n in names if n.endswith(".class"))
    print(f"    klas: {classes}, plikow: {len(names)}, "
          f"rozmiar: {os.path.getsize(JAR_NAME)} B")
    print("    OK")

    # --- 5. wdrozenie ---------------------------------------------------
    step(5, "Wdrozenie do profilu testing")

    # WDROZENIE MUSI BYC ATOMOWE.
    #
    # shutil.copyfile() otwiera plik docelowy i nadpisuje go W MIEJSCU. Gdy
    # Minecraft wlasnie dziala, jego classloader trzyma ten JAR otwarty i czyta
    # z niego klasy LENIWIE - po pierwszym uruchomieniu wiekszosc klas nie jest
    # jeszcze zaladowana. Nadpisanie pliku pod dzialajacym JVM konczy sie wiec:
    #
    #   Caused by: java.lang.ClassNotFoundException:
    #       com.craftingveloce.crafting.VeloceRecipeGraph
    #
    # i crashem serwera w losowym miejscu, wygladajacym na blad w kodzie.
    #
    # Zapis do pliku tymczasowego + os.replace() to pojedyncza operacja rename(2):
    # dzialajaca gra zostaje przy starym inode (wiec dziala dalej), a nowy JAR
    # pojawia sie dla nastepnego uruchomienia.
    tmp_deploy = DEPLOYED + ".tmp"
    shutil.copyfile(JAR_NAME, tmp_deploy)
    os.replace(tmp_deploy, DEPLOYED)
    print(f"    {DEPLOYED}")

    # --- 5b. ostrzezenie o dzialajacej grze -----------------------------
    if game_running():
        print()
        print("    " + "!" * 62)
        print("    Minecraft DZIALA. JAR zostal podmieniony bezpiecznie (atomowo),")
        print("    ale ta sesja nadal uzywa STAREJ wersji - jej classloader trzyma")
        print("    stary plik otwarty. ZAMKNIJ gre i uruchom ponownie, inaczej")
        print("    przetestujesz stary kod.")
        print("    " + "!" * 62)

    import hashlib

    def md5(p):
        return hashlib.md5(open(p, "rb").read()).hexdigest()

    if md5(JAR_NAME) != md5(DEPLOYED):
        fail("skopiowany JAR rozni sie od zbudowanego")
    print(f"    md5: {md5(JAR_NAME)}")
    print("\nGotowe. ZAMKNIJ Minecrafta przed uruchomieniem z nowym JARem.")


if __name__ == "__main__":
    main()
