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

# Pakiety obcego moda - nigdy nie moga trafic do naszego JARa.
EXCLUDED_SRC = ("eatawesome", "moze_intel")

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
    names = ["FILTER_X", "FILTER_Y", "FUEL_X", "FUEL_Y", "PLAYER_X", "PLAYER_Y",
             "FLAME_X", "FLAME_Y",
             "BATTERY_X", "BATTERY_Y", "BATTERY_W", "BATTERY_H", "NUB_W", "NUB_H",
             "BATTERY_SLOT_X", "BATTERY_SLOT_Y"]
    # Domyslnie porownujemy trojke pieca PALIWOWEGO: menu, ekran, generator.
    # Pozostale elementy maja wlasne listy nizej.
    who = {n: ["menu", "ekran", "generator"] for n in names}
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

    shutil.rmtree(BUILD_OUT, ignore_errors=True)
    os.makedirs(BUILD_OUT, exist_ok=True)
    res = subprocess.run(
        ["javac", "--release", "21", "-nowarn",
         "-cp", f"{cp}:{toms}:{rs}", "-d", BUILD_OUT] + sources,
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

    leaks = [n for n in names if "moze_intel" in n or "eatawesome" in n]
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
    validate_gui_layout()

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
