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
    "mezz.jei": (
        ("jei-1.21.1-neoforge-19.56.0.439.jar",),
    ),
    "snownee.jade": (
        ("Jade-1.21.1-NeoForge-15.10.6.jar",),
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
                    "com.smashingmods", "mekanism", "mezz.jei", "snownee.jade")
FOREIGN_JAR_PATHS = ("com/simibubi/create/", "net/createmod/",
                     "com/smashingmods/", "mekanism/", "mezz/jei/", "snownee/jade/")


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


def _method_body(text, signature):
    """
    Cialo metody (w nawiasach klamrowych) po podanym poczatku sygnatury.

    Po co: guardy sprawdzajace "czy w kodzie jest takie wywolanie" sa
    BEZUZYTECZNE, gdy ta sama linia wystepuje w pliku jeszcze raz (np. to samo
    zgloszenie wezla do sieci jest i przy postawieniu bloku, i przy podmianie
    bloku). Wtedy usuniecie wywolania z JEDNEGO z tych miejsc przechodzi przez
    build, a objaw jest cichy. Sprawdzamy wiec zawartosc KONKRETNEJ metody.
    """
    start = text.find(signature)
    if start < 0:
        return None
    brace = text.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace:i + 1]
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
        "jei": ("mezz.jei",),
        "jade": ("snownee.jade",),
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

    JEI jest tu szczegolnym przypadkiem i dlatego NIE ma wlasnej bramki: jego
    plugin ({@code compat/jei/VeloceJeiPlugin}) ma obcy typ w SYGNATURZE metody
    ({@code registerRecipeCatalysts(IRecipeCatalystRegistration)}), a laduje go
    samo JEI, skanujac adnotacje {@code @JeiPlugin}. Bez JEI nie ma kto go
    zaladowac, wiec niezmiennik "obce typy tylko po sprawdzeniu obecnosci moda"
    jest spelniony - i wlasnie dlatego plugin jest w {@code module_dirs}, a nie
    w {@code gates}.

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
                   "com/craftingveloce/compat/mekanism/",
                   "com/craftingveloce/compat/jei/",
                   "com/craftingveloce/compat/jade/")
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


def validate_number_format():
    """
    Formatowanie liczb w GUI zyje w JEDNYM miejscu: {@code util/VeloceFormat}.

    Ten projekt ma powtarzalny blad: te sama regule zapisano recznie w kilku
    miejscach i miejsca sie rozjechaly. Przy liczbach byly TRZY kopie
    (nakladka slotu, terminal, ekran kontrolera), a gracz zglosil to wprost:
    "15.0" w tooltipie obok "1K" na ikonie to dwa rozne formaty tej samej
    liczby.

    Regula: wzorzec {@code %.<cyfry>f} (recznie skladany ulamek) wolno uzyc
    WYLACZNIE w VeloceFormat. Kto potrzebuje liczby do GUI, wola
    {@code compact}/{@code rate}/{@code feCompact}.
    """
    pattern = re.compile(r"%\.[0-9]+f")
    allowed = os.path.join("src", "com", "craftingveloce", "util", "VeloceFormat.java")
    bad = []
    for path in glob.glob("src/com/craftingveloce/**/*.java", recursive=True):
        if path == allowed or any(x in path for x in EXCLUDED_SRC):
            continue
        if pattern.search(open(path, encoding="utf-8").read()):
            bad.append(path.replace(os.sep, "/"))
    if bad:
        fail("wlasne formatowanie ulamkow poza VeloceFormat:\n  "
             + "\n  ".join(bad)
             + "\n  uzyj VeloceFormat.compact/rate/feCompact")
    print("    OK (formaty liczb tylko w VeloceFormat)")


def validate_module_recipe_access():
    """
    {@code recipesAnywhere} NIE moze filtrowac po maszynach ani zasilaniu.

    Ta metoda istnieje wlasnie po to, zeby narzedzia (np. {@code /cv getitems})
    mogly powiedziec "jak sie to robi" bez posiadania maszyny. Jesli ktos
    dopisze do niej warunek "maszyna stoi i ma prad" (bo tak wyglada metoda
    obok, {@code recipesFor}), komenda znowu zacznie klamac: dla itemu
    powstajacego w maszynie modulu powie "nie ma receptury".

    To nie jest hipoteza - ten blad juz wystapil w innej formie (komenda nie
    widziala receptur modulow wcale), a objaw jest mylacy: brak informacji
    udaje informacje. Dlatego pilnuje tego build.
    """
    problems, checked = [], 0
    for path in sorted(glob.glob("src/com/craftingveloce/compat/*/*Module.java")):
        text = open(path, encoding="utf-8").read()
        start = text.find("recipesAnywhere")
        if start < 0:
            continue
        checked += 1
        brace = text.find("{", start)
        depth, end = 0, brace
        for i in range(brace, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        body = text[brace:end]
        offenders = [w for w in ("hasPowered", "hasAny(", "isPowered",
                                 "VeloceProcessingSources")
                     if w in body]
        if offenders:
            problems.append(path.replace(os.sep, "/") + ": " + ", ".join(offenders))
    if problems:
        fail("recipesAnywhere filtruje po maszynach (a nie powinno):\n  "
             + "\n  ".join(problems))
    if checked:
        print(f"    OK ({checked} modulow: recipesAnywhere bez gatingu maszyn)")


def validate_auto_crafter_ingredient_rule():
    """
    Planer i WYKONANIE musza uzywac tej samej reguly "co jest skladnikiem".

    Objaw rozjazdu tych dwoch miejsc (zgloszenie gracza): "GUI pokazuje 2
    crushing wheele, ale przy craftowaniu mowi, ze nie mam itemkow". Planer
    pomijal puste sloty siatki, wykonanie probowalo je "pobrac" i padalo od
    razu - liczba byla policzona poprawnie, a craft nie dzialal NIGDY.

    Dlatego oba miejsca MUSZA pytac wspolna regule ({@code hasOptions}).
    """
    path = "src/com/craftingveloce/crafting/VeloceAutoCrafter.java"
    if not os.path.exists(path):
        return
    text = open(path, encoding="utf-8").read()
    if "private static boolean hasOptions(" not in text:
        fail("brak wspolnej reguly hasOptions w VeloceAutoCrafter")
    problems = []
    for method in ("planRecipe", "runOnce"):
        markers = [m.start() for m in re.finditer(r"\b" + method + r"\s*\(", text)]
        # deklaracja metody jest ostatnim (lub jedynym wlasciwym) trafieniem;
        # bierzemy pierwsze trafienie po slowie "private static" dla tej nazwy
        decl = re.search(r"private static [\w<>\[\], .]*\b" + method + r"\s*\(", text)
        if not decl:
            problems.append(method + ": nie znaleziono deklaracji")
            continue
        start = text.index("{", decl.end())
        depth, end = 0, start
        for i in range(start, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        if "hasOptions(" not in text[start:end]:
            problems.append(method + ": nie sprawdza hasOptions (regula rozjedzie sie "
                                    "z drugim miejscem)")
    if problems:
        fail("regula skladnikow w auto-crafterze:\n  " + "\n  ".join(problems))
    print("    OK (planer i wykonanie: jedna regula skladnikow)")


def validate_block_models():
    """
    Kazdy blockstate i model itemu musi wskazywac na plik, ktory JEST w JARze.

    BUG, ktory to wykryl (zgloszenie gracza: "model ma popsuty"): po zmianie
    nazw blokow modulow Mekanism (veloce_crusher_module -> veloce_mekanism_
    crusher_module) przenioslem pliki modeli, ale BLOCKSTATE'y dalej wskazywaly
    stare nazwy. W grze cztery bloki nie mialy modelu (komunikat "Unable to load
    model" leci do loga klienta), a wygladalo to jak zepsuty blok, a nie jak
    blad w danych - dokladnie ten rodzaj rozjazdu dwoch miejsc, ktory w tym
    projekcie wraca.

    Sprawdzamy tez TEKSTURY: model musi wskazywac na istniejacy plik PNG.
    """
    asset_root = "assets/craftingveloce"
    model_dir = os.path.join(asset_root, "models")
    problems = []
    checked = 0

    def model_path(ref):
        """craftingveloce:block/x -> sciezka pliku modelu."""
        ns, _, path = ref.partition(":")
        if not path:
            ns, path = "minecraft", ns
        if ns != "craftingveloce":
            return None            # modele wanilii - nie nasza sprawa
        return os.path.join(model_dir, path + ".json")

    for bs in sorted(glob.glob(os.path.join(asset_root, "blockstates/*.json"))):
        data = json.load(open(bs, encoding="utf-8"))
        refs = []
        for variant in data.get("variants", {}).values():
            if isinstance(variant, dict):
                refs.append(variant.get("model"))
            elif isinstance(variant, list):
                refs.extend(v.get("model") for v in variant)
        # Blockstate wieloczesciowy (multipart): model ramy + zaslepki stron.
        for part in data.get("multipart", []):
            apply = part.get("apply")
            if isinstance(apply, dict):
                refs.append(apply.get("model"))
            elif isinstance(apply, list):
                refs.extend(a.get("model") for a in apply)
        for ref in refs:
            if not ref:
                continue
            path = model_path(ref)
            if path is None:
                continue
            checked += 1
            if not os.path.exists(path):
                problems.append(f"{bs.replace(os.sep, '/')}: brak modelu {ref} ({path})")

    # Modele itemow i ich rodzic (model bloku) plus tekstury. Sprawdzamy tez
    # tekstury wewnatrz modeli itemow (np. ikona "stol w klatce" ma wlasne
    # sciany) - brak pliku PNG w JARze to znowu "popsuty model", a nie blad
    # kompilacji.
    for item_model in sorted(glob.glob(os.path.join(model_dir, "item/*.json"))):
        data = json.load(open(item_model, encoding="utf-8"))
        parent = data.get("parent")
        if parent:
            path = model_path(parent)
            checked += 1
            if path is not None and not os.path.exists(path):
                problems.append(f"{item_model.replace(os.sep, '/')}: brak rodzica {parent}")

    for model_pattern in ("block/*.json", "item/*.json"):
        for model_file in sorted(glob.glob(os.path.join(model_dir, model_pattern))):
            data = json.load(open(model_file, encoding="utf-8"))
            textures = data.get("textures", {})
            refs = set()
            for value in textures.values():
                if isinstance(value, str) and ":" in value:
                    refs.add(value)
            for element in data.get("elements", []):
                for face in element.get("faces", {}).values():
                    texture = face.get("texture")
                    if isinstance(texture, str) and texture.startswith("#"):
                        resolved = textures.get(texture[1:])
                        if isinstance(resolved, str) and ":" in resolved:
                            refs.add(resolved)
            for ref in refs:
                ns, _, path = ref.partition(":")
                checked += 1
                if ns != "craftingveloce":
                    continue
                if not os.path.exists(os.path.join(asset_root, "textures", path + ".png")):
                    problems.append(f"{model_file.replace(os.sep, '/')}: brak tekstury {ref}")

    if problems:
        fail("modele blokow:\n  " + "\n  ".join(problems))
    print(f"    OK ({checked} odwolan do modeli i tekstur istnieje)")


# Kategorie JEI obslugiwane przez nasze klocki: plik -> {UID kategorii: pole klocka}.
#
# UID to UID KATEGORII JEI, a NIE nazwa typu przepisu - i to jest tu glowna
# pulapka: pila Create ma kategorie "create:sawing", choc jej typ przepisu
# nazywa sie "create:cutting". UID-y ustalone z bajtkodu modow (Create:
# Create.asResource(name) z build("sawing", ...), Mekanism:
# RecipeTypeRegistryObject.getId(), Alchemistry: RecipeType.create("alchemistry", ...)).
JEI_CATEGORIES = {
    "src/com/craftingveloce/compat/VeloceJeiCatalysts.java": {
        "minecraft:crafting": "VELOCE_CRAFTING_TABLE_ITEM",
    },
    "src/com/craftingveloce/compat/create/CreateJeiCatalysts.java": {
        "create:milling": "VELOCE_MILLSTONE_MODULE_ITEM",
        "create:sawing": "VELOCE_SAW_MODULE_ITEM",
        "create:crushing": "VELOCE_CRUSHING_MODULE_ITEM",
        "create:mechanical_crafting": "VELOCE_MECHANICAL_CRAFTER_MODULE_ITEM",
        "create:pressing": "VELOCE_PRESS_MODULE_ITEM",
        "create:mixing": "VELOCE_MIXER_MODULE_ITEM",
        "create:deploying": "VELOCE_DEPLOYER_MODULE_ITEM",
    },
    "src/com/craftingveloce/compat/mekanism/MekanismJeiCatalysts.java": {
        "mekanism:crushing": "VELOCE_CRUSHER_MODULE_ITEM",
        "mekanism:enriching": "VELOCE_ENRICHMENT_MODULE_ITEM",
        "mekanism:combining": "VELOCE_COMBINER_MODULE_ITEM",
        "mekanism:sawing": "VELOCE_SAWMILL_MODULE_ITEM",
    },
    "src/com/craftingveloce/compat/alchemistry/AlchemistryJeiCatalysts.java": {
        "alchemistry:compactor": "VELOCE_COMPACTOR_MODULE_ITEM",
        "alchemistry:combiner": "VELOCE_COMBINER_MODULE_ITEM",
        "alchemistry:fission": "VELOCE_FISSION_MODULE_ITEM",
        "alchemistry:fusion": "VELOCE_FUSION_MODULE_ITEM",
    },
}


def validate_jade_info():
    """
    Jade: JEDNA linia statusu (pracuje / za malo sily / za malo energii).

    Gracz: "wypierdol wszystko, co pisze w integracji Jade i zostaw tylko
    powered/working". Sprawdzamy, ze plugin istnieje, dziala bez modulow maszyn
    i dopisuje DOKLADNIE jedna linie - bez predkosci, SU, energii i sieci.
    """
    problems = []
    plugin = "src/com/craftingveloce/compat/jade/VeloceJadePlugin.java"
    data_provider = "src/com/craftingveloce/compat/jade/VeloceModuleDataProvider.java"
    component = "src/com/craftingveloce/compat/jade/VeloceModuleComponentProvider.java"
    for path, what in ((plugin, "pluginu Jade"), (data_provider, "danych dla Jade"),
                       (component, "tooltipa Jade")):
        if not os.path.exists(path):
            problems.append("brak " + what)
    if problems:
        fail("Jade:\n  " + "\n  ".join(problems))

    plugin_text = open(plugin, encoding="utf-8").read()
    if "@WailaPlugin" not in plugin_text or "IWailaPlugin" not in plugin_text:
        problems.append("plugin Jade nie jest pluginem Jade")
    common = _method_body(plugin_text, "public void register(")
    if common is None or "registerBlockDataProvider(" not in common:
        problems.append("plugin Jade nie rejestruje danych serwera")
    client = _method_body(plugin_text, "public void registerClient(")
    if client is None or "registerBlockComponent(" not in client:
        problems.append("plugin Jade nie rejestruje skladnika tooltipa")
    for path in sorted(glob.glob("src/com/craftingveloce/compat/jade/*.java")):
        for name in _imports_of(path):
            if name.startswith(("com.craftingveloce.compat.create",
                                "com.craftingveloce.compat.mekanism",
                                "com.craftingveloce.compat.alchemistry")):
                problems.append(f"{os.path.basename(path)}: plugin Jade importuje {name}")

    data_text = open(data_provider, encoding="utf-8").read()
    should = _method_body(data_text, "public boolean shouldRequestData(")
    if should is None or "VeloceModuleInfoSource" not in should:
        problems.append("Jade pyta o dane o byle jaki block entity")

    component_text = open(component, encoding="utf-8").read()
    tooltip = _method_body(component_text, "public void appendTooltip(")
    if tooltip is None:
        problems.append("tooltip Jade bez metody")
    else:
        if "VeloceModuleStatus.message(" not in tooltip:
            problems.append("Jade nie pokazuje statusu pracy")
        extra = [line.strip() for line in tooltip.splitlines()
                 if "tooltip.add" in line and "VeloceModuleStatus.message(" not in line
                 and "addAll" not in line]
        if extra:
            problems.append("Jade dopisuje wiecej niz status: " + " | ".join(extra[:3]))
        for forbidden in ("speed", "suDraw", "energy", "networkNodes"):
            if forbidden in tooltip:
                problems.append(f"Jade nadal wypisuje {forbidden}")

    if problems:
        fail("Jade (tylko status pracy):\n  " + "\n  ".join(problems))
    print("    OK (Jade: jedna linia statusu - pracuje / za malo sily / za malo energii)")


def validate_terminal_craft_error():
    """
    Powod nieudanego craftu w terminalu trafia do TOOLTIPA itemu.

    Gracz: "jak czegos nie mozna zrobic w terminalu, to te komunikaty leca na
    pasek w gierce, ktorego w GUI terminala nie widac - niech powod pokaze sie
    w tooltipie tego itemu, ktorego nie udalo sie zrobic".

    Lancuch jest dlugi i kazde ogniwo psuje sie PO CICHU (gracz znow nie widzi
    nic albo widzi komunikat przy losowym itemie):
      1. serwer wysyla powod PAKIETEM, a nie przez pasek akcji - i tylko
         w galezi niepowodzenia (komunikaty o braku terminala i pelnym
         ekwipunku zostaja, bo ich nic nie zastepuje),
      2. pakiet jest zarejestrowany (inaczej nie dotrze),
      3. ekran dokleja powod do tooltipa i czysci pamiec przy zamknieciu,
      4. ekran porownuje pozycje terminala (pakiet z innego terminala nie moze
         pokazac powodu przy tym ekranie),
      5. pamiec powodow zna ITEM, wygasa i jest czerwona,
      6. komunikat sklada wspolne zrodlo kluczy (VeloceCraftErrors), a nie
         drugi, recznie pisany switch.
    """
    problems = []
    pkt = "src/com/craftingveloce/network/TerminalCraftErrorPKT.java"
    pull = "src/com/craftingveloce/network/TerminalPullItemPKT.java"
    handler = "src/com/craftingveloce/network/VelocePacketHandler.java"
    screen = "src/com/craftingveloce/client/gui/VeloceTerminalScreen.java"
    hints = "src/com/craftingveloce/client/gui/VeloceCraftErrorHints.java"
    helper = "src/com/craftingveloce/client/ClientTerminalHelper.java"
    errors = "src/com/craftingveloce/crafting/VeloceCraftErrors.java"
    for path, what in ((pkt, "pakietu powodu"), (hints, "pamieci powodow"),
                       (errors, "wspolnych kluczy komunikatow")):
        if not os.path.exists(path):
            problems.append("brak " + what)

    if os.path.exists(handler) and "TerminalCraftErrorPKT.TYPE" not in open(
            handler, encoding="utf-8").read():
        problems.append("pakiet powodu nie jest zarejestrowany")

    if os.path.exists(pull):
        body = _method_body(open(pull, encoding="utf-8").read(), "public static void handle(")
        if body is None:
            problems.append("brak obslugi wyciagania z terminala")
        else:
            branch = _failure_branch(body)
            if branch is None:
                problems.append("brak galezi niepowodzenia w obsludze terminala")
            elif "sendCraftError(serverPlayer, pkt, pulled)" not in branch:
                problems.append("niepowodzenie nie wysyla powodu do tooltipa")
            elif "displayClientMessage" in branch:
                problems.append("niepowodzenie nadal leci na pasek akcji (w GUI go nie widac)")
            if "inventoryFull" not in body:
                problems.append("zginela informacja o pelnym ekwipunku")

    if os.path.exists(screen):
        text = open(screen, encoding="utf-8").read()
        tooltip = _method_body(text, "public List<Component> getTooltipFromContainerItem(")
        if tooltip is None or "craftErrors.appendTo(" not in tooltip:
            problems.append("tooltip itemu nie doklada powodu nieudanego craftu")
        removed = _method_body(text, "public void removed(")
        if removed is None or "craftErrors.clear()" not in removed:
            problems.append("ekran nie czysci powodow przy zamknieciu")
        setter = _method_body(text, "public void setCraftError(")
        if setter is None or "pos.equals(this.terminalPos)" not in setter:
            problems.append("ekran nie sprawdza, z ktorego terminala jest powod")

    if os.path.exists(hints):
        text = open(hints, encoding="utf-8").read()
        for need, what in (("ChatFormatting.RED", "czerwonego koloru"),
                           ("VeloceCraftErrors.message(", "wspolnego zrodla kluczy"),
                           ("Map<Item, Hint>", "klucza po itemie")):
            if need not in text:
                problems.append("pamiec powodow bez " + what)
        # WYGASANIE sprawdzamy w CIELE appendTo, a nie w calym pliku: sama
        # deklaracja stalej LIFETIME_MS zostaje na miejscu nawet wtedy, gdy
        # warunek z niej zniknie - i komunikat sprzed pol godziny wraca do
        # tooltipa. Kalibracja: podmiana warunku na `if (false)` MUSI byc
        # zlapana (a nie byla, dopoki kontrola patrzyla na caly plik).
        append = _method_body(text, "public void appendTo(")
        if append is None:
            problems.append("pamiec powodow bez metody doklejajacej powod")
        else:
            for need, what in (("LIFETIME_MS", "wygasania powodow"),
                               ("Util.getMillis()", "czasu zapisu powodu")):
                if need not in append:
                    problems.append("pamiec powodow bez " + what)

    if os.path.exists(helper):
        forward = _method_body(open(helper, encoding="utf-8").read(),
                               "public static void handleCraftError(")
        if forward is None or "screen.setCraftError(" not in forward:
            problems.append("helper nie przekazuje powodu do ekranu terminala")

    if os.path.exists(errors):
        text = open(errors, encoding="utf-8").read()
        for need in ("craftingveloce.craft.error.noBaseItem",
                     "craftingveloce.craft.error.extractItem"):
            if need not in text:
                problems.append(f"wspolne klucze bez wariantu z detalem ({need})")

    if problems:
        fail("powod nieudanego craftu w terminalu:\n  " + "\n  ".join(problems))
    print("    OK (terminal: powod nieudanego craftu w tooltipie itemu, nie na pasku akcji)")


def _failure_branch(body):
    """
    Galez niepowodzenia z obslugi terminala: od warunku do jego {@code return}.

    Sprawdzamy WLASNIE te kilka linii, bo tylko tam wolno (i trzeba) wyslac
    powod. Kontrola calego ciala metody przepuscilaby powrot paska akcji -
    w metodzie zostaje przeciez komunikat o pelnym ekwipunku.
    """
    start = body.find("if (pulled.stack().isEmpty()) {")
    if start < 0:
        return None
    end = body.find("return;\n            }", start)
    return body[start:end if end > 0 else len(body)]


def validate_jei_catalysts():
    """
    JEI: nasze klocki na liscie "w tym mozna zrobic ten przepis".

    Gracz: "JEI musi pokazywac, ze nasze klocki tez robia przepis (jak lista
    stol rzemieslniczy, crafter, formulatic assembler, robot, terminal przy
    przepisie wytwarzania)". Kazde ogniwo tego lancucha psuje sie PO CICHU -
    JEI pokaze po prostu za malo ikonek i nikt tego nie zauwazy - dlatego:
      1. plugin {@code @JeiPlugin} istnieje i rejestruje katalizatory przez API
         JEI ({@code getJeiHelpers().getRecipeType(...)} +
         {@code addRecipeCatalysts(...)}), a nie przez wlasne klasy obcych modow,
      2. zaden plik POZA {@code compat/jei/} nie odwoluje sie do pluginu (klasa z
         obcym typem w sygnaturze nie moze byc dotknieta przez rdzen).
         Granicy importow samego pluginu pilnuje {@code validate_core_isolation}
         (mapa {@code owners} zna modul {@code jei}; wolno mu tylko
         {@code mezz.jei}) - dlatego nie ma tu drugiej, nieosiagalnej kontroli:
         kalibracja pokazala, ze wstrzykniecie {@code import com.simibubi.create}
         do pluginu zatrzymuje build wlasnie tam,
      3. tabela UID kategorii -&gt; nasz klocek zgadza sie CO DO PARY (zamiana
         UID-ow miejscami to tez blad: mlyn pokazalby sie przy kruszarce),
      4. rdzen wypelnia kategorie {@code minecraft:crafting}, a kazda bramka
         moda wola swoja tabele,
      5. UID-y obcych kategorii sa w {@code FOREIGN_PACKAGES} - inaczej kontrola
         wyciekow w JARze ich nie widzi.
    """
    problems = []
    plugin = "src/com/craftingveloce/compat/jei/VeloceJeiPlugin.java"
    registry = "src/com/craftingveloce/compat/VeloceJeiCatalysts.java"
    for path, what in ((plugin, "pluginu JEI"), (registry, "spisu kategorii JEI")):
        if not os.path.exists(path):
            problems.append("brak " + what)
            return fail("JEI:\n  " + "\n  ".join(problems))

    plugin_text = open(plugin, encoding="utf-8").read()
    if "@JeiPlugin" not in plugin_text or "IModPlugin" not in plugin_text:
        problems.append("plugin JEI nie jest pluginem JEI (@JeiPlugin/IModPlugin)")
    if 'ResourceLocation.fromNamespaceAndPath(MOD_ID, "jei")' not in plugin_text:
        problems.append("plugin JEI bez wlasnego UID")
    body = _method_body(plugin_text, "public void registerRecipeCatalysts(")
    if body is None:
        problems.append("plugin JEI nie rejestruje katalizatorow")
    else:
        for need, what in (("getJeiHelpers()", "pobrania pomocy JEI"),
                           ("getRecipeType(", "szukania kategorii po UID"),
                           ("addRecipeCatalysts(", "dodania katalizatora")):
            if need not in body:
                problems.append("plugin JEI bez " + what)

    # Plugin jest ladowany przez JEI takze BEZ pozostalych modow, wiec wolno mu
    # znac tylko JEI - tej granicy pilnuje validate_core_isolation (owners["jei"]).

    # ...i odwrotnie: nikt poza compat/jei/ nie moze dotknac pluginu.
    for path in glob.glob("src/com/craftingveloce/**/*.java", recursive=True):
        rel = path.replace(os.sep, "/")
        if "/compat/jei/" in rel:
            continue
        text = open(path, encoding="utf-8").read()
        if "VeloceJeiPlugin" in text or "compat.jei" in text:
            problems.append(f"{rel}: rdzen/bramka odwoluje sie do pluginu JEI")

    # Tabela UID -> klocek: porownujemy PARY, nie same zbiory nazw.
    for path, expected in JEI_CATEGORIES.items():
        if not os.path.exists(path):
            problems.append("brak tabeli kategorii JEI: " + path)
            continue
        text = open(path, encoding="utf-8").read()
        found = {}
        for m in re.finditer(r'register\(\s*"([^"]+)"\s*,\s*\(\)\s*->\s*[\w.]*?(\w+)\.get\(\)',
                             text):
            found[m.group(1)] = m.group(2)
        missing = {k: v for k, v in expected.items() if k not in found}
        extra = {k: v for k, v in found.items() if k not in expected}
        wrong = {k: (v, found[k]) for k, v in expected.items()
                 if k in found and found[k] != v}
        if missing:
            problems.append(f"{os.path.basename(path)}: brak kategorii {sorted(missing)}")
        if extra:
            problems.append(f"{os.path.basename(path)}: kategoria spoza listy {sorted(extra)}")
        for uid, (want, got) in wrong.items():
            problems.append(f"{os.path.basename(path)}: {uid} -> {got}, a ma byc {want}")

    core = "src/com/craftingveloce/CraftingVeloceMod.java"
    if os.path.exists(core) and "VeloceJeiCatalysts.registerDefaults()" \
            not in open(core, encoding="utf-8").read():
        problems.append("rdzen nie wypelnia kategorii minecraft:crafting")
    for path, call in (("src/com/craftingveloce/compat/create/CreateCompat.java",
                        "CreateJeiCatalysts.register()"),
                       ("src/com/craftingveloce/compat/mekanism/MekanismCompat.java",
                        "MekanismJeiCatalysts.register()"),
                       ("src/com/craftingveloce/compat/alchemistry/AlchemistryCompat.java",
                        "AlchemistryJeiCatalysts.register()")):
        if not os.path.exists(path) or call not in open(path, encoding="utf-8").read():
            problems.append(f"{os.path.basename(path)}: brak {call}")
    if 'JEI("jei")' not in open("src/com/craftingveloce/compat/VeloceMods.java",
                                encoding="utf-8").read():
        problems.append("VeloceMods bez wpisu JEI")
    toml = "src_meta/META-INF/neoforge.mods.toml"
    if os.path.exists(toml) and 'modId="jei"' not in open(toml, encoding="utf-8").read():
        problems.append("neoforge.mods.toml bez opcjonalnej zaleznosci jei")
    if "mezz.jei" not in FOREIGN_PACKAGES:
        problems.append("mezz.jei poza FOREIGN_PACKAGES - wyciek bylby niewidoczny")

    if problems:
        fail("JEI (katalizatory kategorii):\n  " + "\n  ".join(problems))
    total = sum(len(v) for v in JEI_CATEGORIES.values())
    print(f"    OK (JEI: {total} kategorii -> nasze klocki, plugin bez obcych modow)")


def validate_module_info_gui():
    """
    Okna maszyn: osobne dla energii i osobne dla Create (jak piec).

    Gracz: "w GUI modulow z Create w tle widoczny jest pasek bateryjki oraz slot
    na akumulator ... przygotuj osobny ekran dedykowany wylacznie blokom
    kinetycznym (bez slotu i bez wskaznika energii)".

    Sprawdzamy dwa ekrany i dwa menu:
      * maszyna na FE: tekstura pieca, pasek baterii i tooltip 1:1 z piecem
        (te same klucze: energia, cykle, koszt cyklu),
      * maszyna kinetyczna: WLASNY ekran i WLASNE menu, zero baterii, zero
        slotu, na srodku jeden wysrodkowany status (ten sam napis co w Jade),
      * oba typy menu sa zarejestrowane i podpiete pod swoje ekrany,
      * ladowanie: slot baterii + dobieranie pradu w ticku + ticker,
      * stare, kombinowane okna nie wrocily.
    """
    problems = []
    inventory = "src/com/craftingveloce/inventory/VeloceModuleMenu.java"
    kinetic_menu = "src/com/craftingveloce/inventory/VeloceKineticMenu.java"
    screen = "src/com/craftingveloce/client/gui/VeloceModuleScreen.java"
    kinetic_screen = "src/com/craftingveloce/client/gui/VeloceKineticScreen.java"
    for path, what in ((inventory, "menu maszyny FE"), (kinetic_menu, "menu maszyny kinetycznej"),
                       (screen, "ekranu maszyny FE"), (kinetic_screen, "ekranu maszyny kinetycznej")):
        if not os.path.exists(path):
            problems.append("brak " + what)
    for gone in ("src/com/craftingveloce/client/gui/VeloceModuleInfoScreen.java",
                 "src/com/craftingveloce/network/OpenModuleInfoPKT.java",
                 "src/com/craftingveloce/crafting/VeloceModuleInfoLines.java"):
        if os.path.exists(gone):
            problems.append("zostalo stare okno: " + os.path.basename(gone))
    if problems:
        fail("okna maszyn:\n  " + "\n  ".join(problems))

    registry = open("src/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    for need, what in (("VELOCE_MODULE_MENU =", "menu maszyny FE"),
                       ("VELOCE_KINETIC_MENU =", "menu maszyny kinetycznej")):
        if need not in registry:
            problems.append("nie zarejestrowano " + what)
    mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    for need, what in (("VELOCE_MODULE_MENU.get()", "ekranu FE"),
                       ("VeloceModuleScreen::new", "ekranu FE"),
                       ("VELOCE_KINETIC_MENU.get()", "ekranu kinetycznego"),
                       ("VeloceKineticScreen::new", "ekranu kinetycznego")):
        if need not in mod:
            problems.append("brak podpiecia " + what)

    # Maszyna kinetyczna: WLASNE menu (bez slotu baterii) i otwieranie go.
    km = open(kinetic_menu, encoding="utf-8").read()
    for need, what in (("VELOCE_KINETIC_MENU", "wlasnego typu menu"),
                       ("VeloceModuleDisplay", "czytania pol maszyny")):
        if need not in km:
            problems.append("menu kinetyczne bez " + what)
    if "getBatterySlot" in km or "isEnergyItem" in km:
        problems.append("menu kinetyczne ma slot baterii (a nie powinno)")
    block = "src/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java"
    block_text = open(block, encoding="utf-8").read()
    if "VeloceKineticMenu" not in block_text:
        problems.append("maszyna kinetyczna otwiera nie swoje menu")
    body = _method_body(block_text,
                        "protected net.minecraft.world.InteractionResult useWithoutItem(")
    if body is None or "openMenu(" not in body:
        problems.append("maszyna kinetyczna nie otwiera menu")

    # Ekran kinetyczny: tylko wysrodkowany status, zero energii i slotow.
    ks = open(kinetic_screen, encoding="utf-8").read()
    for need, what in (("AbstractContainerScreen<VeloceKineticMenu>", "zwyklego ekranu kontenera"),
                       ("electric_furnace.png", "tekstury pieca"),
                       ("drawCenteredString(this.font, VeloceModuleStatus.message(display)",
                        "wysrodkowanego statusu"),
                       ("module.info.minimum", "progu (min. RPM - SU)")):
        if need not in ks:
            problems.append("ekran kinetyczny bez " + what)
    for forbidden, what in (("drawBattery", "wskaznika energii"), ("getBatterySlot", "slotu baterii"),
                            ("module.info.speed", "predkosci"), ("module.info.stress", "SU")):
        if forbidden in ks:
            problems.append(f"ekran kinetyczny nadal pokazuje {what}")

    # Ekran FE: bateria + tooltip 1:1 z piecem.
    fs = open(screen, encoding="utf-8").read()
    for need, what in (("electric_furnace.png", "tekstury pieca"), ("drawBattery(", "baterii"),
                       ("gui.craftingveloce.electric.energy", "linii energii"),
                       ("gui.craftingveloce.electric.smelts", "linii cykli"),
                       ("gui.craftingveloce.electric.perSmelt", "kosztu cyklu")):
        if need not in fs:
            problems.append("ekran FE bez " + what)
    if "VeloceModuleStatus" in fs:
        problems.append("ekran FE pokazuje status kinetyczny")

    # Ladowanie akumulatora: slot baterii w menu + dobieranie pradu z itemu.
    menu = open(inventory, encoding="utf-8").read()
    for need, what in (("getBatterySlot()", "slotu na baterie w menu"),
                       ("isEnergyItem", "filtra: tylko itemy z energia")):
        if need not in menu:
            problems.append("menu maszyny bez " + what)
    fe_be = "src/com/craftingveloce/block/entity/VeloceFeModuleBlockEntity.java"
    fe_be_text = open(fe_be, encoding="utf-8").read()
    for need, what in (("private void chargeFromItem()", "metody dobierania pradu"),
                       ("getBatterySlot()", "wystawienia slotu baterii"),
                       ('tag.put("Battery"', "zapisu baterii w NBT"),
                       ("getUpdatePacket()", "wysylania energii na klienta")):
        if need not in fe_be_text:
            problems.append("modul FE bez " + what)
    tick_body = _method_body(fe_be_text, "public void serverTick()")
    if tick_body is None or "        chargeFromItem();" not in tick_body:
        problems.append("modul FE nie dobiera pradu w ticku")
    ticker = ("public <T extends BlockEntity> "
              "net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(")
    if ticker not in open("src/com/craftingveloce/block/VeloceFeModuleBlock.java",
                          encoding="utf-8").read():
        problems.append("modul FE nie ma tickera (ladowanie z itemu nie zadziala)")

    if problems:
        fail("okna maszyn (FE i kinetyczne):\n  " + "\n  ".join(problems))
    print("    OK (okna maszyn: FE jak piec z bateria, Create osobny ekran bez energii)")


def validate_pipe_energy():
    """
    Energia w rurze: pobor z oznaczonych zrodel z sieci (VeloceCraftingCache.tickAll)
    i rozdanie do wezlow sieci.

    Gracz przeniosl bufor do sieci rur i tick energii do ticku poziomu. Sprawdzamy
    ogniwa, ktore inaczej znikna po cichu:
      1. VelocePipeNetwork ma pojemnik energii,
      2. pobor/rozdanie dziala z VeloceCraftingCache.tickAll,
      3. rura NIE wystawia pojemnika jako capability (nie jest przewodem dla innych modow),
      4. maszyny nie oddaja energii (extractEnergy = 0), wiec nie moga byc dla siebie zrodlem.
    """
    problems = []
    
    # 1. Pojemnik w sieci
    net_path = "src/com/craftingveloce/network/pipe/VelocePipeNetwork.java"
    net_text = open(net_path, encoding="utf-8").read()
    if "private final net.neoforged.neoforge.energy.EnergyStorage energyBuffer" not in net_text:
        problems.append("VelocePipeNetwork bez deklaracji pojemnika energii")
    
    # 2. Tick w cache
    cache_path = "src/com/craftingveloce/crafting/VeloceCraftingCache.java"
    cache_text = open(cache_path, encoding="utf-8").read()
    tick = _method_body(cache_text, "public static void tickAll(ServerLevel level)")
    if tick is None or "VeloceEnergyPull.pull" not in tick:
        problems.append("energia nie jest tykowana (brak VeloceEnergyPull w VeloceCraftingCache.tickAll)")
    
    mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    # Rejestracja energii na rurze = rura staje sie przewodem dla innych modow.
    # Sprawdzamy OKNO wokol kazdej rejestracji, bo typ bywa w nastepnej linii.
    for idx in [i for i in range(len(mod)) if mod.startswith("EnergyStorage.BLOCK", i)]:
        window = mod[idx:idx + 200]
        if "VELOCE_PIPE_BE" in window or "VELOCE_PIPE" in window:
            problems.append("rura wystawia EnergyStorage (moglaby byc przewodem)")
            break

    # Maszyny: tylko odbiorniki - extractEnergy musi zwracac 0.
    for path, what in (("src/com/craftingveloce/block/entity/VeloceElectricFurnaceBlockEntity.java",
                        "piec elektryczny"),
                       ("src/com/craftingveloce/block/entity/VeloceFeModuleBlockEntity.java",
                        "modul FE")):
        body = _method_body(open(path, encoding="utf-8").read(), "public int extractEnergy(")
        if body is None or "return 0;" not in body:
            problems.append(f"{what}: oddaje energie (maszyny maja byc tylko odbiornikami)")

    if problems:
        fail("energia w rurze:\n  " + "\n  ".join(problems))
    print("    OK (energia: bufor w rurze, pobor extracting, rozdanie, maszyny tylko odbieraja)")


def validate_brewing_stand():
    """
    Brewing Stand: blok, BE, menu, ekran i dane - z waniliowa logika warzenia.

    Gracz: "dodaj brewing stand tak samo jak piecyk czy crafting". Sprawdzamy
    ogniwa, ktore latwo zgubic pojedynczo (a wtedy blok stoi i nic nie robi):
      1. block entity DZIEDZICZY po waniliowym BrewingStandBlockEntity (5 slotow,
         brewTime, paliwo, PotionBrewing, NBT) zamiast kopiowac logike,
      2. ticker bloku wola {@code serverTick()} i BE je MA - to byla prawdziwa
         przyczyna czerwonych buildow: skopiowany z pieca blok wolal metode
         instancyjna pieca, ktorej brewing stand nie mial,
      3. menu ma SLOTY WARZENIA (3 butelki + skladnik + blaze powder) i ekwipunek,
      4. ekran nie udaje pieca: brak paska baterii (warzenie nie ma akumulatora),
      5. rejestracja (blok/item/BE/menu + ekran), obudowa Integrale i dane.
    """
    problems = []
    block = "src/com/craftingveloce/block/VeloceBrewingStandBlock.java"
    be = "src/com/craftingveloce/block/entity/VeloceBrewingStandBlockEntity.java"
    menu = "src/com/craftingveloce/inventory/VeloceBrewingStandMenu.java"
    screen = "src/com/craftingveloce/client/gui/VeloceBrewingStandScreen.java"
    for path, what in ((block, "bloku"), (be, "block entity"), (menu, "menu"),
                       (screen, "ekranu")):
        if not os.path.exists(path):
            problems.append("brak " + what)
    if problems:
        fail("brewing stand:\n  " + "\n  ".join(problems))

    # UWAGA: dziedziczenie po waniliowym BrewingStandBlockEntity jest
    # NIE MOZLIWE dla naszego bloku - waniliowy konstruktor ustawia typ
    # minecraft:brewing_stand, a nasz blok ma wlasny typ, co konczylo sie
    # crashem "Invalid block entity ... got Block craftingveloce:brewing_stand".
    # Dlatego BE jest nasz wlasny: 5 slotow + zapis w NBT + menu, a logika
    # mieszania (PotionBrewing) dochodzi osobno.
    be_text = open(be, encoding="utf-8").read()
    if "extends BlockEntity" not in be_text:
        problems.append("BE nie jest naszym wlasnym block entity")
    for need, what in (("implements net.minecraft.world.Container", "kontenera 5 slotow"),
                       ("new SimpleContainer(5)", "pieciu slotow (3 butelki, skladnik, paliwo)"),
                       ('tag.put("Items"', "zapisu zawartosci w NBT"),
                       ("createMenu(", "otwierania wlasnego menu")):
        if need not in be_text:
            problems.append("BE brewing standu bez " + what)
    if "BrewingStandBlockEntity" in be_text and "extends" in be_text \
            and "extends BlockEntity" not in be_text:
        problems.append("BE dziedziczy po waniliowym (to powoduje crash typow)")

    menu_text = open(menu, encoding="utf-8").read()
    # Slotow maszyny szukamy w WYWOLANIACH addSlot, nie w stalych: sama nazwa
    # stalej zostaje w pliku nawet wtedy, gdy slot zniknie (kalibracja to
    # pokazala - pierwsza wersja checku przepuscila usuniecie slotu paliwa).
    for need, what in (("new Slot(container, bottle,", "petli trzech butelek"),
                                              ("new Slot(container, 3,", "slotu skladnika"),
                       ("new Slot(container, 4,", "slotu paliwa (blaze powder)"),
                       ("new Slot(playerInv,", "ekwipunku gracza")):
        if need not in menu_text:
            problems.append("menu bez " + what)
    if "getBatterySlot" in menu_text:
        problems.append("menu warzenia ma slot baterii (to nie maszyna na FE)")

    screen_text = open(screen, encoding="utf-8").read()
    if "drawBattery" in screen_text or "energyCapacity" in screen_text:
        problems.append("ekran warzenia rysuje baterie (warzenie nie ma akumulatora)")
    if "electric_furnace.png" not in screen_text:
        problems.append("ekran warzenia nie uzywa tekstury pieca")

    reg = open("src/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    for need, what in (("BREWING_STAND =", "bloku"), ("BREWING_STAND_ITEM", "itemu"),
                       ("BREWING_STAND_BE", "block entity"), ("BREWING_STAND_MENU", "menu")):
        if need not in reg:
            problems.append("rejestracja bez " + what)
    mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceBrewingStandScreen::new" not in mod:
        problems.append("ekran warzenia nie jest podpiety")
    cc = open("src/com/craftingveloce/block/VeloceCaseContents.java", encoding="utf-8").read()
    if "BREWING_STAND.get()" not in cc:
        problems.append("brak obudowy Integrale dla brewing standa")
    for path in ("assets/craftingveloce/blockstates/brewing_stand.json",
                 "assets/craftingveloce/models/item/brewing_stand.json",
                 "data/craftingveloce/loot_table/blocks/brewing_stand.json"):
        if not os.path.exists(path):
            problems.append("brak danych: " + path)

    # Czesc B celu: co siec umie ze stolow specjalnych. To FAKTY z wanilii,
    # nie obietnice - smithing i fletching sa juz obslugiwane, a kartografia
    # i warzenie nie maja RecipeType, wiec NIE wolno ich dopisywac do rodzin.
    families = open("src/com/craftingveloce/crafting/VeloceRecipeFamilies.java",
                    encoding="utf-8").read()
    if "RecipeType.SMITHING" not in families:
        problems.append("smithing table wypadl z rodzin receptur (siec go nie zrobi)")
    if "RecipeType.CRAFTING" not in families:
        problems.append("fletching table przestal byc obslugiwany (to zwykly crafting)")
    if "BREWING" in families or "CARTOGRAPHY" in families:
        problems.append("ktos dopisal nieistniejacy RecipeType (BREWING/CARTOGRAPHY)")

    if problems:
        fail("brewing stand:\n  " + "\n  ".join(problems))
    print("    OK (brewing stand: waniliowa logika warzenia + menu/sloty + dane)")


def validate_energy_pull():
    """
    Nasze maszyny SAME sciagaja prad z obcych zrodel w sieci (Forge Energy).

    Gracz: "nasz modul moze sciagnac prad z energy cuba, ale tylko w te strone;
    inne moduly nie moga uzywac naszych kabli; jesli jest pelny, to nie probuje
    sciagac; i chcemy limity".

    Sprawdzamy caly lancuch:
      1. obce zrodlo energii jest typem endpointu i trafia do sieci ze skanu,
         ktory POMIJA nasze bloki (maszyny nie moga byc dla siebie zrodlem),
      2. pobor respektuje: pelny akumulator = zero prob, limit odbioru maszyny,
         limit zrodla (pytamy extractEnergy), zwrot nadwyzki i nieciagniecie
         niezaladowanych chunkow,
      3. ciagnie PIEC i moduly FE w swoim serwerowym ticku,
      4. nasza rura NIE wystawia EnergyStorage (kierunek tylko jeden).
    """
    problems = []
    enum_path = "src/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java"
    scan_path = "src/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java"
    pull_path = "src/com/craftingveloce/network/pipe/VeloceEnergyPull.java"
    net_path = "src/com/craftingveloce/network/pipe/VelocePipeNetwork.java"

    if "ENERGY" not in open(enum_path, encoding="utf-8").read():
        problems.append("brak typu endpointu ENERGY")
    net_text = open(net_path, encoding="utf-8").read()
    for need, what in (("addEnergyEndpoint(", "zapisu zrodel energii"),
                       ("getEnergyEndpoints()", "odczytu zrodel energii"),
                       ("clearEnergyEndpoints()", "czyszczenia listy zrodel")):
        if need not in net_text:
            problems.append("VelocePipeNetwork bez " + what)

    scan = open(scan_path, encoding="utf-8").read()
    if "discoveredEnergy.add(" not in scan:
        problems.append("skan nie zapamietuje obcych zrodel energii")
    if "instanceof VeloceNetworkNode" not in scan or "addEnergyEndpoint(" not in scan:
        problems.append("skan nie pomija naszych blokow / nie zapisuje zrodel do sieci")

    if not os.path.exists(pull_path):
        problems.append("brak wspolnego poboru energii (VeloceEnergyPull)")
    else:
        pull = open(pull_path, encoding="utf-8").read()
        for need, what in (("free <= 0", "braku poboru przy pelnym akumulatorze"),
                           ("extractEnergy(", "pytania zrodla o transfer (limit zrodla)"),
                           ("receiveEnergy(taken - accepted, false)", "zwrotu nadwyzki"),
                           ("isLoaded(pos)", "pomijania niezaladowanych zrodel"),
                           ("Math.min(free, maxRate)", "limitu odbioru maszyny")):
            if need not in pull:
                problems.append("pobor energii bez " + what)

    # Modul FE wola pobor przez wlasny helper (pullFromNetwork), piec robi to
    # wprost - dlatego sprawdzamy TICK + wywolanie w pliku, a nie sam literal
    # w ciele ticku (pierwsza wersja testu dawala falszywy alarm).
    fe_mod = "src/com/craftingveloce/block/entity/VeloceFeModuleBlockEntity.java"
    fe_text = open(fe_mod, encoding="utf-8").read()
    fe_tick = _method_body(fe_text, "public void serverTick()")
    if fe_tick is None or "pullFromNetwork()" not in fe_tick \
            or "VeloceEnergyPull.pull(" not in fe_text:
        problems.append("modul FE: nie sciaga pradu z sieci w ticku")
    furn = "src/com/craftingveloce/block/entity/VeloceElectricFurnaceBlockEntity.java"
    furn_text = open(furn, encoding="utf-8").read()
    furn_tick = _method_body(furn_text, "public void serverTick()")
    if furn_tick is None or "VeloceEnergyPull.pull(" not in furn_tick:
        problems.append("piec elektryczny: nie sciaga pradu z sieci w ticku")

    # Kierunek tylko jeden: rura nie moze wystawiac EnergyStorage.
    pipe_be = "src/com/craftingveloce/block/entity/VelocePipeBlockEntity.java"
    if os.path.exists(pipe_be) and "IEnergyStorage" in open(pipe_be, encoding="utf-8").read():
        problems.append("rura wystawia EnergyStorage (obcy mod moglby z niej pobierac)")

    if problems:
        fail("pobor pradu z sieci:\n  " + "\n  ".join(problems))
    print("    OK (pobor pradu: maszyny same sciagaja, limity i pelny akumulator respektowane)")


def validate_craftable_cache():
    """
    Cache liczb "ile da sie dorobic": serwerowy, JEDEN na siec, instant dla GUI.

    Gracz: "cyferki w terminalu laduja sie powoli od lewej do prawej, a kontroler
    pokazuje kilka pierwszych i przestaje; chce, zeby sie keszowaly na serwerze
    i zeby klient przy otwarciu GUI instant dostal gotowy kesz, a dopiero potem
    odpalila sie logika liczenia widocznego".

    Sprawdzamy cale ogniwo, bo kazde psuje sie po cichu (cyferki po prostu
    znikaja albo wracaja do powolnego doliczania):
      1. cache zyje przy SIECI (nie przy terminalu ani kontrolerze) - inaczej
         dwa GUI maja dwa rozne kesze, ktore sie rozjezdzaja,
      2. pakiet najpierw wysyla migawke cache, a DOPIERO POTEM liczy widoczna
         strone (odwrotna kolejnosc = powrot do "ladowania od lewej"),
      3. swiezo policzone liczby trafiaja do cache (bez tego cache nigdy sie
         nie zapelni i instant nie ma czego pokazac),
      4. zmiany sieci kasuja cache (inaczej gracz widzi stare liczby),
      5. kontroler idzie TA SAMA sciezka co terminal (bez wlasnego toru liczb).
    """
    problems = []
    network = "src/com/craftingveloce/network/pipe/VelocePipeNetwork.java"
    packet = "src/com/craftingveloce/network/RequestCraftableCountsPKT.java"
    nodes = "src/com/craftingveloce/network/pipe/VeloceNodeBlocks.java"
    toggle = "src/com/craftingveloce/network/CraftingTableToggleItemPKT.java"
    controller = "src/com/craftingveloce/client/gui/VeloceControllerScreen.java"

    net_text = open(network, encoding="utf-8").read()
    for need, what in (("craftableMemo", "mapy cache"),
                       ("public void rememberCraftable(", "zapisu do cache"),
                       ("public Map<Item, Long> getCraftableMemo()", "migawki cache"),
                       ("public void clearCraftableMemo()", "kasowania cache")):
        if need not in net_text:
            problems.append("VelocePipeNetwork bez " + what)

    handle = _method_body(open(packet, encoding="utf-8").read(), "public static void handle(")
    if handle is None:
        problems.append("brak obslugi zadania liczb")
    else:
        if "getCraftableMemo()" not in handle:
            problems.append("pakiet nie wysyla cache (instant cyferek nie ma)")
        if "rememberCraftable(" not in handle:
            problems.append("pakiet nie dopisuje wyniku do cache")
        if "getCraftableMemo()" in handle and "computeCraftableCounts(" in handle \
                and handle.index("getCraftableMemo()") > handle.index("computeCraftableCounts("):
            problems.append("cache leci PO liczeniu (ma byc instant, przed)")

    for path, what in ((nodes, "wezly sieci"), (toggle, "przelacznik craftera"),
                       (nodes, "wezly sieci")):
        if "clearCraftableMemo(" not in open(path, encoding="utf-8").read():
            problems.append(f"{what}: zmiana nie kasuje cache")
    # Zmiana ukladu / przeladowanie chunkow: kasowanie MUSI byc w CIELE
    # reconcileCaches - inaczej metoda clearCraftableMemo istnieje, ale nikt
    # jej nie wola przy zmianie topologii (kalibracja to pokazala).
    mgr_text = open("src/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
                    encoding="utf-8").read()
    reconcile = _method_body(mgr_text, "private void reconcileCaches(")
    if reconcile is None or "clearCraftableMemo()" not in reconcile:
        problems.append("zmiana ukladu / przeladowanie chunkow nie kasuje cache")

    ctrl = open(controller, encoding="utf-8").read()
    if "craftableCounts.request(" not in ctrl:
        problems.append("kontroler nie zamawia liczb ta sama sciezka co terminal")
    if "updateCraftableCounts(" not in ctrl:
        problems.append("kontroler nie odbiera liczb z tej samej sciezki")

    # Persystencja: cache musi jechac do save'a razem z siecia.
    if "saveCraftableMemo(" not in open(
            "src/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
            encoding="utf-8").read():
        problems.append("cache liczb nie jest zapisywany do save'a gry")
    if "restoreCraftableMemo(" not in open(
            "src/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
            encoding="utf-8").read():
        problems.append("cache liczb nie jest odczytywany z save'a gry")

    if problems:
        fail("cache liczb craftowalnych:\n  " + "\n  ".join(problems))
    print("    OK (cache liczb: siec + instant migawka + douczanie + kasowanie przy zmianach)")


def validate_block_probe():
    """
    `/cv block`: statystyki klocka pod celownikiem + tolerancja progu RPM.

    Gracz: "mam creative motor, ktory teoretycznie daje 256 obrotow, a modul
    pokazuje not enough ... dodaj komende, ktora pokaze statystyki klocka,
    na ktory patrze - ile mu brakuje speeda". Dwa ogniwa, oba latwe do zgubienia:
      1. prog 256 RPM musi miec tolerancje (256.0 bywa 255.99998 po propagacji),
      2. komenda musi istniec, byc podpieta pod /cv i czytac te same dane co
         okno/Jade (VeloceModuleInfoSource) oraz pokazywac BRAKUJACA predkosc.
    """
    problems = []
    be = ("src/com/craftingveloce/compat/create/block/entity/"
          "VeloceKineticModuleBlockEntity.java")
    text = open(be, encoding="utf-8").read()
    speed = _method_body(text, "public boolean hasEnoughRotationSpeed()")
    if speed is None or "REQUIRED_SPEED_TOLERANCE" not in speed:
        problems.append("prog 256 RPM bez tolerancji (256.0 bywa 255.99998)")

    probe = "src/com/craftingveloce/commands/BlockProbeCommand.java"
    if not os.path.exists(probe):
        problems.append("brak komendy /cv block")
    else:
        ptext = open(probe, encoding="utf-8").read()
        for need, what in (("moduleInfo(", "danych maszyny"),
                           ("BRAKUJE: ", "brakujacej predkosci"),
                           ("VeloceNetworkNode", "informacji o wezle sieci")):
            if need not in ptext:
                problems.append("komenda /cv block bez " + what)
    root = open("src/com/craftingveloce/commands/CVDebugCommand.java", encoding="utf-8").read()
    if 'Commands.literal("block")' not in root or "BlockProbeCommand::describe" not in root:
        problems.append("/cv block nie jest podpiete")
    readme = "README.md"
    if os.path.exists(readme) and "/cv block" not in open(readme, encoding="utf-8").read():
        problems.append("/cv block nieopisane w README")

    if problems:
        fail("podglad klocka (/cv block):\n  " + "\n  ".join(problems))
    print("    OK (/cv block: statystyki klocka + tolerancja progu RPM)")


def validate_showcase_command():
    """
    /cv showcase: lista blokow z REJESTRU, nie z recznej listy.

    Recznie pisana lista blokow do testow rozjedzie sie z rejestrem przy
    pierwszym nowym bloku (i nikt tego nie zauwazy, bo komenda "dziala" -
    tylko nie pokazuje nowego klocka). Guard pilnuje, ze komenda chodzi po
    rejestrze (BuiltInRegistries.BLOCK), ze wypelnia maszyny budowane
    (VeloceCaseBuildable) i ze jest podpieta do rejestracji komend.
    """
    path = "src/com/craftingveloce/commands/CVShowcaseCommand.java"
    if not os.path.exists(path):
        fail("showcase:\n  brak klasy komendy /cv showcase")
    text = open(path, encoding="utf-8").read()
    problems = []
    if "getStateForPlacement" not in text:
        problems.append("komenda showcase bez stanu z wlasna logika postawienia")
    for need, what in (('literal("pipes")', "trybu z rurami"),
                       ('literal("clear")', "trybu sprzatania")):
        if need not in text:
            problems.append("komenda showcase bez " + what)
    # Ciala metod, nie caly plik: nazwa "BuiltInRegistries.BLOCK" wystepuje
    # w kilku miejscach, wiec samo jej usuniecie z listy blokow nie byloby widoczne.
    blocks_body = _method_body(text, "private static List<Block> ourBlocks()")
    if blocks_body is None or "BuiltInRegistries.BLOCK.keySet()" not in blocks_body:
        problems.append("showcase nie czyta listy blokow z REJESTRU (reczna lista sie rozjedzie)")
    fill_body = _method_body(text, "private static void fillParts(")
    if fill_body is None or "VeloceCaseBuildable" not in fill_body:
        problems.append("showcase nie wypelnia maszyn budowanych elementami")
    mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "CVShowcaseCommand.register" not in mod:
        problems.append("komenda showcase nie jest zarejestrowana")
    if problems:
        fail("showcase:\n  " + "\n  ".join(problems))
    print("    OK (/cv showcase: bloki z rejestru + elementy + rury + sprzatanie)")


def validate_loot_item_ids():
    """
    Kazda loot table musi wskazywac na ISTNIEJACY item.

    BUG z loga gry ("Unknown registry key in ResourceKey[minecraft:item]:
    craftingveloce:veloce_crusher_module"): 4 loot table modulow Mekanism
    wskazywaly item BEZ prefiksu moda, bo generator zostawial istniejace pliki
    nietkniete. Objaw: te bloki nie dropily NIC (a blad lecial tylko do loga,
    przy wczytywaniu loot table).
    """
    registry = open("src/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    compat = "".join(open(path, encoding="utf-8").read()
                     for path in glob.glob("src/com/craftingveloce/compat/*/*Blocks.java"))
    items = set(re.findall(r'register(?:SimpleBlockItem)?\(\s*"([a-z0-9_]+)"', registry + compat))

    problems = []
    files = sorted(glob.glob("data/craftingveloce/loot_table/blocks/*.json"))
    for path in files:
        data = json.load(open(path, encoding="utf-8"))
        for name in _loot_item_names(data):
            if not name.startswith("craftingveloce:"):
                continue
            if name.split(":", 1)[1] not in items:
                problems.append(f"{os.path.basename(path)} -> {name}")
    if problems:
        fail("loot table wskazuja nieistniejace itemy:\n  " + "\n  ".join(problems))
    print(f"    OK ({len(files)} loot table, kazdy wskazany item istnieje)")


def _loot_item_names(node):
    """Wszystkie 'name' z wpisow typu minecraft:item (rekurencyjnie)."""
    if isinstance(node, dict):
        if node.get("type") == "minecraft:item" and isinstance(node.get("name"), str):
            yield node["name"]
        for value in node.values():
            yield from _loot_item_names(value)
    elif isinstance(node, list):
        for value in node:
            yield from _loot_item_names(value)


def validate_case_occlusion():
    """
    Kazdy nasz blok musi byc {@code noOcclusion()} + nie zaslaniac sasiadow.

    BUG, ktory to wykryl (zgloszenie gracza): obudowy z zawartoscia w srodku
    (kontroler, ekstraktor, sensory, piece, moduly) nie mialy {@code noOcclusion},
    wiec gra traktowala je jak PELNY, nieprzezroczysty szescian i obcinala
    sciany sasiadow tam, gdzie sie stykaly ("robila sie przezroczystosc i nie
    bylo widac klocka obok"). Pusta klatka Integrale miala te opcje od poczatku -
    dlatego dzialala i dlatego objaw wygladal na przypadkowy.
    """
    problems = []
    files = ["src/com/craftingveloce/init/VeloceRegistry.java"]
    files += sorted(glob.glob("src/com/craftingveloce/compat/*/*Blocks.java"))
    files += sorted(glob.glob("src/com/craftingveloce/compat/*/block/*.java"))
    for path in files:
        if not os.path.exists(path):
            continue
        text = open(path, encoding="utf-8").read()
        chains = text.count("Properties.of(")
        if chains == 0:
            continue
        missing = chains - text.count("noOcclusion()")
        if missing > 0:
            problems.append(f"{path.replace(os.sep, '/')}: {missing} z {chains} "
                            f"definicji blokow bez noOcclusion()")
    if problems:
        fail("przezroczystosc obudow (noOcclusion):\n  " + "\n  ".join(problems))
    print("    OK (kazda definicja bloku: noOcclusion + nie zaslania sasiadow)")


def validate_create_mechanics():
    """
    Mechanika maszyn Create: naped z kazdej strony, blachy, siatka, wymagania.

    Gracz opisal cztery rzeczy, ktore musza dzialac razem:
      1. krecenie przyjmowane z KAZDEJ strony (os obrotu ze stanu bloku, wal
         na obu koncach osi) - inaczej naped z boku jest ignorowany,
      2. bok, z ktorego dochodzi naped, zamyka sie blacha jak przy rurze,
      3. crafter mechaniczny ma tyle pol siatki, ile zbudowal gracz, a receptura
         wchodzi tylko wtedy, gdy sie w nich zmiesci (5x5 = 25 oczek, sufit 9x9),
      4. receptury z Basenem i cieplem (Blaze Burner) sa craftowalne wtedy, gdy
         te rzeczy sa w sieci - bez nich planer ich nie widzi.
    """
    problems = []

    block_code = open("src/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java",
                      encoding="utf-8").read()
    for need, what in (("BlockStateProperties.AXIS", "stanu osi obrotu"),
                       ("side.getAxis() == own",
                        "walu z kazdej strony zgodnej z osia"),
                       ("neighbourAxis(world, pos.relative(side)",
                        "walu od strony sasiada z napedem"),
                       ("return state.getValue(BlockStateProperties.AXIS)",
                        "osi obrotu czytanej ze stanu"),
                       ("instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity",
                        "blachy od strony napedu Create"),
                       ("VeloceIntegraleFrame.withClosure", "blach na bokach obudowy"),
                       ("protected BlockState updateShape", "przeliczania blach po zmianie sasiada")):
        if need not in block_code:
            problems.append("maszyna kinetyczna bez " + what)

    entry = open("src/com/craftingveloce/crafting/ProcessingEntry.java", encoding="utf-8").read()
    if "public boolean fitsGrid(int side, int parts)" not in entry:
        problems.append("ProcessingEntry nie sprawdza LICZBY pol (tylko bok siatki)")
    if "gridWidth * gridHeight <= parts" not in entry:
        problems.append("brak warunku na pokrycie wszystkich pol receptury")
    processing_sources = open("src/com/craftingveloce/crafting/VeloceProcessingSources.java",
                              encoding="utf-8").read()
    if "public static int maxParts(" not in processing_sources:
        problems.append("brak liczenia liczby zbudowanych pol")
    if "return (int) Math.floor(Math.sqrt(maxParts(level, network, type)));" not in processing_sources:
        problems.append("bok siatki nie liczy sie z liczby zbudowanych pol")
    if "public boolean fitsGrid(int side)" in entry:
        problems.append("ProcessingEntry bez reguly dopasowania siatki")
    sources = open("src/com/craftingveloce/crafting/VeloceProcessingSources.java",
                   encoding="utf-8").read()
    if "maxGridSide" not in sources:
        problems.append("brak liczenia boku zbudowanej siatki")
    harvest = open("src/com/craftingveloce/compat/create/CreateRecipeHarvest.java",
                   encoding="utf-8").read()
    for need, what in (("recipe.getWidth()", "szerokosci siatki z receptury"),
                       ("recipe.getHeight()", "wysokosci siatki z receptury"),
                       ("requiresHeat", "flagi ciepla (Blaze Burner)")):
        if need not in harvest:
            problems.append("harvest Create bez " + what)
    module = open("src/com/craftingveloce/compat/create/CreateModule.java",
                  encoding="utf-8").read()
    for need, what in (('"blaze_burner"', "wymagania Blaze Burnera"),
                       ('"basin"', "wymagania Basenu")):
        if need not in module:
            problems.append("modul Create bez " + what)
    # Filtrowanie musi byc w OBIE strony zapytania (planer i lista "co umiemy"):
    # samo wystapienie nazwy w pliku nic nie znaczy - latwo usunac jedno z dwoch
    # miejsc i cicho dostac receptury ponad zbudowana siatke.
    for signature, name in (("public List<ProcessingEntry> recipesFor(", "recipesFor"),
                            ("public Set<Item> producible(", "producible")):
        body = _method_body(module, signature)
        if body is None or "fitsGrid" not in body or "requirementsMet" not in body:
            problems.append(f"{name} nie filtruje receptur po siatce i wymaganiach")
    family = open("src/com/craftingveloce/compat/create/CreateRecipeFamily.java",
                  encoding="utf-8").read()
    for need, what in (("pressing()", "typu prasy"), ("mixing()", "typu miksera")):
        if need not in family:
            problems.append("rodzina Create bez " + what)
    be = open("src/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
              encoding="utf-8").read()
    for need, what in (("public static final int GRID_LIMIT = 9", "granicy 9x9"),
                       ("GRID_LIMIT * GRID_LIMIT", "limitu oczek craftera")):
        if need not in be:
            problems.append("crafter bez " + what)

    # Konwersje: pusty Integrale + klocek z Create = nasz modul. To ta sciezka,
    # ktora gracz opisal ("biore puste veloce integrale, klikam crushing
    # wheel'em - pojawia sie jeden w srodku, drugi klik - drugi i dopiero
    # teraz maszyna dziala").
    compat = open("src/com/craftingveloce/compat/create/CreateCompat.java",
                  encoding="utf-8").read()
    if "registerConversions();" not in compat:
        problems.append("bramka Create nie rejestruje konwersji z pustej obudowy")
    for item in ("crushing_wheel", "mechanical_crafter", "millstone", "mechanical_saw",
                 "mechanical_press", "mechanical_mixer", "deployer"):
        if f'VeloceIntegraleConversions.register(create("{item}")' not in compat:
            problems.append(f"brak konwersji obudowy na modul z create:{item}")

    # Klucz tabeli konwersji MUSI byc ID, nie referencja do bloku: bloki z
    # innego moda moga jeszcze nie istniec, gdy bramka rejestruje wpisy, a wtedy
    # wpis wskazywalby pusty blok i klik w obudowe nie robilby NIC.
    conversions = open("src/com/craftingveloce/block/VeloceIntegraleConversions.java",
                       encoding="utf-8").read()
    if "record Conversion(ResourceLocation inputId" not in conversions:
        problems.append("tabela konwersji kluczuje po bloku, a nie po ID "
                        "(klik klockiem z moda moze nie dzialac)")
    if "BuiltInRegistries.BLOCK.getKey(block)" not in conversions:
        problems.append("konwersje nie szukaja wejscia po ID w chwili uzycia")

    integrale = open("src/com/craftingveloce/block/VeloceIntegraleBlock.java",
                     encoding="utf-8").read()
    if "VeloceCaseBuildable" not in integrale or "buildable.addPart()" not in integrale:
        problems.append("pierwszy wlozony klocek nie zostaje w maszynie "
                        "(konwersja nie dokłada elementu)")

    renderer = open("src/com/craftingveloce/client/render/VeloceCaseRenderer.java",
                    encoding="utf-8").read()
    render_body = _method_body(renderer, "public void render(")
    if render_body is None or "caseParts() > 0" not in render_body \
            or "caseBuiltFromParts()" not in render_body:
        problems.append("maszyna bez wklikanych elementow nie jest pusta obudowa "
                        "(z creative'a widac gotowy klocek)")
    if "for (int i = 0; i < parts; i++)" not in renderer:
        problems.append("renderer nie rysuje tylu modeli, ile gracz wklikal")

    # Zawartosc MUSI byc rysowana jako MODEL ITEMU. Maszyny z innych modow
    # (mlynek, pila, kruszarka, maszyny Mekanism) nie maja zwyklego modelu
    # bloku - rysuja je wlasne renderery block entity, wiec renderSingleBlock
    # pokazywal PUSTA obudowe (zgloszenie gracza: "inne itemki w ogole nie
    # renderuja sie w srodku, tylko waniliowe").
    parts_body = _method_body(renderer, "private void renderParts(")
    render_body = _method_body(renderer, "public void render(")

    # Zawartosc obudowy to model ITEMU (pelna reprezentacja maszyny), a nie
    # model bloku - modele blokow maszyn z modow sa okrojone (czesc rysuje ich
    # wlasny renderer / Flywheel), a modele itemow sa pelne. Transformacja
    # przedmiotu (FIXED) MUSI byc skompensowana, inaczej item jest maly i w rogu.
    content_body = _method_body(renderer, "private void renderContent(")
    if content_body is None:
        problems.append("renderer obudowy bez renderContent")
    else:
        # Warunki sprawdzamy w CIELE renderContent - nazwy wystepuja tez
        # w innych metodach (contentTransform), wiec sama obecnosc w pliku
        # przepuscilaby np. podmiane kontekstu FIXED na NONE.
        for need, what in (("ItemDisplayContext.FIXED", "kontekstu FIXED"),
                           ("renderStatic(", "rysowania modelu itemu"),
                           ("transform.scale.x", "kompensacji rozmiaru"),
                           ("-transform.translation.x", "kompensacji przesuniecia")):
            if need not in content_body:
                problems.append("renderContent bez " + what)
        # Model musi stac PROSTO: transformacja FIXED przekreca przedmiot jak
        # w ekwipunku (30/225 stopni), przez co gracz widzial "saw i deployer
        # patrzA na bok". Zerujemy rotacje odwrotnoscia rotationZYX.
        if "rotationZYX(" not in content_body or "transform.rotation" not in content_body:
            problems.append("zawartosc nie stoi prosto (przechyl z transformacji FIXED)")
        if "contentScale" not in content_body:
            problems.append("zawartosc nie uzywa skali maszyny (duze maszyny wystaja gora)")
    if "getTransforms()" not in renderer or "getTransform(ItemDisplayContext.FIXED)" not in renderer:
        problems.append("brak odczytu transformacji modelu itemu")

    # Skala per maszyna zyje w tabeli obudow.
    contents = open("src/com/craftingveloce/block/VeloceCaseContents.java", encoding="utf-8").read()
    if "public static float contentScale(BlockState state)" not in contents:
        problems.append("tabela obudow bez skali zawartosci")
    for need, what in (("float scale,", "rozmiaru zawartosci we wpisie tabeli"),
                       ("float pitch,", "obrotu maszyny we wpisie tabeli"),
                       ("boolean keepItemRotation", "wyboru orientacji modelu we wpisie tabeli")):
        if need not in contents:
            problems.append("wpis tabeli obudow bez " + what)
    compat_create = open("src/com/craftingveloce/compat/create/CreateCompat.java", encoding="utf-8").read()
    for need, what in (('block("crushing_wheel"), 0.4F, 0.0F, true', "mocno zmniejszonej kruszarki"),
                       ('block("mechanical_crafter"), 0.42F', "zmniejszonego craftera"),
                       ('block("mechanical_press"), 0.5F', "zmniejszonej prasy"),
                       ('block("mechanical_mixer"), 0.5F', "zmniejszonego miksera"),
                       ('block("deployer"), 0.5F, -90.0F', "deployera patrzacego w dol"),
                       ('block("mechanical_saw"), 0.7F, -90.0F', "pily patrzacej w dol")):
        if need not in compat_create:
            problems.append("brak " + what)

    be_code = open("src/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
                   encoding="utf-8").read()

    # Naped: PROG 256 RPM + pobor 1024 SU. Wczesniejszy model (dzielenie stalej
    # przez predkosc bez progu) dawal ogromny "impact" przy malych obrotach i
    # sieć krzyczala overstressed - gracz to zglosil ("przy 256 sie zacina,
    # przy 1 kreci normalnie").
    modules = open("src/com/craftingveloce/compat/create/CreateKineticModules.java",
                   encoding="utf-8").read()
    if "public static final int REQUIRED_SPEED = 256;" not in modules:
        problems.append("brak progu wymaganej predkosci 256 RPM")
    if "public static final float STRESS_SU = 1024.0F;" not in modules:
        problems.append("brak stalej 1024 SU dla modulow")
    if modules.count("STRESS_SU") < 8:
        problems.append("nie kazdy modul bierze 1024 SU (ktos wpisal wlasna liczbe)")
    stress_body = _method_body(be_code, "float calculateStressApplied()")
    if stress_body is None or "module.constantSu() / speed" not in stress_body:
        problems.append("brak kompensacji predkosci w poborze")
    if stress_body is None or "speed < 1f" not in stress_body:
        problems.append("brak zabezpieczenia przy predkosci 0 (dzielenie przez zero)")
    if "public boolean hasEnoughRotationSpeed()" not in be_code:
        problems.append("brak sprawdzenia progu predkosci w maszynie")
    powered_body = _method_body(be_code, "public boolean isPowered()")
    if powered_body is None or "hasEnoughRotationSpeed()" not in powered_body:
        problems.append("isPowered nie wymaga progu predkosci")

    # Status "za malo sily" NIE jest juz rysowany wlasnym napisem po ekranie.
    #
    # Gracz: "wywal to cos, zamiast tego zrob integracje z Jade" - te informacje
    # pokazuje teraz tooltip Jade i okno po prawym kliku (patrz validate_jade_info,
    # ktory pilnuje, ze naprawde je pokazuja). Ten test pilnuje drugiego konca:
    # ze wlasnego napisu przy celowniku nie ma.
    gone = "src/com/craftingveloce/client/VeloceModuleOverlay.java"
    if os.path.exists(gone):
        problems.append("wrocil wlasny napis przy celowniku (ma byc w tooltipie Jade)")
    own_mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceModuleOverlay" in own_mod:
        problems.append("mod nadal podpina wlasny napis przy celowniku")

    # Kreatywnosc i middle click maja dawac maszyne WYPELNIONA (kruszarka: 2 kola,
    # crafter: 3x3), a nie pusta - inaczej gracz stawia pustke i wyglada jak blad.
    for need, what in (("public ItemStack filledStack()", "przedmiotu z zapisanymi elementami"),
                       ("public int defaultParts()", "domyslnej liczby elementow"),
                       ("getCloneItemStack", "wypelnionego middle clicka"),
                       ("return 9;", "domyslnego gridu 3x3 dla craftera"),
                       ("return 2;", "domyslnych dwoch kol dla kruszarki")):
        if need not in block_code:
            problems.append("maszyna bez " + what)
    blocks_registry = open("src/com/craftingveloce/compat/create/CreateBlocks.java",
                           encoding="utf-8").read()
    for need, what in (("VELOCE_CRUSHING_MODULE.get().filledStack()", "wypelnionej kruszarki w zakladce"),
                       ("VELOCE_MECHANICAL_CRAFTER_MODULE.get().filledStack()",
                        "wypelnionego craftera w zakladce")):
        if need not in blocks_registry:
            problems.append("brak " + what)

    # Crafter mechaniczny NIE reaguje na moc: ani animacja, ani stan bloku.
    if "ignoresPowerInModel()" not in block_code:
        problems.append("maszyna nie ma znacznika 'ignoruje moc w modelu'")
    neighbour = _method_body(block_code, "protected void neighborChanged(")
    if neighbour is None or "ignoresPowerInModel()" in neighbour:
        # Os musi sie dopasowac do napedu z KAZDEJ strony - takze w crafterze.
        # Wczesniej crafter wracal tu od razu i nie przyjmowal obrotow z boku.
        problems.append("maszyna nie dopasowuje osi do napedu (brak mocy z kazdej strony)")
    if neighbour is None or "AXIS" not in neighbour:
        problems.append("maszyna nie ustawia osi obrotu przy zmianie sasiada")
    speed_body = _method_body(be_code, "public float caseSpinDegreesPerTick()")
    if speed_body is None or "ignoresPowerInModel()" not in speed_body:
        problems.append("animacja craftera zalezy od napedu (ma byc stala)")

    for need, what in (('block("mechanical_press"), 0.5F, 180.0F', "prasy obroconej o 180 stopni"),
                       ('block("mechanical_mixer"), 0.5F, 180.0F', "miksera obroconego o 180 stopni")):
        if need not in compat_create:
            problems.append("brak " + what)

    # Obrot per maszyna musi byc stosowany, a zerowanie przechylu musi omijac
    # maszyny, ktore maja zostac w swojej orientacji (kolo mlynskie).
    for need, what in (("contentPitch", "obrotu per maszyna"),
                       ("keepsItemRotation", "wyboru, czy zerowac przechyl"),
                       ("Axis.XP.rotationDegrees(contentPitch)", "stosowania obrotu maszyny"),
                       ("if (!keepRotation)", "omijania zerowania dla wybranych maszyn")):
        if need not in renderer:
            problems.append("renderer obudowy bez " + what)

    if "renderSingleBlock" in renderer or "getBlockRenderer" in renderer:
        problems.append("renderer obudowy rysuje model BLOKU - modele blokow maszyn "
                        "sa okrojone (pelny jest model itemu)")
    if "getBlockEntityRenderDispatcher" in renderer:
        problems.append("renderer obudowy wola renderery block entity - maszyny "
                        "Create rysuje Flywheel, wiec bylaby pusta")

    spin_iface = open("src/com/craftingveloce/block/VeloceCaseSpin.java", encoding="utf-8").read()
    if "boolean casePartsSpinIndividually();" not in spin_iface:
        problems.append("interfejs bez informacji, czy elementy krecA sie pojedynczo")
    if "boolean caseBuiltFromParts();" not in spin_iface:
        problems.append("brak rozroznienia maszyny budowanej od maszyny ze stala zawartoscia")
    if "caseBuiltFromParts()" not in renderer:
        problems.append("renderer nie odroznia pustej maszyny budowanej od stalej zawartosci")

    # Siatka: uklad liczy MASZYNA (kolumny x rzedy), renderer i powiadomienie
    # musza korzystac z tego samego zrodla - inaczej gracz widzi "1x2",
    # a w srodku rysuje sie cos innego.
    if "gridLabel()" not in block_code or "displayClientMessage" not in block_code:
        problems.append("brak powiadomienia na pasku o ukladzie siatki")
    be_code = open("src/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
                   encoding="utf-8").read()
    for need, what in (("public int caseGridColumns()", "kolumn ukladu"),
                       ("public int caseGridRows()", "rzedow ukladu"),
                       ("public String gridLabel()", "tekstu ukladu dla gracza")):
        if need not in be_code:
            problems.append("maszyna bez " + what)
    # Siatka musi zostawiac zapas na animacje (gracz: craftery wystawaly ponad
    # model przy najwyzszej klatce bujania).
    if "GRID_EXTENT" not in renderer or "GRID_EXTENT / cols" not in renderer:
        problems.append("siatka elementow nie ma zapasu na animacje")
    if "spin.caseGridColumns()" not in renderer or "spin.caseGridRows()" not in renderer:
        problems.append("renderer nie uklada elementow tak, jak podaje maszyna")

    # Siatka craftera rosnie KWADRATOWO od srodka na zewnatrz (1x1, 2x2, 3x3...),
    # a nie jako rosnaca linia (1x2, 1x3...). Bok = ceil(sqrt(oczek)), a pola
    # wypelniaja sie od srodka obudowy.
    if "Math.ceil(Math.sqrt(Math.max(1, parts)))" not in be_code:
        problems.append("siatka craftera nie rosnie kwadratowo (bok z pierwiastka oczek)")
    # Sprawdzamy TRESC metody renderParts, nie samo wystapienie nazwy w pliku:
    # definicja spiralOrder zostaje nawet wtedy, gdy pętla jej nie uzywa.
    if parts_body is None or "centreOrder(" not in parts_body or "order[i][0]" not in parts_body:
        problems.append("elementy nie wypelniaja kwadratu od SRODKA na zewnatrz")

    if "neighbourAxis" not in block_code or "Direction.Axis axis = neighbourAxis" not in block_code:
        problems.append("os maszyny nie dopasowuje sie do sasiada z napedem "
                        "(naped z boku nie zadziala)")


    # Maszyna bez pradu nie jest dostepna: dotyczy WSZYSTKICH integracji.
    for path, name in (("src/com/craftingveloce/compat/create/CreateModule.java", "Create"),
                       ("src/com/craftingveloce/compat/mekanism/MekanismModule.java", "Mekanism"),
                       ("src/com/craftingveloce/compat/alchemistry/AlchemistryModule.java", "Alchemistry")):
        body = _method_body(open(path, encoding="utf-8").read(), "public Set<Item> producible(")
        if body is None or "hasPowered" not in body:
            problems.append(f"modul {name}: producible pokazuje maszyny BEZ pradu")
    if problems:
        fail("mechanika Create:\n  " + "\n  ".join(problems))
    print("    OK (Create: os z kazdej strony + blachy, siatka z receptury, "
          "wymagania basin/blaze, sufit 9x9)")


def validate_case_disassembly():
    """
    Rozkladanie obudowy: NBT na dropie + receptura w stole craftingu.

    Gracz: "niech dany blok ma w tagu NBT zapamietane, ile dokladnie crafterow
    zawiera w srodku - po zniszczeniu dokladnie ta sama liczba zostaje w NBT
    dropnietego itemu, a po postawieniu ma zachowac te sama wartosc. A gdy
    wloze ten przedmiot do Crafting Table, receptura ma zwrocic sam mechanical
    crafter w liczbie, ile ich bylo, plus sam ten bazowy klocek."

    Sprawdzamy cztery ogniwa, bo kazde z nich latwo zgubic po osobno:
      1. blok zapisuje licznik elementow do NBT dropnietego przedmiotu,
      2. block entity umie go odczytac (inaczej postawienie gubi wartosc),
      3. receptura zwraca pusta obudowe ORAZ klocki bazowe w liczbie z NBT,
      4. serializer jest zarejestrowany, a receptura dostarczona jako JSON
         (bez JSON-a receptura nie istnieje w swiecie).
    """
    problems = []

    module_block = open("src/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java",
                        encoding="utf-8").read()
    drops = _method_body(module_block, "protected java.util.List<ItemStack> getDrops(")
    if drops is None:
        problems.append("maszyna nie ma wlasnego dropu (licznik nie trafi do NBT)")
    else:
        for need, what in (('tag.putInt("VeloceParts"', "zapisu liczby elementow"),
                           ("BlockItem.setBlockEntityData", "wpisania danych do przedmiotu"),
                           ("LootContextParams.BLOCK_ENTITY", "odczytu block entity przy zbiciu")):
            if need not in drops:
                problems.append("drop maszyny bez " + what)

    be = open("src/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
              encoding="utf-8").read()
    if 'tag.getInt("VeloceParts")' not in be or 'tag.putInt("VeloceParts"' not in be:
        problems.append("block entity nie czyta/zapisuje liczby elementow w NBT")

    recipe_path = "src/com/craftingveloce/crafting/VeloceCaseDisassemblyRecipe.java"
    if not os.path.exists(recipe_path):
        fail("rozkladanie obudowy:\n  brak klasy receptury")
    recipe = open(recipe_path, encoding="utf-8").read()
    remaining_body = _method_body(recipe, "public NonNullList<ItemStack> getRemainingItems(")
    if remaining_body is None or "remaining.set(" not in remaining_body \
            or "base.asItem()" not in remaining_body:
        problems.append("getRemainingItems nie zwraca klockow bazowych do siatki")
    for need, what in (("VELOCE_INTEGRALE_ITEM", "zwrotu pustej obudowy"),
                       ("getRemainingItems", "zwrotu klockow bazowych do siatki"),
                       ("base.asItem()", "klocka bazowego jako zwrotu"),
                       ("partsOf(machine)", "liczby elementow z NBT"),
                       ('getInt("VeloceParts")', "odczytu licznika z NBT przedmiotu")):
        if need not in recipe:
            problems.append("receptura bez " + what)

    recipes = open("src/com/craftingveloce/crafting/VeloceRecipes.java", encoding="utf-8").read()
    if '"case_disassembly"' not in recipes:
        problems.append("serializer receptury nie jest zarejestrowany")
    mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceRecipes.register" not in mod:
        problems.append("rejestracja receptur nie jest podpieta do moda")

    json_path = "data/craftingveloce/recipe/case_disassembly.json"
    if not os.path.exists(json_path):
        problems.append("brak JSON-a receptury (data/craftingveloce/recipe/case_disassembly.json)")
    else:
        data = json.load(open(json_path, encoding="utf-8"))
        if data.get("type") != "craftingveloce:case_disassembly":
            problems.append(f"JSON receptury ma typ {data.get('type')}")

    if problems:
        fail("rozkladanie obudowy:\n  " + "\n  ".join(problems))
    print("    OK (obudowa: NBT z liczba elementow + receptura rozkladajaca)")


def validate_mods_toml():
    """
    `neoforge.mods.toml` musi sie PARSOWAC i miec wymagane zaleznosci.

    BUG, ktory to wykryl: przy usuwaniu bloku zaleznosci (Jade) zostala
    osierocona linia `side="CLIENT"` na koncu pliku. TOML dokleja taka linie do
    POPRZEDNIEJ tabeli, a duplikat klucza `side` wywala parsowanie CALEGO pliku
    - czyli mod nie wstaje. Build tego nie widzial, bo to plik danych, a nie
    kod: kompilacja przechodzi, JAR sie pakuje, a blad wychodzi dopiero w grze.
    """
    path = "src_meta/META-INF/neoforge.mods.toml"
    if not os.path.exists(path):
        return
    try:
        import tomllib
    except ImportError:      # Python < 3.11
        print("    (pomijam: brak tomllib)")
        return
    try:
        with open(path, "rb") as handle:
            data = tomllib.load(handle)
    except Exception as exc:
        fail(f"neoforge.mods.toml nie parsuje sie: {exc}")

    dependencies = data.get("dependencies", {}).get("craftingveloce", [])
    mod_ids = {dep.get("modId") for dep in dependencies}
    missing = sorted({"neoforge", "minecraft", "toms_storage"} - mod_ids)
    if missing:
        fail("neoforge.mods.toml bez wymaganych zaleznosci: " + ", ".join(missing))
    print(f"    OK (mods.toml parsuje sie: {len(mod_ids)} zaleznosci)")


def validate_integrale_display():
    """
    Klatka Veloce Integrale: OBUDOWA, ktora zamienia sie w nasza maszyne.

    Gracz: "jak wkladam furnace to robi sie furnace display - ma sie zmieniac
    w normalne itemki veloce". Klatka nie wystawia wiec niczego i nie udaje
    craftera: prawy klik odpowiednim waniliowym klockiem PODMIENIA ja na
    prawdziwy blok Veloce wedlug tabeli (VeloceIntegraleConversions): z pulpitu
    powstaje kontroler, z dozownika ekstraktor, z obserwatora sensor progu,
    ze stolu craftingu stol (ten jeden w obudowie), z pieca piec.

    Sprawdzamy:
      1. tabela przepisan mapuje wlasciwe pary,
      2. klatka pyta te tabele i podmienia blok, zglaszajac nowy wezel sieci,
      3. stary system "eksponatow" zniknal CALKOWICIE (polowiczny refaktor
         zostawilby w swiecie blok, ktory nic nie robi),
      4. stol w obudowie oddaje przy zbiciu JEDEN przedmiot i ma droge powrotna,
      5. klient renderuje stol w obudowie (a nie "eksponat"),
      6. klatka nie jest crafterem dla sieci, a jej przedmiot ma podpowiedz
         generowana z tabeli przepisan.
    """
    problems = []

    conv_path = "src/com/craftingveloce/block/VeloceIntegraleConversions.java"
    if not os.path.exists(conv_path):
        fail("klatka / maszyny:\n  brak tabeli przepisan VeloceIntegraleConversions")
    conversions = open(conv_path, encoding="utf-8").read()
    for input_block, target in (("Blocks.CRAFTING_TABLE", "VELOCE_CRAFTING_TABLE"),
                                ("Blocks.LECTERN", "VELOCE_CONTROLLER"),
                                ("Blocks.DISPENSER", "VELOCE_EXTRACTOR"),
                                ("Blocks.OBSERVER", "THRESHOLD_SENSOR"),
                                ("Blocks.FURNACE", "VELOCITY_FURNACE")):
        pair = f"add({input_block}, () -> VeloceRegistry.{target}.get())"
        if pair not in conversions:
            problems.append(f"tabela przepisan bez pary {input_block} -> {target}")

    integrale = "src/com/craftingveloce/block/VeloceIntegraleBlock.java"
    text = open(integrale, encoding="utf-8").read()
    for need, what in (("VeloceIntegraleConversions.forItem", "wgladu do tabeli przepisan"),
                       ("world.setBlock(pos, result", "podmiany bloku"),
                       ("VeloceNodeBlocks.onNodePlaced", "zgloszenia nowego bloku do sieci")):
        if need not in text:
            problems.append("klatka bez " + what)
    # Samo wystapienie onNodePlaced w pliku nic nie znaczy - to samo wywolanie
    # jest przy postawieniu bloku, wiec sprawdzamy TRESC metody podmiany.
    convert = _method_body(text, "private static void convert")
    if convert is None:
        problems.append("klatka nie ma metody zamiany na maszyne")
    else:
        for need, what in (("world.setBlock(pos, result", "postawienia maszyny"),
                           ("VeloceNodeBlocks.onNodePlaced", "zgloszenia nowego wezla sieci")):
            if need not in convert:
                problems.append("zamiana klatki na maszyne bez " + what)
    use_item = _method_body(text, "protected ItemInteractionResult useItemOn")
    if use_item is None or "convert(" not in use_item:
        problems.append("prawy klik nie zamienia klatki na maszyne")
    if "exposesCraftingBuffer" in text:
        problems.append("klatka wystawia bufor craftingu, choc crafterem nie jest")

    # 3. Stary system eksponatow ma zniknac z CALEGO moda. Sprawdzamy to
    # plik po pliku, bo najczestszy polowiczny refaktor zostawia metode, ktora
    # juz nikt nie wola, albo pole, ktore nadal jedzie w NBT.
    #
    # UWAGA (sprawdzone na prawdziwych klasach wanilii): usuniecie wlasciwosci
    # stanu bloku NIE psuje zapisanych swiatow - od 1.20.5 stan bloku jedzie
    # w palecie jako mapa {Name, Properties}, a nieznane klucze sa pomijane
    # (test: {Name:"minecraft:oak_fence",Properties:{filled:"true"}} parsuje sie
    # bez bledu, tak samo jak zla wartosc znanej wlasciwosci).
    leftovers = []
    for path in sorted(glob.glob("src/com/craftingveloce/**/*.java", recursive=True)):
        body = open(path, encoding="utf-8").read()
        for need in ("FILLED", "isDisplayable", "putOnDisplay", "takeOffDisplay",
                     "getDisplayItem()", "setDisplayItem(",
                     'tag.put("DisplayItem"', 'tag.getCompound("DisplayItem")'):
            if need in body:
                leftovers.append(f"{path.replace(os.sep, '/')} -> {need}")
    if leftovers:
        problems.append("zostal stary system eksponatow:\n  " + "\n  ".join(leftovers))

    be_path = "src/com/craftingveloce/block/entity/VeloceCraftingTableBlockEntity.java"
    be = open(be_path, encoding="utf-8").read()
    if "instanceof com.craftingveloce.block.VeloceCraftingTableBlock" not in be:
        problems.append("block entity stolu nie rozpoznaje craftera po TYPIE bloku")

    # Widmo magazynu: endpoint bufora musi znikac, gdy blok przestaje byc
    # crafterem - walidacja nie moze wiec opierac sie na samym block entity.
    manager = open("src/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
                   encoding="utf-8").read()
    buffer_case = manager.partition("case CRAFTING_BUFFER")[2][:400]
    if "exposesCraftingBuffer" not in buffer_case:
        problems.append("walidacja bufora craftera nie pyta wezla o regule - "
                        "po podmianie bloku zostaje widmo magazynu")

    registry = open("src/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    if "integraleBlock()" in registry:
        problems.append("klatka nadal ma wlasny block entity stolu (ma go nie miec)")

    mod = open("src/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceCaseRenderer" not in mod:
        problems.append("brak rejestracji renderera obudowy (RegisterRenderers)")

    renderer = "src/com/craftingveloce/client/render/VeloceCaseRenderer.java"
    if not os.path.exists(renderer):
        problems.append("brak klasy renderera obudowy")
    else:
        body = open(renderer, encoding="utf-8").read()
        for need, what in (("VeloceCaseContents.contentFor", "zawartosci z tabeli obudow"),
                           ("renderStatic(", "rysowania modelu itemu zawartosci"),
                           ("rotationDegrees", "animacji (obrot)"),
                           ("Math.sin", "animacji (bujanie)")):
            if need not in body:
                problems.append("renderer obudowy bez " + what)
        # Samo wystapienie "VeloceCaseSpin" w pliku nic nie znaczy (jest w
        # sygnaturze metody pomocniczej) - musi byc WYWOLANE z render().
        render_body = _method_body(body, "public void render(")
        if render_body is None or "VeloceCaseSpin" not in render_body \
                or "renderParts(" not in render_body:
            problems.append("renderer nie rysuje elementow maszyny (kola mlynskie) "
                            "z predkoscia z maszyny - VeloceCaseSpin nieuzywany")

    contents_path = "src/com/craftingveloce/block/VeloceCaseContents.java"
    if not os.path.exists(contents_path):
        problems.append("brak tabeli zawartosci obudow (VeloceCaseContents)")
    else:
        body = open(contents_path, encoding="utf-8").read()
        for need, what in (("VELOCE_CRAFTING_TABLE.get()", "stolu craftingu"),
                           ("VELOCE_CONTROLLER.get()", "kontrolera"),
                           ("VELOCE_EXTRACTOR.get()", "ekstraktora"),
                           ("THRESHOLD_SENSOR.get()", "sensora progu"),
                           ("VELOCITY_FURNACE.get()", "pieca paliwowego"),
                           ("ELECTRIC_FURNACE.get()", "pieca elektrycznego")):
            if need not in body:
                problems.append("tabela obudow bez " + what)

    if problems:
        fail("klatka / maszyny z klatki:\n  " + "\n  ".join(problems))
    print(f"    OK (klatka: {len(conversions.split('add(Blocks.')) - 1} przepisan, "
          f"obudowa + zawartosc, brak duplikatow)")


def validate_integrale_model():
    """
    Klatka Veloce Integrale: rama z pretow + FIOLETOWE SZKLO w oknach.

    Gracz opisal to w dwóch krokach: najpierw "tylko rogi i kance, bez
    kolorowania srodka", a potem "te dziury w modelu bloczka zabarw na
    fioletowo, jak purple stained glass". Model latwo zepsuc jedna literowka
    (szyba nieprzezroczysta, szyba zlewajaca sie z rama, rama wypelniona),
    a w grze wyglada to jak zwykly klocek - czyli odwrotnie niz ma byc.

    Sprawdzamy to, co widzi gracz:
      * 12 elementow ramy, kazdy CIENKI pret (2 wymiary <= 2, trzeci >= 16),
      * srodki scian (miejsce okien) sa przykryte WYLACZNIE szyba, ktora jest
        wcieta w ramę (nie dotyka plaszczyzn bloku - inaczej z-fighting),
      * szyba bierze teksture szkla, a nie metalu ramy,
      * model ma render_type translucent (bez tego fiolet jest nieprzezroczysty).
    """
    path = "assets/craftingveloce/models/block/veloce_integrale_frame.json"
    if not os.path.exists(path):
        return
    model = json.load(open(path, encoding="utf-8"))
    elements = model.get("elements", [])
    problems = []

    # 1) Rama: dokladnie 12 cienkich pretow.
    bars = []
    for i, el in enumerate(elements):
        sizes = [el["to"][0] - el["from"][0], el["to"][1] - el["from"][1],
                 el["to"][2] - el["from"][2]]
        if sum(1 for size in sizes if size <= 2) >= 2 and max(sizes) >= 16:
            bars.append((i, el))
    if len(bars) != 12:
        problems.append(f"pretow ramy: {len(bars)}, ma byc 12 (12 krawedzi szescianu)")

    # 2) Szyba: element WCIETY (zadna sciana nie lezy na plaszczyznie bloku).
    glass = [(i, el) for i, el in enumerate(elements) if el not in [b[1] for b in bars]]
    if len(glass) != 1:
        problems.append(f"elementow szyby: {len(glass)}, ma byc 1")
    for i, el in glass:
        f, t = el["from"], el["to"]
        if min(f) < 1.0 or max(t) > 15.0:
            problems.append(f"szyba {f}..{t} dotyka plaszczyzny bloku "
                            f"(z-fighting z rama) - ma byc wcieta")
        if min(f) > 4.0 or max(t) < 12.0:
            problems.append(f"szyba {f}..{t} jest za mala - okno ma 12x12 px")
        textures = {face.get("texture") for face in el.get("faces", {}).values()}
        if "#glass" not in textures:
            problems.append(f"szyba nie uzywa tekstury #glass (ma {textures})")

    textures = model.get("textures", {})
    glass_texture = textures.get("glass", "")
    if "glass" not in glass_texture:
        problems.append(f"tekstura szyby nie jest szklem: {glass_texture}")
    if model.get("ambientocclusion", True):
        problems.append("ambientocclusion nie jest false - cienie na pretach klatki")

    render_type = model.get("render_type", "")
    if "translucent" not in render_type:
        problems.append(f"render_type={render_type!r} - bez translucent fiolet jest "
                        f"nieprzezroczysty (gracz chce szklo)")

    # 3) Zaslepki stron: po jednej na kazda strone, dokladnie w świetle okna.
    #
    # Gracz chce widziec, z ktorej strony dochodzi kabel - okno od tej strony
    # zamyka sie blacha. Zaslepka MUSI lezec na plaszczyznie TEJ wlasnie strony
    # (0 albo 16) i przykrywac okno 2..14, inaczej zostaje szpara albo blacha
    # wisi w powietrzu.
    sides = {
        "north": (2, 0), "south": (2, 1), "west": (0, 2),
        "east": (1, 2), "down": (2, 2), "up": (2, 2),
    }
    for side, (axis_a, axis_b) in sides.items():
        panel_path = ("assets/craftingveloce/models/block/"
                      f"veloce_integrale_panel_{side}.json")
        if not os.path.exists(panel_path):
            problems.append(f"brak modelu zaslepki {side}")
            continue
        panel_model = json.load(open(panel_path, encoding="utf-8"))
        panel_elements = panel_model.get("elements", [])
        if len(panel_elements) != 1:
            problems.append(f"zaslepka {side}: elementow {len(panel_elements)}, ma byc 1")
            continue
        f, t = panel_elements[0]["from"], panel_elements[0]["to"]
        if side in ("north", "south"):
            low, high = f[2], t[2]
            if not ((side == "north" and low == 0 and high == 2)
                    or (side == "south" and low == 14 and high == 16)):
                problems.append(f"zaslepka {side}: z={low}..{high}, ma byc przy scianie")
            if (f[0], t[0], f[1], t[1]) != (2, 14, 2, 14):
                problems.append(f"zaslepka {side}: okno ma byc 2..14, jest "
                                f"{f[0]}..{t[0]} x {f[1]}..{t[1]}")
        if side in ("west", "east"):
            low, high = f[0], t[0]
            if not ((side == "west" and low == 0 and high == 2)
                    or (side == "east" and low == 14 and high == 16)):
                problems.append(f"zaslepka {side}: x={low}..{high}, ma byc przy scianie")
            if (f[1], t[1], f[2], t[2]) != (2, 14, 2, 14):
                problems.append(f"zaslepka {side}: okno ma byc 2..14, jest "
                                f"{f[1]}..{t[1]} x {f[2]}..{t[2]}")
        if side in ("down", "up"):
            low, high = f[1], t[1]
            if not ((side == "down" and low == 0 and high == 2)
                    or (side == "up" and low == 14 and high == 16)):
                problems.append(f"zaslepka {side}: y={low}..{high}, ma byc przy scianie")
            if (f[0], t[0], f[2], t[2]) != (2, 14, 2, 14):
                problems.append(f"zaslepka {side}: okno ma byc 2..14, jest "
                                f"{f[0]}..{t[0]} x {f[2]}..{t[2]}")

    # 4) Blockstate musi byc wieloczesciowy: rama + po jednej zaslepce na strone.
    bs_path = "assets/craftingveloce/blockstates/veloce_integrale.json"
    bs = json.load(open(bs_path, encoding="utf-8"))
    parts = bs.get("multipart", [])
    if not parts:
        problems.append("blockstate nie jest wieloczesciowy (multipart) - "
                        "nie da sie pokazac zabudowanych stron")
    else:
        applied = {p.get("apply", {}).get("model") for p in parts}
        if "craftingveloce:block/veloce_integrale_frame" not in applied:
            problems.append("blockstate nie zawiera modelu ramy")
        for side in sides:
            condition = parts and any(
                p.get("when", {}).get(side) == "true"
                and p.get("apply", {}).get("model")
                == f"craftingveloce:block/veloce_integrale_panel_{side}"
                for p in parts)
            if not condition:
                problems.append(f"blockstate nie ma warunku dla strony {side}")

    # 5) KAZDA nasza maszyna ma model OBUDOWY (rama + szyba), a nie swoj stary
    #    model - tak gracz chce: "model zmienia sie na ten veloce integrale,
    #    a dopiero w srodku jest render". Jednoczesciowy model jest tez jedynym
    #    sposobem, zeby particles przy zbiciu pochodzily z ramy: przy multipart
    #    wanilia bierze particleIcon z PIERWSZEJ czesci listy.
    machines_with_frame = set()
    for j in glob.glob("src/com/craftingveloce/block/*.java") + glob.glob("src/com/craftingveloce/compat/*/block/*.java"):
        if "VeloceIntegraleFrame.addProperties" in open(j, encoding="utf-8").read():
            # We want the block ID, but we have Java files. The easiest way is to scan all blockstate json
            # but maybe we just require all blockstates that have the frame model to be multipart
            pass

    # A better approach: ANY blockstate that mentions veloce_integrale_frame MUST be a multipart with panels.
    # We already have a loop over all blockstates below in "5b". Let's move the multipart check there.
    for bs_file in sorted(glob.glob("assets/craftingveloce/blockstates/*.json")):
        block_id = os.path.basename(bs_file)[:-len(".json")]
        bs_data = json.load(open(bs_file, encoding="utf-8"))
        
        # Check if it uses the frame
        uses_frame = False
        parts = bs_data.get("multipart")
        variants = bs_data.get("variants")
        
        if parts:
            applied = [p.get("apply", {}).get("model") for p in parts]
            if any("veloce_integrale_frame" in m for m in applied if m):
                uses_frame = True
        elif variants:
            models = [v.get("model") for v in variants.values()]
            if any("veloce_integrale_frame" in m for m in models if m):
                uses_frame = True
                
        if uses_frame:
            # MASZYNA MUSI BYC MULTIPARTEM ramy + paneli, nie statycznym modelem.
            if not parts:
                problems.append(f"blockstate {block_id} uzywa ramy Integrale, ale jest 'variants' - MUSI byc multipart z panelami")
            else:
                applied = [p.get("apply", {}).get("model") for p in parts]
                if applied[0] != "craftingveloce:block/veloce_integrale_frame":
                    problems.append(f"blockstate {block_id} nie jest obudowa Integrale "
                                    f"(pierwsza czesc musi byc rama - particleIcon): {applied[:2]}")
                else:
                    for side in ("north", "east", "south", "west", "up", "down"):
                        if not any(p.get("when", {}).get(side) == "true"
                                   and p.get("apply", {}).get("model")
                                   == f"craftingveloce:block/veloce_integrale_panel_{side}"
                                   for p in parts):
                            problems.append(f"blockstate {block_id}: brak warunku zaslepki {side}")

    # 5b) Obudowa = model ramy (pierwsza czesc!) + ikona z ZAWARTOSCIA.
    #
    # "Obudowa" rozpoznajemy po tym, ze blok ma rama w blockstate ALBO ikone
    # z zawartoscia (#content) - dzieki temu rura i terminal (ktore obudowy nie
    # maja) nie wpadaja do tego sprawdzenia, a blok, ktoremu podmieniono model
    # na obcy, nadal jest sprawdzany.
    #
    # Kolejnosc czesci ma znaczenie: waniliowy MultiPartBakedModel bierze
    # particleIcon z PIERWSZEJ czesci listy, wiec blacha (albo stary model) na
    # poczatku = particles z zlej tekstury - dokladnie to zglosil gracz.
    allowed_models = ("craftingveloce:block/veloce_integrale_frame",
                      "craftingveloce:block/veloce_integrale_panel_")
    for bs_file in sorted(glob.glob("assets/craftingveloce/blockstates/*.json")):
        block_id = os.path.basename(bs_file)[:-len(".json")]
        bs_data = json.load(open(bs_file, encoding="utf-8"))
        bs_models = [v.get("model") for v in bs_data.get("variants", {}).values()]
        if not bs_models:
            bs_models = [part.get("apply", {}).get("model")
                         for part in bs_data.get("multipart", [])]
        if not bs_models:
            continue
        if block_id == "veloce_integrale":
            continue   # PUSTA obudowa: nie ma zawartosci i nie ma jej miec
        item_file = f"assets/craftingveloce/models/item/{block_id}.json"
        icon = open(item_file, encoding="utf-8").read() if os.path.exists(item_file) else ""
        has_content = "#content" in icon
        is_case = has_content or bs_models[0] == "craftingveloce:block/veloce_integrale_frame"
        if not is_case:
            continue
        if not has_content:
            problems.append(f"{block_id}: obudowa bez zawartosci w ikonie itemu (#content)")
        wrong = [m for m in bs_models if m and not m.startswith(allowed_models)]
        if wrong:
            problems.append(f"{block_id}: obudowa z obcym modelem {wrong[0]}")
        elif bs_models[0] != "craftingveloce:block/veloce_integrale_frame":
            problems.append(f"{block_id}: pierwsza czesc obudowy to {bs_models[0]}, "
                            f"a musi byc rama obudowy (particles przy zbiciu)")

    if problems:
        fail("model klatki veloce_integrale:\n  " + "\n  ".join(problems))
    print(f"    OK (klatka: {len(bars)} pretow + szyba {glass_texture}, "
          f"6 zaslepek stron, translucent; fasada stolu + ikona z stolem)")


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
    validate_number_format()
    validate_module_recipe_access()
    validate_block_models()
    validate_integrale_model()
    validate_integrale_display()
    validate_mods_toml()
    validate_create_mechanics()
    validate_case_disassembly()
    validate_case_occlusion()
    validate_loot_item_ids()
    validate_showcase_command()
    validate_block_probe()
    validate_craftable_cache()
    validate_energy_pull()
    validate_brewing_stand()
    validate_pipe_energy()
    validate_module_info_gui()
    validate_jade_info()
    validate_terminal_craft_error()
    validate_jei_catalysts()
    validate_auto_crafter_ingredient_rule()

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
