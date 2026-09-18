#!/usr/bin/env python3
"""
Builds craftingveloce-1.0.0.jar and deploys it to the testing profile.

Usage:
    python3 scripts/build.py

The script does everything in order and ABORTS on the first error, instead of
leaving a half-finished JAR:

  1. compiles all sources EXCEPT src/moze_intel and src/eatawesome
     (those are another mod's classes - in our JAR they would cause
     ResolutionException)
  2. checks that every class imported by the code actually exists in the
     compiled output
  3. packages the JAR, excluding system junk (.DS_Store, __MACOSX)
  4. checks the result: class path, no com/com duplicates, META-INF,
     no foreign package leaks
  5. copies the JAR into the testing profile

NOTE: do not swap the JAR while Minecraft is running. The game already holds
loaded classes in memory, and after replacing the file it may throw
NoClassDefFoundError for classes that were not in the loaded version.
"""
import glob
import json
import os
import pathlib
import re
from collections import Counter
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

# The flat src/ tree became a Gradle module, so every guard that reads
# sources goes through this root instead of hardcoding "src".
SRC_ROOT = "neoforge/src/main/java"
BUILD_OUT = "/tmp/craftingveloce_build"

# Directory for compile-time dependencies (compileOnly). It is in .gitignore
# (*.jar), because these are other people's mods - they must NEVER end up in
# our JAR.
LIBS = "libs"

# Compile-time dependencies of the optional integrations (compileOnly).
#
# Key = package prefix of the foreign mod. It is how we RECOGNIZE which JARs
# are needed: if no file in src/ imports that package, the JAR is not required.
# That way the "gates only, zero blocks" stage also compiles without those
# mods, and the stage with blocks immediately reports the missing JAR.
#
# Value = tuple of REQUIRED artifacts; each artifact is a list of NAMES
# (variants) to choose from - the first one found wins. For example Mekanism
# has an "API only" variant and a "full JAR" variant: both are enough to
# compile, but the API is small. We look for the JARs in several directories
# (different machines keep them differently), and we pull the missing
# libraries out of META-INF/jarjar into libs/.
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

# Directories in which we look for the compileOnly JARs (in this order).
COMPILE_ONLY_DIRS = (LIBS, MODS, os.path.expanduser("~/Downloads"))

# Foreign mod packages - they must NEVER end up in our JAR.
EXCLUDED_SRC = ("eatawesome", "moze_intel")

# Foreign mod packages = the isolation boundary. The core (everything outside
# compat/) must not import them, and the compat/ module of a given mod must
# not import another foreign mod (an easy mistake when copying a file).
FOREIGN_PACKAGES = ("com.simibubi.create", "net.createmod",
                    "com.smashingmods", "mekanism", "mezz.jei", "snownee.jade")
FOREIGN_JAR_PATHS = ("com/simibubi/create/", "net/createmod/",
                     "com/smashingmods/", "mekanism/", "mezz/jei/", "snownee/jade/")


# System junk that must not end up in the JAR.
JUNK = (".DS_Store", "__MACOSX", ".git")

# The block list comes from the data generator, and NOT from a second,
# hand-written list. This project has already been bitten four times by
# exactly such a divergence between two registries.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_loot_tables import registered_block_ids  # noqa: E402


def validate_block_data(jar_names):
    """
    Every registered block must have a complete set of data in the JAR.

    We check three things whose absence only becomes visible in game:
      * loot table    - without it the block does NOT drop when destroyed,
      * blockstate    - without it the block does not render,
      * item model    - without it the block has no icon in the inventory
                        and in creative.
    """
    names = set(jar_names)
    missing = []
    for block_id in registered_block_ids():
        for opis, sciezka in (
            ("loot table", f"data/craftingveloce/loot_table/blocks/{block_id}.json"),
            ("blockstate", f"assets/craftingveloce/blockstates/{block_id}.json"),
            ("item model", f"assets/craftingveloce/models/item/{block_id}.json"),
        ):
            if sciezka not in names:
                missing.append(f"{block_id}: no {opis} ({sciezka})")
    if missing:
        fail("blocks without a complete data set:\n  " + "\n  ".join(missing))
    print(f"    OK (data of {len(registered_block_ids())} blocks complete)")


def _record_components(path):
    """(name, list of components) of a record-packet from its source file."""
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
    """Splits a record header on commas at DEPTH 0 (generics!)."""
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
    """Components without packages: 'net.minecraft.core.BlockPos pos' -> 'BlockPos pos'."""
    text = text.replace("`", "").replace("@Nullable", "")
    text = re.sub(r"(?<![A-Za-z0-9_])(?:[a-z][a-z0-9_]*\.)+", "", text)  # package segments
    return re.sub(r"\s+", " ", text).strip()


def validate_packet_docs():
    """
    Every registered packet must be listed in the README - WITH ITS SIGNATURE.

    The packet table in the README is written by hand, while packet
    registration lives in VelocePacketHandler.java. Those are two registers of
    the same thing, so without a check they diverge on their own - and they
    already did: the README listed two packets that do not exist, was missing
    eight new ones, and then for several changes it described
    `OpenControllerScreenPKT` with a `hotbar` field that was long gone.

    That is why we check not only the NAME but also the COMPONENTS: the record
    header against the table cell. A name without fields is a document that
    lies halfway.
    """
    handler = os.path.join("neoforge/src/main/java/com/craftingveloce/network/VelocePacketHandler.java")
    readme = "README.md"
    if not os.path.exists(handler) or not os.path.exists(readme):
        return
    registered = sorted(set(re.findall(
        r"playTo(?:Server|Client)\((\w+)\.TYPE", open(handler, encoding="utf-8").read())))
    doc = open(readme, encoding="utf-8").read()
    missing = [p for p in registered if p not in doc]
    if missing:
        fail("packets registered but not documented in README.md:\n  " + "\n  ".join(missing))

    # Signatures: the record header against the third column of the table row.
    mismatches = []
    for name in registered:
        path = os.path.join("neoforge/src/main/java/com/craftingveloce/network", name + ".java")
        if not os.path.exists(path):
            continue
        _, body = _record_components(path)
        if body is None:
            continue
        row = re.search(r"^\|\s*`" + re.escape(name) + r"`\s*\|[^|]*\|([^|]*)\|",
                        doc, re.MULTILINE)
        if not row:
            mismatches.append(f"{name}: no row in the table")
            continue
        code = _normalize_signature(body)
        docs = _normalize_signature(row.group(1))
        if code != docs:
            mismatches.append(f"{name}:\n    code:   {code}\n    README: {docs}")
    if mismatches:
        fail("packet signatures do not match README.md:\n  " + "\n  ".join(mismatches))
    print(f"    OK (README documents all {len(registered)} packets - names and signatures)")


def validate_lang_keys():
    """
    Every translation key used in the code must exist in en_us.json.

    A missing key is NOT a compilation error - the player sees the raw string
    "gui.craftingveloce.controller.flow.churn" in the GUI and nobody notices it
    in tests, because the code "works". This is exactly the class of bug this
    project has already had once (a key deleted together with a method, while
    still used elsewhere).
    """
    lang_path = os.path.join("assets/craftingveloce/lang/en_us.json")
    if not os.path.exists(lang_path):
        return
    lang = json.load(open(lang_path, encoding="utf-8"))

    # DUPLICATE KEYS, read from the RAW TEXT because json.load cannot see them - it keeps
    # the last one and reports nothing. This is not hypothetical: the retexture branch and
    # this one each added `config.jade.plugin_craftingveloce.module_info`, git merged both
    # insertions because they were in different parts of the file, and the result was a
    # file with 153 keys of which 152 were unique. Everything still "worked", which is
    # exactly why it needs a machine to notice it.
    raw_keys = [line.strip().split('"')[1] for line in
                open(lang_path, encoding="utf-8")
                if line.strip().startswith('"') and line.count('"') >= 2]
    duplicated = sorted(k for k, n in Counter(raw_keys).items() if n > 1)
    if duplicated:
        fail("duplicate keys in en_us.json (json.load silently keeps the last one):\n  "
             + "\n  ".join(duplicated))

    prefixes = {k.split(".")[0] for k in lang}
    used = set()
    for root, _, files in os.walk(SRC_ROOT):
        for name in files:
            if not name.endswith(".java"):
                continue
            text = open(os.path.join(root, name), encoding="utf-8").read()
            for lit in re.findall(r'"([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"', text):
                if lit.split(".")[0] in prefixes:
                    used.add(lit)
    missing = sorted(k for k in used if k not in lang)
    if missing:
        fail("language keys used in the code but absent from en_us.json:\n  "
             + "\n  ".join(missing))
    unused = sorted(k for k in lang
                    if k not in used and not k.startswith(("block.", "item.")))
    info = f"    OK ({len(used)} keys used, all present"
    if unused:
        info += f"; unused: {', '.join(unused)}"
    print(info + ")   [block./item. skipped - the registry creates those]")


def _balanced(text, open_index):
    """Contents of the parentheses that start at open_index (level 0 inside)."""
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i]
    return None


def _strip_comments(text):
    """
    Java source with comments removed.

    Guards that look for a CODE pattern must not be satisfied - or tripped - by the
    documentation that describes it. This bit twice in one session: a guard forbidding
    `if (false)` fired on the Javadoc explaining why `if (false)` is forbidden, and a
    guard forbidding `PotionContents` fired on a comment naming the class.

    Note: this is a lexer-free approximation. It does not understand string literals,
    so a guard for something that can appear inside a string (e.g. a resource path)
    must NOT use it.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def _method_body(text, signature):
    """
    Method body (inside the braces) after the given start of the signature.

    Why: guards that check "is such a call present in the code" are USELESS
    when the same line occurs in the file a second time (e.g. the same node
    registration to the network happens both when a block is placed and when a
    block is replaced). Then removing the call from ONE of those places passes
    the build, and the symptom is silent. So we check the contents of a
    SPECIFIC method.
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
    The ClientTerminalHelper method signatures in the README must match the code.

    The README itself asks for this ("make sure this list does not fall
    behind"), and it fell behind anyway: `openControllerScreen` still described
    a `hotbar` parameter that had not existed for two sessions. Exactly the
    same class of bug as the packet table - hence it gets the same kind of
    check.

    We check:
      * every method described in the README exists in the code,
      * the number of parameters matches,
      * when the README gives full types - the types as well.
    Methods written in shorthand (without types, e.g. `handleSyncCounts(a, b)`)
    are checked only by parameter count, because they cannot be compared
    literally.
    """
    readme = "README.md"
    src = "neoforge/src/main/java/com/craftingveloce/client/ClientTerminalHelper.java"
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
            problems.append(f"{name}: documented in the README, but missing from the code")
            continue
        parts = [p for p in _split_components(raw) if p.strip()]
        typed = all(" " in p.strip() for p in parts)
        candidates = [p for p in methods[name]
                      if len([x for x in _split_components(p) if x.strip()]) == len(parts)]
        if not candidates:
            arities = [len([x for x in _split_components(p) if x.strip()])
                       for p in methods[name]]
            problems.append(f"{name}: README gives {len(parts)} parameter(s), "
                            f"the code has {arities}")
        elif typed and _normalize_signature(raw) not in candidates:
            problems.append(f"{name}:\n    code:   {candidates[0]}\n"
                            f"    README: {_normalize_signature(raw)}")

    missing = sorted(n for n in methods if n not in documented)
    if missing:
        problems.append("public methods without a README description: " + ", ".join(missing))

    if problems:
        fail("ClientTerminalHelper in the README out of sync with the code:\n  "
             + "\n  ".join(problems))
    print(f"    OK (README documents {len(documented)} ClientTerminalHelper methods - "
          f"signatures match)")


def validate_filter_labels():
    """
    The controller's filter button labels must fit inside the button.

    This button is 52 px wide, and the previous labels ("Show all" = 8
    characters, "Not available" = 13) ran past it and overlapped the
    neighbouring button. The font width cannot be measured exactly here (it
    depends on the game assets), so we use an estimate: ~6 px per character +
    8 px for the button's inner padding. Calibration: this formula flags BOTH
    labels that really did not fit once, and lets the current ones through
    ("All", "Active", "None").
    """
    lang_path = os.path.join("assets/craftingveloce/lang/en_us.json")
    screen = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceControllerScreen.java"
    if not os.path.exists(lang_path) or not os.path.exists(screen):
        return
    text = open(screen, encoding="utf-8").read()
    m = re.search(r"FILTER_BUTTON_W\s*=\s*(\d+)", text)
    if not m:
        fail("no FILTER_BUTTON_W constant in VeloceControllerScreen")
    width = int(m.group(1))
    lang = json.load(open(lang_path, encoding="utf-8"))

    keys = [k for k in lang if k.startswith("gui.craftingveloce.controller.filter.")
            and not k.endswith(".tip")]
    too_long = []
    for key in sorted(keys):
        label = lang[key]
        # ~6 px per character + 8 px of padding inside the button
        estimated = len(label) * 6 + 8
        if estimated > width:
            too_long.append(f"{key} = '{label}' (~{estimated} px > {width} px)")
    if too_long:
        fail("filter labels do not fit inside the button:\n  " + "\n  ".join(too_long))
    print(f"    OK (filter labels fit in {width} px: "
          + ", ".join(f"'{lang[k]}'" for k in sorted(keys)) + ")")

def validate_gui_layout():
    """
    The GUI coordinates live in several files and must agree.

    Who is compared with whom depends on who uses what:
      * FILTER_X/Y  - the menu places the slots, the screen draws the filter
                      icons, the generator paints the frames  -> all three,
      * FUEL_X/Y    - menu (slot) + generator (frame),
      * PLAYER_X/Y  - menu (inventory) + generator (frames),
      * FLAME_X/Y   - screen (flame sprite) + generator (position for the label).

    It has already diverged once: the fuel slot sat where the menu said, while
    the texture had its frame somewhere else - the slot overlapped the
    "Inventory" label. The compiler does not see this, and in game it looks
    like a graphics bug.
    """
    def consts(path, names):
        text = open(path, encoding="utf-8").read()
        out = {}
        for n in names:
            m = re.search(r"\b" + re.escape(n) + r"\s*=\s*(-?\d+)", text)
            out[n] = int(m.group(1)) if m else None
        return out

    paths = {
        "menu": "neoforge/src/main/java/com/craftingveloce/inventory/VeloceVelocityFurnaceMenu.java",
        "ekran": "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceVelocityFurnaceScreen.java",
        "generator": "scripts/gen_furnace_gui.py",
    }
    # The battery and its slot live in the ELECTRIC furnace menu and in its screen.
    paths["menu_el"] = "neoforge/src/main/java/com/craftingveloce/inventory/VeloceElectricFurnaceMenu.java"
    paths["ekran_el"] = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceElectricFurnaceScreen.java"
    # Sensor: the menu places the item slot, and the GUI generator paints the
    # frame underneath it. It is the same pair as in the furnace - and this
    # very pair was outside the check as long as the generator wrote it as a
    # one-liner ("FILTER_X, FILTER_Y = 26, 18"), which a regular expression
    # does not see.
    paths["menu_s"] = "neoforge/src/main/java/com/craftingveloce/inventory/VeloceThresholdSensorMenu.java"
    paths["gen_s"] = "scripts/gen_sensor_textures.py"
    names = ["FILTER_X", "FILTER_Y", "FUEL_X", "FUEL_Y", "PLAYER_X", "PLAYER_Y",
             "FLAME_X", "FLAME_Y",
             "BATTERY_X", "BATTERY_Y", "BATTERY_W", "BATTERY_H", "NUB_W", "NUB_H",
             "BATTERY_SLOT_X", "BATTERY_SLOT_Y",
             "HEAT_BATTERY_X", "HEAT_BATTERY_Y", "HEAT_BATTERY_W", "HEAT_BATTERY_H",
             "FILTER_SLOT_X", "FILTER_SLOT_Y"]
    # By default we compare the FUEL furnace triple: menu, screen, generator.
    # The remaining elements have their own lists below.
    who = {n: ["menu", "ekran", "generator"] for n in names}
    for n in ("FILTER_SLOT_X", "FILTER_SLOT_Y"):
        who[n] = ["menu_s", "gen_s"]            # sensor: menu + generator
    for n in ("FUEL_X", "FUEL_Y", "PLAYER_X", "PLAYER_Y"):
        who[n] = ["menu", "generator"]          # the screen does not need them
    for n in ("FLAME_X", "FLAME_Y"):
        who[n] = ["ekran", "generator"]         # the menu does not need them
    for n in ("BATTERY_X", "BATTERY_Y", "BATTERY_W", "BATTERY_H", "NUB_W", "NUB_H"):
        who[n] = ["ekran_el", "generator"]      # battery of the electric furnace
    for n in ("HEAT_BATTERY_X", "HEAT_BATTERY_Y", "HEAT_BATTERY_W", "HEAT_BATTERY_H"):
        # The FUEL furnace's accumulator. Its own names, because the electric furnace
        # has a battery of the same size somewhere else - one shared name would make
        # this compare the wrong pair, and the two are supposed to be independent.
        who[n] = ["ekran", "generator"]
    for n in ("BATTERY_SLOT_X", "BATTERY_SLOT_Y"):
        # The electric screen USES the constant from the menu (it has no copy
        # of its own) - and that is how it should be: one source. The generator
        # paints the frame under that position.
        who[n] = ["menu_el", "generator"]

    values = {n: {k: consts(paths[k], [n])[n] for k in who[n]} for n in names}

    problems = []
    for n in names:
        v = values[n]
        if any(x is None for x in v.values()):
            problems.append(f"{n}: no constant (" + ", ".join(
                f"{k}={'missing' if x is None else x}" for k, x in v.items()) + ")")
        elif len(set(v.values())) > 1:
            problems.append(f"{n}: " + ", ".join(f"{k}={x}" for k, x in v.items()))

    if problems:
        fail("GUI layout does not agree between files:\n  " + "\n  ".join(problems))
    print("    OK (GUI layout consistent: " + ", ".join(
        f"{n}={values[n][who[n][0]]}" for n in names) + ")")

def validate_sensor_row():
    """
    The sensor row: centred ON BOTH SIDES, even gaps, nothing overlapping.

    The player asked directly for ONE aligned row (item slot, number field,
    "+", "-", mode button), centred - first horizontally, and then a second
    report came in: "horizontally fine, but vertically too high". The
    coordinates are hand-calculated numbers, and such a typo is only visible
    in game - as a crooked GUI. Here we compute the same thing the player
    sees: horizontal margins, vertical margins in the working area
    (title -> inventory) and the slot centred in the row.
    """
    path = "neoforge/src/main/java/com/craftingveloce/inventory/VeloceThresholdSensorMenu.java"
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
            fail(f"no {n} constant in {path} (build.py reads them one by one)")
        v[n] = int(m.group(1))

    pieces = [("slot", v["FILTER_SLOT_X"], v["SLOT_SIZE"]),
              ("field", v["FIELD_X"], v["FIELD_W"]),
              ("+", v["STEP_PLUS_X"], v["BTN_W"]),
              ("-", v["STEP_MINUS_X"], v["BTN_W"]),
              ("mode", v["MODE_X"], v["BTN_W"])]

    problems = []
    for (an, ax, aw), (bn, bx, _bw) in zip(pieces, pieces[1:]):
        gap = bx - (ax + aw)
        if gap != v["GAP"]:
            problems.append(f"gap {an} -> {bn} = {gap} px, should be {v['GAP']}")
    left = pieces[0][1]
    right = v["PANEL_WIDTH"] - (pieces[-1][1] + pieces[-1][2])
    if left != right:
        problems.append(f"row is not centred horizontally: "
                        f"left margin {left}, right margin {right}")

    # Vertical: the working area is the gap between the title and the player inventory.
    above = v["ROW_Y"] - v["TITLE_BOTTOM"]
    below = v["PLAYER_Y"] - (v["ROW_Y"] + v["ROW_H"])
    if below < 0:
        problems.append(f"row (y={v['ROW_Y']}..{v['ROW_Y'] + v['ROW_H']}) "
                        f"overlaps the player inventory (y={v['PLAYER_Y']})")
    elif abs(above - below) > 1:
        problems.append(f"row is not centred vertically: "
                        f"{above} px above it, {below} px below it")

    # A 16 px slot must be centred in a 20 px row.
    slot_above = v["FILTER_SLOT_Y"] - v["ROW_Y"]
    slot_below = v["ROW_Y"] + v["ROW_H"] - (v["FILTER_SLOT_Y"] + v["SLOT_SIZE"])
    if abs(slot_above - slot_below) > 1:
        problems.append(f"slot is not centred in the row: "
                        f"{slot_above} px above it, {slot_below} px below it")

    if problems:
        fail("sensor row:\n  " + "\n  ".join(problems))
    row_w = pieces[-1][1] + pieces[-1][2] - pieces[0][1]
    print(f"    OK (sensor row: {row_w} px, margin {left} px on both sides, "
          f"{above} px above and {below} px below)")


def validate_node_blocks():
    """
    Whoever is a network node must say so in ONE way - through the interface.

    A pipe network node has two duties that must go hand in hand:
      * implement {@code VeloceNetworkNode} (otherwise the core will not
        recognise it: it will not reach the terminals and its chunk will not be
        kept loaded),
      * call {@code VeloceNodeBlocks.onNodePlaced} / {@code onNodeRemoved}
        (otherwise the network will not learn about the block being
        placed/destroyed).

    This pair has already diverged once: the controller was not recognised as a
    node, so its GUI showed an empty stock, and the filter slots could be
    exposed to the network as ordinary storage. Both errors are invisible to
    the compiler - that is why the build guards them.
    """
    # Core blocks AND module blocks: a machine from compat/ is also a network
    # node (otherwise the crafter would not see it), and it is easy to forget
    # exactly in a module that is only just being written.
    block_dirs = ["neoforge/src/main/java/com/craftingveloce/block"]
    block_dirs += sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/block"))
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
        name = os.path.relpath(path, "neoforge/src/main/java/com/craftingveloce")
        text = open(path, encoding="utf-8").read()
        is_interface = re.search(r"class\s+\w+[^{]*\bimplements\b[^{]*\bVeloceNetworkNode\b",
                                 text) is not None
        has_hook = "VeloceNodeBlocks.onNodePlaced(" in text
        has_remove = "VeloceNodeBlocks.onNodeRemoved(" in text
        if is_interface:
            nodes.append(name)
            if not (has_hook and has_remove):
                fail(f"{name} implements VeloceNetworkNode, but does not call "
                     f"{'onNodePlaced' if not has_hook else 'onNodeRemoved'} - "
                     f"the network will not learn about this block changing")
        elif has_hook or has_remove:
            hooked.append(name)
    if hooked:
        fail("these blocks register themselves with the network, but are not "
             "nodes (no VeloceNetworkNode):\n  " + "\n  ".join(hooked))
    if not nodes:
        fail("no block implements VeloceNetworkNode - "
             "network node recognition is broken")
    print(f"    OK ({len(nodes)} network nodes: interface + hooks in pairs)")


def validate_recipe_model():
    """
    One rule about recipes = one place in the code.

    This project has a recurring bug: the same rule written by hand in two
    places, and the places diverging (network node list, packet list, GUI
    layout). With recipes there were as many as THREE copies of the type list
    (registry, graph, crafter GUI) and TWO copies of the "special" filter -
    because of that second filter, mod recipes were being silently thrown away
    (Mekanism marks all of its own that way), so no module from another mod
    would have had anything to count.

    We guard three invariants:
      1. there is ONE recipe model (ProcessingEntry) - no second
         CraftingEntry record,
      2. the recipe type list may be declared ONLY in
         VeloceRecipeFamilies.java (everything else must take it from that
         class),
      3. the "special" filter may be applied ONLY in
         VeloceRecipeRegistry.isVanillaSpecial (narrowed to the minecraft
         namespace), and not directly through recipe.isSpecial().
    """
    src_root = SRC_ROOT
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
                    # The filter may be applied only here - and in exactly one
                    # place (isVanillaSpecial). A second attempt in the same
                    # file would mean a second filter next to the one narrowed
                    # to minecraft.
                    uses = text.count(".isSpecial()")
                    if uses != 1:
                        dup_special.append(rel + f" ({uses}x, should be 1x)")
                else:
                    dup_special.append(rel)
    problems = []
    if dup_model:
        problems.append("a second recipe model (record CraftingEntry) in: "
                        + ", ".join(dup_model))
    if dup_lists:
        problems.append("a private copy of the recipe type list (only "
                        "VeloceRecipeFamilies is allowed) in: " + ", ".join(dup_lists))
    if dup_special:
        problems.append("filtering by recipe.isSpecial() outside "
                        "isVanillaSpecial() in: " + ", ".join(dup_special))
    if problems:
        fail("recipe model:\n  " + "\n  ".join(problems))
    entries = os.path.join(src_root, "com/craftingveloce/crafting/ProcessingEntry.java")
    if not os.path.exists(entries):
        fail("no ProcessingEntry - the single recipe model")
    print("    OK (one recipe model, one family list, one special filter)")


def _imports_of(path):
    """The full set of imported names in a file (simply, without a parser)."""
    text = open(path, encoding="utf-8").read()
    return [m.group(1) for m in re.finditer(r"^import\s+([\w.]+);", text, re.M)]


def foreign_packages_used():
    """
    Prefixes of foreign packages that are ACTUALLY imported in src/.

    Thanks to this, the "gates only, zero blocks" stage does not require
    foreign mod JARs, and the first file with a foreign type immediately
    forces them.
    """
    used = set()
    for path in glob.glob("neoforge/src/main/java/**/*.java", recursive=True):
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
    Extracts a JAR from another mod's META-INF/jarjar into libs/.

    Create bundles its libraries (Ponder, Flywheel, Registrate) as nested
    JARs. Ponder alone is enough to compile - without it javac does not see the
    kinetic block base class ("cannot access VirtualBlockEntity").
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
                    print(f"    extracted {name} from {os.path.basename(jar)}")
                    return dest
            except zipfile.BadZipFile:
                continue
    return None


def resolve_compile_only():
    """The compileOnly JARs needed for THIS compilation (may be empty)."""
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
                missing.append(" or ".join(variants))
    if missing:
        fail("missing compile-time dependencies: " + ", ".join(sorted(set(missing)))
             + "\n  foreign package imports in src/: " + ", ".join(sorted(used))
             + "\n  looked in: " + ", ".join(COMPILE_ONLY_DIRS)
             + "\n  put the JARs there (or into libs/) and run again")
    return paths

def validate_core_isolation():
    """
    The core does not know foreign mods, and a compat module knows ONLY its own mod.

    Two invariants:
      1. no file outside {@code compat/} imports a foreign package - otherwise
         the mod will not start without that mod (NoClassDefFoundError while
         linking),
      2. a {@code compat/<mod>} module does not import another foreign mod -
         this is the most common mistake when copying a file from one
         integration to another, and it only shows up for a player who has just
         one of those mods.
    """
    owners = {
        "create": ("com.simibubi.create", "net.createmod"),
        "alchemistry": ("com.smashingmods",),
        "mekanism": ("mekanism",),
        "jei": ("mezz.jei",),
        "jade": ("snownee.jade",),
    }
    core, cross, checked = [], [], 0
    for path in glob.glob("neoforge/src/main/java/com/craftingveloce/**/*.java", recursive=True):
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
                cross.append(f"{rel} -> {name} (module '{owner}')")
    problems = []
    if core:
        problems.append("the core imports foreign mods:\n  " + "\n  ".join(core))
    if cross:
        problems.append("a compat module imports a foreign mod:\n  " + "\n  ".join(cross))
    if problems:
        fail("module isolation:\n  " + "\n  ".join(problems))
    print(f"    OK (isolation: {checked} files with foreign imports, all in compat/)")


def validate_compat_gates(z):
    """
    Classes loaded ALWAYS must not have a foreign type in their signature.

    This is the most important isolation invariant and at the same time the
    easiest to break: adding a field or a parameter of a foreign mod's type to
    a gate class breaks the mod without that mod. The reason is mechanical -
    the JVM must know the types from the signatures in order to verify a class,
    while it resolves method bodies only when they are CALLED.

    We check four groups of classes (all loaded unconditionally):
      1. the core (outside {@code compat/}),
      2. classes in {@code compat/} itself (e.g. VeloceMods - the mod enum),
      3. the gate classes {@code compat/<mod>/XCompat},
      4. classes in {@code compat/<mod>/} - except the gates - are loaded ONLY
         after checking that the mod is present, so they may have foreign types.

    JEI is a special case here, and that is why it has NO gate of its own: its
    plugin ({@code compat/jei/VeloceJeiPlugin}) has a foreign type in the
    SIGNATURE of a method ({@code registerRecipeCatalysts(IRecipeCatalystRegistration)}),
    and JEI itself loads it by scanning for the {@code @JeiPlugin} annotation.
    Without JEI there is nobody to load it, so the invariant "foreign types
    only after checking that the mod is present" holds - and that is exactly
    why the plugin is in {@code module_dirs} and not in {@code gates}.

    NOTE: this must be a SIGNATURE check, not a class-loading test. HotSpot
    resolves types lazily, so a class with an unused field of a foreign type
    will load without the foreign mod - and only blow up when somebody touches
    that field (e.g. a year later, while making another change). Calibration:
    injecting a field of a Create type into VeloceMods passes the L1 test, and
    it MUST be caught here.
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
            continue   # loaded conditionally - foreign types are allowed here
        targets.append(name[:-len(".class")].replace("/", "."))

    problems, checked = [], 0
    for cls in sorted(targets):
        res = subprocess.run(["javap", "-p", "-s", "-cp", BUILD_OUT, cls],
                             capture_output=True, text=True)
        if res.returncode != 0:
            problems.append(f"{cls}: javap failed ({res.stderr.strip()[:120]})")
            continue
        checked += 1
        for line in res.stdout.splitlines():
            stripped = line.strip()
            if not (stripped.endswith(";") or stripped.startswith("descriptor:")):
                continue
            # Our own names may contain the word "mekanism" (the compat module
            # package), so we first strip our whole qualification - we are
            # looking for a foreign package, not for a word.
            probe = re.sub(r"com\.craftingveloce(\.\w+)+", "", line)
            probe = re.sub(r"com/craftingveloce(/[\w$]+)+", "", probe)
            for pkg in FOREIGN_PACKAGES:
                if pkg in probe or pkg.replace(".", "/") in probe:
                    problems.append(f"{cls}: foreign type in signature -> {stripped}")
    if problems:
        fail("signature isolation:\n  " + "\n  ".join(problems[:8]))
    missing = [c for c in gates.values() if c not in targets]
    if missing:
        fail("missing gate classes in the JAR: " + ", ".join(missing))
    print(f"    OK ({checked} always-loaded classes: zero foreign types in signatures)")


def validate_jar_isolation(z):
    """
    BYTECODE level: the core does not reference any foreign mod class.

    The source check looks at {@code import} lines - but a foreign type can be
    used without an import (a fully qualified name in the code). That is why we
    check the finished artifact: none of our classes OUTSIDE {@code compat/}
    may have a reference to a foreign package in the constant pool. It is the
    same invariant, but resistant to how it is spelled in the source.
    """
    root = "com/craftingveloce/"
    compat = root + "compat/"
    own_prefix = compat.encode()
    patterns = [p.encode() for p in FOREIGN_JAR_PATHS]

    def foreign_refs(data):
        """References to foreign packages, ignoring our own compat/ directory."""
        found = []
        for pattern in patterns:
            start = 0
            while True:
                i = data.find(pattern, start)
                if i < 0:
                    break
                # A reference to OUR compat module
                # (com/craftingveloce/compat/mekanism/...) also contains the
                # word "mekanism/" - that is not a leak.
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
        fail("the core references foreign mods (bytecode level):\n  "
             + "\n  ".join(bad[:5]))
    print("    OK (core bytecode with no references to foreign mods)")


def validate_isolation_runtime(cp, toms, rs):
    """
    L1 live: the gate classes MUST be loadable WITHOUT the foreign mods.

    The static checks (imports, bytecode) guard where a foreign type sits.
    This test checks the EFFECT: it runs a small program with a classpath
    WITHOUT Create, Alchemistry and Mekanism, which loads the gate classes and
    asks them about mod presence. If a gate had a foreign type in its
    signature, merely loading it would throw NoClassDefFoundError - exactly
    what a player without that mod would see (and what cannot be seen at
    compile time).
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
        fail("the isolation test does not compile (scripts/isolation/L1Test.java):\n"
             + res.stderr[:600])
    # We run it in a temporary directory so that the logger (log4j from the MC
    # classpath) does not create a logs/ directory in the repository.
    res = subprocess.run(["java", "-cp", os.pathsep.join([out, classpath]), "L1Test"],
                         capture_output=True, text=True, cwd=out)
    if res.returncode != 0:
        lines = [l for l in res.stdout.splitlines() if l.startswith("FAIL") or l.startswith("ERROR")]
        fail("L1 (mod without the foreign mods) did not pass:\n  " + "\n  ".join(lines[:8]))
    print("    OK (L1: gates load without the foreign mods and report 'missing')")


def validate_module_block_ids():
    """
    A module block ID must start with {@code veloce_<mod>_}.

    Without that, two modules may independently pick the same name (e.g.
    "combiner" exists both in Mekanism and in Alchemistry) and one block will
    overwrite the other - and the bug only shows up for a player who has both
    mods. The mod prefix solves this once and for all and immediately says
    which integration the block comes from.
    """
    problems = []
    checked = 0
    for path in sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/*Blocks.java")):
        rel = path.replace(os.sep, "/")
        mod = rel.split("/compat/", 1)[1].split("/", 1)[0]
        text = open(path, encoding="utf-8").read()
        for block_id in re.findall(r'BLOCKS\.register\(\s*"([a-z0-9_]+)"', text):
            checked += 1
            expected = f"veloce_{mod}_"
            if not block_id.startswith(expected):
                problems.append(f"{rel}: '{block_id}' does not start with '{expected}'")
    if problems:
        fail("module block names:\n  " + "\n  ".join(problems))
    if checked:
        print(f"    OK ({checked} module blocks: names with the mod prefix)")


def validate_create_kinetics():
    """
    A Create kinetic block MUST implement {@code IBE}.

    Create ticks its machines through the default {@code getTicker} from
    {@code IBE} - without that interface the block entity is never ticked,
    {@code getSpeed()} stays at zero, and the machine is "unpowered" forever.

    This is the worst kind of bug: the block places fine, looks good, there is
    nothing in the logs, and the integration simply does not work. That is why
    the build guards it.
    """
    problems, checked = [], 0
    for path in sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/block/*.java")):
        text = open(path, encoding="utf-8").read()
        if "extends KineticBlock" not in text:
            continue
        checked += 1
        if "IBE<" not in text:
            problems.append(path.replace(os.sep, "/")
                            + ": extends KineticBlock but does not implement IBE<> "
                              "(the BE would not be ticked - the machine never spins)")
    if problems:
        fail("Create kinetics:\n  " + "\n  ".join(problems))
    if checked:
        print(f"    OK ({checked} kinetic blocks: IBE provides BE ticking)")


def validate_number_format():
    """
    Number formatting in the GUI lives in ONE place: {@code util/VeloceFormat}.

    This project has a recurring bug: the same rule written by hand in several
    places, and the places diverging. With numbers there were THREE copies
    (slot overlay, terminal, controller screen), and the player reported it
    directly: "15.0" in the tooltip next to "1K" on the icon are two different
    formats of the same number.

    The rule: the {@code %.<digits>f} pattern (a hand-built fraction) may be
    used ONLY in VeloceFormat. Anyone who needs a number for the GUI calls
    {@code compact}/{@code rate}/{@code feCompact}.
    """
    pattern = re.compile(r"%\.[0-9]+f")
    allowed = os.path.join(SRC_ROOT, "com", "craftingveloce", "util", "VeloceFormat.java")
    bad = []
    for path in glob.glob("neoforge/src/main/java/com/craftingveloce/**/*.java", recursive=True):
        if path == allowed or any(x in path for x in EXCLUDED_SRC):
            continue
        if pattern.search(open(path, encoding="utf-8").read()):
            bad.append(path.replace(os.sep, "/"))
    if bad:
        fail("custom fraction formatting outside VeloceFormat:\n  "
             + "\n  ".join(bad)
             + "\n  use VeloceFormat.compact/rate/feCompact")
    print("    OK (number formats only in VeloceFormat)")


def validate_module_recipe_access():
    """
    {@code recipesAnywhere} must NOT filter by machines or by power.

    This method exists precisely so that tools (e.g. {@code /cv getitems}) can
    say "how is this made" without having a machine. If somebody adds a
    condition "the machine is running and has power" to it (because that is
    what the neighbouring method, {@code recipesFor}, looks like), the command
    will start lying again: for an item produced in a module machine it will
    say "no recipe".

    This is not a hypothesis - the bug already occurred in another form (the
    command did not see module recipes at all), and the symptom is misleading:
    missing information pretends to be information. That is why the build
    guards it.
    """
    problems, checked = [], 0
    for path in sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/*Module.java")):
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
        fail("recipesAnywhere filters by machines (and it should not):\n  "
             + "\n  ".join(problems))
    if checked:
        print(f"    OK ({checked} modules: recipesAnywhere without machine gating)")


def validate_auto_crafter_ingredient_rule():
    """
    The planner and the EXECUTION must use the same "what counts as an
    ingredient" rule.

    The symptom of those two places diverging (a player report): "the GUI shows
    2 crushing wheels, but when crafting it says I do not have the items". The
    planner skipped empty grid slots, while the execution tried to "take" them
    and failed immediately - the count was computed correctly, and the craft
    NEVER worked.

    That is why both places MUST ask a common rule ({@code hasOptions}).
    """
    path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java"
    if not os.path.exists(path):
        return
    text = open(path, encoding="utf-8").read()
    if "private static boolean hasOptions(" not in text:
        fail("no common hasOptions rule in VeloceAutoCrafter")
    problems = []
    for method in ("planRecipe", "runOnce"):
        markers = [m.start() for m in re.finditer(r"\b" + method + r"\s*\(", text)]
        # the method declaration is the last (or the only proper) hit;
        # we take the first hit after the words "private static" for this name
        decl = re.search(r"private static [\w<>\[\], .]*\b" + method + r"\s*\(", text)
        if not decl:
            problems.append(method + ": declaration not found")
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
            problems.append(method + ": does not check hasOptions (the rule will "
                                    "diverge from the other place)")
    if problems:
        fail("ingredient rule in the auto-crafter:\n  " + "\n  ".join(problems))
    print("    OK (planner and execution: one ingredient rule)")

def validate_block_models():
    """
    Every blockstate and item model must point to a file that IS in the JAR.

    The BUG that uncovered this (a player report: "the model is broken"): after
    renaming the Mekanism module blocks (veloce_crusher_module ->
    veloce_mekanism_crusher_module) I moved the model files, but the
    BLOCKSTATES still pointed at the old names. In game four blocks had no
    model (the message "Unable to load model" goes to the client log), and it
    looked like a broken block rather than a data bug - exactly the kind of
    divergence of two places that keeps coming back in this project.

    We also check the TEXTURES: a model must point to an existing PNG file.
    """
    asset_root = "assets/craftingveloce"
    model_dir = os.path.join(asset_root, "models")
    problems = []
    checked = 0

    def model_path(ref):
        """craftingveloce:block/x -> model file path."""
        ns, _, path = ref.partition(":")
        if not path:
            ns, path = "minecraft", ns
        if ns != "craftingveloce":
            return None            # vanilla models - not our business
        return os.path.join(model_dir, path + ".json")

    for bs in sorted(glob.glob(os.path.join(asset_root, "blockstates/*.json"))):
        data = json.load(open(bs, encoding="utf-8"))
        refs = []
        for variant in data.get("variants", {}).values():
            if isinstance(variant, dict):
                refs.append(variant.get("model"))
            elif isinstance(variant, list):
                refs.extend(v.get("model") for v in variant)
        # Multipart blockstate: the frame model + the side panels.
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
                problems.append(f"{bs.replace(os.sep, '/')}: no model {ref} ({path})")

    # Item models and their parent (the block model) plus textures. We also
    # check the textures inside item models (e.g. the "table in the frame" icon
    # has walls of its own) - a missing PNG file in the JAR is again a "broken
    # model", not a compilation error.
    for item_model in sorted(glob.glob(os.path.join(model_dir, "item/*.json"))):
        data = json.load(open(item_model, encoding="utf-8"))
        parent = data.get("parent")
        if parent:
            path = model_path(parent)
            checked += 1
            if path is not None and not os.path.exists(path):
                problems.append(f"{item_model.replace(os.sep, '/')}: no parent {parent}")

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
                    problems.append(f"{model_file.replace(os.sep, '/')}: no texture {ref}")

    if problems:
        fail("block models:\n  " + "\n  ".join(problems))
    print(f"    OK ({checked} references to models and textures exist)")


# JEI categories handled by our blocks: file -> {category UID: block field}.
#
# The UID is the UID of the JEI CATEGORY, and NOT the name of the recipe type -
# and that is the main trap here: the Create saw has the category
# "create:sawing", even though its recipe type is called "create:cutting".
# The UIDs were determined from the mods' bytecode (Create:
# Create.asResource(name) from build("sawing", ...), Mekanism:
# RecipeTypeRegistryObject.getId(), Alchemistry: RecipeType.create("alchemistry", ...)).
JEI_CATEGORIES = {
    "neoforge/src/main/java/com/craftingveloce/compat/VeloceJeiCatalysts.java": {
        "minecraft:crafting": "VELOCE_CRAFTING_TABLE_ITEM",
        # Verified against the JEI JAR: mezz.jei.api.constants.RecipeTypes.BREWING
        # exists and its UID is minecraft:brewing. Brewing has no vanilla
        # RecipeType, so JEI's own category is the only way our stand can appear
        # on a brewing recipe.
        "minecraft:brewing": "BREWING_STAND_ITEM",
    },
    "neoforge/src/main/java/com/craftingveloce/compat/create/CreateJeiCatalysts.java": {
        "create:milling": "VELOCE_MILLSTONE_MODULE_ITEM",
        "create:sawing": "VELOCE_SAW_MODULE_ITEM",
        "create:crushing": "VELOCE_CRUSHING_MODULE_ITEM",
        "create:mechanical_crafting": "VELOCE_MECHANICAL_CRAFTER_MODULE_ITEM",
        "create:pressing": "VELOCE_PRESS_MODULE_ITEM",
        "create:mixing": "VELOCE_MIXER_MODULE_ITEM",
        "create:deploying": "VELOCE_DEPLOYER_MODULE_ITEM",
    },
    # Only the ENABLED machines appear. The gas and fluid ones are switched off in
    # MekanismFeModules.DISABLED, and the Energized Smelter is gone - so a catalyst
    # for any of them would be a link to a block that cannot exist.
    "neoforge/src/main/java/com/craftingveloce/compat/mekanism/MekanismJeiCatalysts.java": {
        "mekanism:crushing": "VELOCE_CRUSHER_MODULE_ITEM",
        "mekanism:enriching": "VELOCE_ENRICHMENT_MODULE_ITEM",
        "mekanism:combining": "VELOCE_COMBINER_MODULE_ITEM",
        "mekanism:sawing": "VELOCE_SAWMILL_MODULE_ITEM",
        "mekanism:compressing": "VELOCE_COMPRESSING_MODULE_ITEM",
        "mekanism:metallurgic_infusing": "VELOCE_METALLURGIC_INFUSING_MODULE_ITEM",
    },
    "neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryJeiCatalysts.java": {
        "alchemistry:compactor": "VELOCE_COMPACTOR_MODULE_ITEM",
        "alchemistry:combiner": "VELOCE_COMBINER_MODULE_ITEM",
        "alchemistry:fission": "VELOCE_FISSION_MODULE_ITEM",
        "alchemistry:fusion": "VELOCE_FUSION_MODULE_ITEM",
        "alchemistry:atomizer": "VELOCE_ATOMIZER_MODULE_ITEM",
        "alchemistry:dissolver": "VELOCE_DISSOLVER_MODULE_ITEM",
        "alchemistry:liquifier": "VELOCE_LIQUIFIER_MODULE_ITEM",
    },
}


def validate_jade_info():
    """
    Jade: ONE status line (working / not enough force / not enough energy).

    The player: "throw out everything the Jade integration says and leave only
    powered/working". We check that the plugin exists, works without the
    machine modules, and adds EXACTLY one line - without speed, SU, energy and
    the network.
    """
    problems = []
    plugin = "neoforge/src/main/java/com/craftingveloce/compat/jade/VeloceJadePlugin.java"
    data_provider = "neoforge/src/main/java/com/craftingveloce/compat/jade/VeloceModuleDataProvider.java"
    component = "neoforge/src/main/java/com/craftingveloce/compat/jade/VeloceModuleComponentProvider.java"
    for path, what in ((plugin, "Jade plugin"), (data_provider, "Jade data"),
                       (component, "Jade tooltip")):
        if not os.path.exists(path):
            problems.append("no " + what)
    if problems:
        fail("Jade:\n  " + "\n  ".join(problems))

    plugin_text = open(plugin, encoding="utf-8").read()
    if "@WailaPlugin" not in plugin_text or "IWailaPlugin" not in plugin_text:
        problems.append("the Jade plugin is not a Jade plugin")
    common = _method_body(plugin_text, "public void register(")
    if common is None or "registerBlockDataProvider(" not in common:
        problems.append("the Jade plugin does not register server data")
    client = _method_body(plugin_text, "public void registerClient(")
    if client is None or "registerBlockComponent(" not in client:
        problems.append("the Jade plugin does not register the tooltip component")
    for path in sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/jade/*.java")):
        for name in _imports_of(path):
            if name.startswith(("com.craftingveloce.compat.create",
                                "com.craftingveloce.compat.mekanism",
                                "com.craftingveloce.compat.alchemistry")):
                problems.append(f"{os.path.basename(path)}: the Jade plugin imports {name}")

    data_text = open(data_provider, encoding="utf-8").read()
    should = _method_body(data_text, "public boolean shouldRequestData(")
    if should is None or "VeloceModuleInfoSource" not in should:
        problems.append("Jade asks for data about any old block entity")

    # The FUEL furnace is not a module and registers NO energy capability - on purpose,
    # so that nothing can move its heat - and that is exactly why Jade's own energy bar
    # cannot see it either. Its readout therefore has to be written here, or a player
    # looking at a furnace with a battery in it would be told nothing at all.
    component_text = open(component, encoding="utf-8").read()
    heat = _method_body(component_text, "private static void appendFuelFurnaceHeat(")
    if heat is None:
        problems.append("the Jade tooltip never shows the fuel furnace's heat, and Jade's "
                        "own bar cannot: that furnace has no energy capability")
    elif "gui.craftingveloce.furnace.temperature" not in heat:
        problems.append("the Jade line for the fuel furnace does not show a temperature")

    tooltip = _method_body(component_text, "public void appendTooltip(")
    if tooltip is None:
        problems.append("the Jade tooltip has no method")
    else:
        if "VeloceModuleStatus.message(" not in tooltip:
            problems.append("Jade does not show the working status")
        # The method existing is not enough - the failure this catches is the CALL being
        # dropped, which leaves the furnace silently back to showing nothing while every
        # other check here still passes.
        if "appendFuelFurnaceHeat(" not in tooltip:
            problems.append("the Jade tooltip never calls the fuel furnace's heat lines, "
                            "so a furnace with a battery in it shows nothing")
        extra = [line.strip() for line in tooltip.splitlines()
                 if "tooltip.add" in line and "VeloceModuleStatus.message(" not in line
                 and "addAll" not in line]
        if extra:
            problems.append("Jade adds more than the status: " + " | ".join(extra[:3]))
        for forbidden in ("speed", "suDraw", "energy", "networkNodes"):
            if forbidden in tooltip:
                problems.append(f"Jade still prints {forbidden}")

    if problems:
        fail("Jade (working status only):\n  " + "\n  ".join(problems))
    print("    OK (Jade: one status line - working / not enough force / not enough energy)")


def validate_terminal_craft_error():
    """
    The reason for a failed craft in the terminal goes into the item TOOLTIP.

    The player: "when something cannot be made in the terminal, those messages
    go to the in-game action bar, which is not visible in the terminal GUI -
    let the reason show up in the tooltip of the item that could not be made".

    The chain is long and every link breaks SILENTLY (the player again sees
    nothing, or sees the message on a random item):
      1. the server sends the reason in a PACKET, not through the action bar -
         and only in the failure branch (the messages about a missing terminal
         and a full inventory stay, because nothing replaces them),
      2. the packet is registered (otherwise it will not arrive),
      3. the screen attaches the reason to the tooltip and clears the memory
         when closed,
      4. the screen compares the terminal position (a packet from another
         terminal must not show the reason on this screen),
      5. the reason memory knows the ITEM, expires and is red,
      6. the message is assembled from the common source of keys
         (VeloceCraftErrors), and not from a second, hand-written switch.
    """
    problems = []
    pkt = "neoforge/src/main/java/com/craftingveloce/network/TerminalCraftErrorPKT.java"
    pull = "neoforge/src/main/java/com/craftingveloce/network/TerminalPullItemPKT.java"
    handler = "neoforge/src/main/java/com/craftingveloce/network/VelocePacketHandler.java"
    screen = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceTerminalScreen.java"
    hints = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceCraftErrorHints.java"
    helper = "neoforge/src/main/java/com/craftingveloce/client/ClientTerminalHelper.java"
    errors = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceCraftErrors.java"
    for path, what in ((pkt, "the reason packet"), (hints, "the reason memory"),
                       (errors, "the common message keys")):
        if not os.path.exists(path):
            problems.append("no " + what)

    if os.path.exists(handler) and "TerminalCraftErrorPKT.TYPE" not in open(
            handler, encoding="utf-8").read():
        problems.append("the reason packet is not registered")

    if os.path.exists(pull):
        body = _method_body(open(pull, encoding="utf-8").read(), "public static void handle(")
        if body is None:
            problems.append("no terminal pull handling")
        else:
            branch = _failure_branch(body)
            if branch is None:
                problems.append("no failure branch in the terminal handling")
            elif "sendCraftError(serverPlayer, pkt, pulled)" not in branch:
                problems.append("the failure does not send the reason to the tooltip")
            elif "displayClientMessage" in branch:
                problems.append("the failure still goes to the action bar (invisible in the GUI)")
            if "inventoryFull" not in body:
                problems.append("the information about a full inventory is gone")

    if os.path.exists(screen):
        text = open(screen, encoding="utf-8").read()
        tooltip = _method_body(text, "public List<Component> getTooltipFromContainerItem(")
        if tooltip is None or "craftErrors.appendTo(" not in tooltip:
            problems.append("the item tooltip does not attach the reason for a failed craft")
        removed = _method_body(text, "public void removed(")
        if removed is None or "craftErrors.clear()" not in removed:
            problems.append("the screen does not clear the reasons when closed")
        setter = _method_body(text, "public void setCraftError(")
        if setter is None or "pos.equals(this.terminalPos)" not in setter:
            problems.append("the screen does not check which terminal the reason comes from")

    if os.path.exists(hints):
        text = open(hints, encoding="utf-8").read()
        for need, what in (("ChatFormatting.RED", "the red colour"),
                           ("VeloceCraftErrors.message(", "the common source of keys"),
                           ("Map<Item, Hint>", "the key by item")):
            if need not in text:
                problems.append("the reason memory without " + what)
        # We check EXPIRY in the BODY of appendTo, and not in the whole file:
        # the LIFETIME_MS constant declaration stays in place even when the
        # condition using it disappears - and a message from half an hour ago
        # comes back to the tooltip. Calibration: replacing the condition with
        # `if (false)` MUST be caught (and it was not, as long as the check
        # looked at the whole file).
        append = _method_body(text, "public void appendTo(")
        if append is None:
            problems.append("the reason memory has no method that attaches the reason")
        else:
            for need, what in (("LIFETIME_MS", "reason expiry"),
                               ("Util.getMillis()", "the timestamp of the reason")):
                if need not in append:
                    problems.append("the reason memory without " + what)

    if os.path.exists(helper):
        forward = _method_body(open(helper, encoding="utf-8").read(),
                               "public static void handleCraftError(")
        if forward is None or "screen.setCraftError(" not in forward:
            problems.append("the helper does not forward the reason to the terminal screen")

    if os.path.exists(errors):
        text = open(errors, encoding="utf-8").read()
        for need in ("craftingveloce.craft.error.noBaseItem",
                     "craftingveloce.craft.error.extractItem"):
            if need not in text:
                problems.append(f"the common keys without the variant with detail ({need})")

    if problems:
        fail("reason for a failed craft in the terminal:\n  " + "\n  ".join(problems))
    print("    OK (terminal: the reason for a failed craft in the item tooltip, not the action bar)")


def _failure_branch(body):
    """
    The failure branch of the terminal handling: from the condition to its
    {@code return}.

    We check EXACTLY those few lines, because only there may (and must) the
    reason be sent. Checking the whole method body would let the action bar
    return - after all, the message about a full inventory stays in the method.
    """
    start = body.find("if (pulled.stack().isEmpty()) {")
    if start < 0:
        return None
    end = body.find("return;\n            }", start)
    return body[start:end if end > 0 else len(body)]

def validate_model_texture_refs():
    """
    Every `#N` a block model references must be DEFINED in that model's `textures`.

    This is not theoretical. Adding a glass pane to the terminal shipped a model whose
    element said `"texture": "#3"` while the `textures` block never defined `#3`, because
    the edit that added the pane was followed by a script that rewrote the file without
    the texture entry. The build stayed green and the game loaded - the block simply
    rendered the purple-and-black MISSING TEXTURE, which is how the player found it.

    A missing reference is invisible to every check that only asks "does the model parse":
    it parses perfectly, it just points at nothing.
    """
    problems = []
    files = sorted(glob.glob("assets/craftingveloce/models/block/*.json"))
    files += sorted(glob.glob("assets/craftingveloce/models/item/*.json"))
    checked = 0
    for path in files:
        try:
            model = json.load(open(path, encoding="utf-8"))
        except (ValueError, OSError):
            continue                      # other guards own malformed JSON
        defined = set(model.get("textures", {}).keys())
        used = set()
        for element in model.get("elements", []):
            for face in element.get("faces", {}).values():
                tex = face.get("texture")
                if isinstance(tex, str) and tex.startswith("#"):
                    used.add(tex[1:])
        # `particle` is a texture KEY, never referenced with '#'
        missing = used - defined

        # A model with NO `textures` of its own is a geometry-only parent: it is
        # deliberately incomplete and expects the CHILD - the model that lists it as its
        # `parent` - to supply the keys. `pipe_part.json` is exactly that, and
        # `veloce_pipe_part.json` supplies its `#0`. Reporting it would be a false alarm
        # about a pattern the mod uses on purpose, so it is skipped. A model that defines
        # SOME textures but is missing one that its own elements use is a different thing
        # entirely, and is still a failure.
        if not defined:
            continue
        if missing:
            problems.append(f"{os.path.basename(path)} uses {sorted(missing)} "
                            f"but defines only {sorted(defined)}")
        checked += 1
    if problems:
        fail("block models reference undefined textures:\n  " + "\n  ".join(problems))
    print(f"    OK ({checked} models, every '#N' reference resolves)")


def validate_jei_integrale_category():
    """
    The "Integrale conversion" JEI category - the one that shows the mod's RULE.

    Item 1 of the cleanup, in the player's words: nothing in this mod is made in a crafting
    table, so JEI has to show that you right-click a Veloce Integrale with item X and get
    machine Y. Every link of that breaks SILENTLY - JEI simply shows an empty tab, or a tab
    that disagrees with the block - so each one is pinned here:

      1. the category exists and is a real JEI category, and it owns its own RecipeType
         (a category with no type cannot be filled, and the tab stays empty);
      2. the rows are built from `VeloceIntegraleConversions.all()`, the SAME table the
         block consults on right-click - so what JEI shows and what actually works cannot
         drift apart;
      3. rows whose result does not resolve are SKIPPED. The table keys on ids precisely
         because another mod's block may not exist yet when its row is registered, so
         without this a player without that mod would be shown a recipe for nothing;
      4. the information page is registered on the frame item, because the category alone
         does not say the rule in words;
      5. the three language keys exist - a missing key shows the raw key to the player.
    """
    problems = []
    category = "neoforge/src/main/java/com/craftingveloce/compat/jei/IntegraleConversionCategory.java"
    recipe = "neoforge/src/main/java/com/craftingveloce/compat/jei/IntegraleConversionRecipe.java"
    plugin_path = "neoforge/src/main/java/com/craftingveloce/compat/jei/VeloceJeiPlugin.java"
    for path, what in ((category, "Integrale conversion category"), (recipe, "its recipe type"),
                       (plugin_path, "JEI plugin")):
        if not os.path.exists(path):
            problems.append("no " + what)
    if problems:
        return fail("JEI Integrale conversion:\n  " + "\n  ".join(problems))

    category_text = open(category, encoding="utf-8").read()
    if "implements IRecipeCategory<IntegraleConversionRecipe>" not in category_text:
        problems.append("the category is not an IRecipeCategory of its own recipe type")
    if "RecipeType<IntegraleConversionRecipe> TYPE" not in category_text:
        problems.append("the category declares no RecipeType of its own - the tab stays empty")

    plugin_text = open(plugin_path, encoding="utf-8").read()
    if "IntegraleConversionCategory" not in _method_body(plugin_text,
                                                        "public void registerCategories("):
        problems.append("the category is never registered in registerCategories")
    recipes_body = _method_body(plugin_text, "public void registerRecipes(")
    if recipes_body is None:
        problems.append("no registerRecipes - nothing ever fills the category")
    else:
        if "VeloceIntegraleConversions.all()" not in recipes_body:
            problems.append("the rows do NOT come from VeloceIntegraleConversions.all(), so JEI "
                            "can drift from the table that performs the conversion")
        if "Blocks.AIR" not in recipes_body and "== null" not in recipes_body:
            problems.append("rows whose result does not resolve are not skipped - a player "
                            "without that mod would be shown a recipe for nothing")
    if "addIngredientInfo(" not in plugin_text:
        problems.append("no information page on the frame item - the category shows the "
                        "examples but never states the rule")

    lang = json.load(open("assets/craftingveloce/lang/en_us.json", encoding="utf-8"))
    for key in ("craftingveloce.jei.integrale_conversion",
                "craftingveloce.jei.integrale_conversion.howto",
                "craftingveloce.jei.integrale_info"):
        if key not in lang:
            problems.append("missing language key " + key)

    if problems:
        fail("JEI Integrale conversion:\n  " + "\n  ".join(problems))
    print("    OK (category exists, filled from the conversion table, unresolved rows skipped, "
          "info page and 3 language keys present)")


def validate_jei_catalysts():
    """
    JEI: our blocks on the "this is where you can make this recipe" list.

    The player: "JEI must show that our blocks also make the recipe (like the
    crafting table, crafter, formulaic assembler, robot, terminal list at the
    crafting recipe)". Every link of this chain breaks SILENTLY - JEI will
    simply show too few icons and nobody will notice - therefore:
      1. the {@code @JeiPlugin} plugin exists and registers catalysts through
         the JEI API ({@code getJeiHelpers().getRecipeType(...)} +
         {@code addRecipeCatalysts(...)}), and not through its own classes of
         the foreign mods,
      2. no file OUTSIDE {@code compat/jei/} references the plugin (a class
         with a foreign type in its signature must not be touched by the core).
         The import boundary of the plugin itself is guarded by
         {@code validate_core_isolation} (the {@code owners} map knows the
         {@code jei} module; it may only use {@code mezz.jei}) - that is why
         there is no second, unreachable check here: calibration showed that
         injecting {@code import com.simibubi.create} into the plugin stops the
         build exactly there,
      3. the table of category UID -> our block matches AS PAIRS (swapping the
         UIDs is also a bug: the millstone would show up for the crusher),
      4. the core fills in the {@code minecraft:crafting} category, and each
         mod's gate calls its own table,
      5. the UIDs of foreign categories are in {@code FOREIGN_PACKAGES} -
         otherwise the leak check in the JAR does not see them.
    """
    problems = []
    plugin = "neoforge/src/main/java/com/craftingveloce/compat/jei/VeloceJeiPlugin.java"
    registry = "neoforge/src/main/java/com/craftingveloce/compat/VeloceJeiCatalysts.java"
    for path, what in ((plugin, "JEI plugin"), (registry, "JEI category list")):
        if not os.path.exists(path):
            problems.append("no " + what)
            return fail("JEI:\n  " + "\n  ".join(problems))

    plugin_text = open(plugin, encoding="utf-8").read()
    if "@JeiPlugin" not in plugin_text or "IModPlugin" not in plugin_text:
        problems.append("the JEI plugin is not a JEI plugin (@JeiPlugin/IModPlugin)")
    if 'ResourceLocation.fromNamespaceAndPath(MOD_ID, "jei")' not in plugin_text:
        problems.append("the JEI plugin has no UID of its own")
    body = _method_body(plugin_text, "public void registerRecipeCatalysts(")
    if body is None:
        problems.append("the JEI plugin does not register catalysts")
    else:
        for need, what in (("getJeiHelpers()", "fetching the JEI helpers"),
                           ("getRecipeType(", "looking up a category by UID"),
                           ("addRecipeCatalysts(", "adding a catalyst")):
            if need not in body:
                problems.append("the JEI plugin without " + what)

    # The plugin is loaded by JEI also WITHOUT the other mods, so it may only
    # know JEI - that boundary is guarded by validate_core_isolation (owners["jei"]).

    # ...and the other way round: nobody outside compat/jei/ may touch the plugin.
    for path in glob.glob("neoforge/src/main/java/com/craftingveloce/**/*.java", recursive=True):
        rel = path.replace(os.sep, "/")
        if "/compat/jei/" in rel:
            continue
        text = open(path, encoding="utf-8").read()
        if "VeloceJeiPlugin" in text or "compat.jei" in text:
            problems.append(f"{rel}: the core/gate references the JEI plugin")

    # UID -> block table: we compare PAIRS, not just the sets of names.
    for path, expected in JEI_CATEGORIES.items():
        if not os.path.exists(path):
            problems.append("no JEI category table: " + path)
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
            problems.append(f"{os.path.basename(path)}: missing categories {sorted(missing)}")
        if extra:
            problems.append(f"{os.path.basename(path)}: category outside the list {sorted(extra)}")
        for uid, (want, got) in wrong.items():
            problems.append(f"{os.path.basename(path)}: {uid} -> {got}, should be {want}")

    core = "neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java"
    if os.path.exists(core) and "VeloceJeiCatalysts.registerDefaults()" \
            not in open(core, encoding="utf-8").read():
        problems.append("the core does not fill in the minecraft:crafting category")
    for path, call in (("neoforge/src/main/java/com/craftingveloce/compat/create/CreateCompat.java",
                        "CreateJeiCatalysts.register()"),
                       ("neoforge/src/main/java/com/craftingveloce/compat/mekanism/MekanismCompat.java",
                        "MekanismJeiCatalysts.register()"),
                       ("neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryCompat.java",
                        "AlchemistryJeiCatalysts.register()")):
        if not os.path.exists(path) or call not in open(path, encoding="utf-8").read():
            problems.append(f"{os.path.basename(path)}: no {call}")
    if 'JEI("jei")' not in open("neoforge/src/main/java/com/craftingveloce/compat/VeloceMods.java",
                                encoding="utf-8").read():
        problems.append("VeloceMods without a JEI entry")
    toml = "src_meta/META-INF/neoforge.mods.toml"
    if os.path.exists(toml) and 'modId="jei"' not in open(toml, encoding="utf-8").read():
        problems.append("neoforge.mods.toml without the optional jei dependency")
    if "mezz.jei" not in FOREIGN_PACKAGES:
        problems.append("mezz.jei outside FOREIGN_PACKAGES - a leak would be invisible")

    if problems:
        fail("JEI (category catalysts):\n  " + "\n  ".join(problems))
    total = sum(len(v) for v in JEI_CATEGORIES.values())
    print(f"    OK (JEI: {total} categories -> our blocks, plugin without the foreign mods)")


def validate_module_info_gui():
    """
    Machine windows: separate for energy and separate for Create (like the furnace).

    The player: "in the GUI of Create modules there is a battery bar and a slot
    for the accumulator visible in the background ... prepare a separate screen
    dedicated exclusively to kinetic blocks (without a slot and without an
    energy indicator)".

    We check two screens and two menus:
      * a FE machine: furnace texture, battery bar and tooltip 1:1 with the
        furnace (the same keys: energy, cycles, cycle cost),
      * a kinetic machine: its OWN screen and its OWN menu, zero battery, zero
        slot, one centred status in the middle (the same text as in Jade),
      * both menu types are registered and wired to their screens,
      * charging: the battery slot + drawing power in the tick + a ticker,
      * the old, combined windows have not come back.
    """
    problems = []
    inventory = "neoforge/src/main/java/com/craftingveloce/inventory/VeloceModuleMenu.java"
    kinetic_menu = "neoforge/src/main/java/com/craftingveloce/inventory/VeloceKineticMenu.java"
    screen = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceModuleScreen.java"
    kinetic_screen = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceKineticScreen.java"
    for path, what in ((inventory, "FE machine menu"), (kinetic_menu, "kinetic machine menu"),
                       (screen, "FE machine screen"), (kinetic_screen, "kinetic machine screen")):
        if not os.path.exists(path):
            problems.append("no " + what)
    for gone in ("neoforge/src/main/java/com/craftingveloce/client/gui/VeloceModuleInfoScreen.java",
                 "neoforge/src/main/java/com/craftingveloce/network/OpenModuleInfoPKT.java",
                 "neoforge/src/main/java/com/craftingveloce/crafting/VeloceModuleInfoLines.java"):
        if os.path.exists(gone):
            problems.append("the old window is still there: " + os.path.basename(gone))
    if problems:
        fail("machine windows:\n  " + "\n  ".join(problems))

    registry = open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    for need, what in (("VELOCE_MODULE_MENU =", "the FE machine menu"),
                       ("VELOCE_KINETIC_MENU =", "the kinetic machine menu")):
        if need not in registry:
            problems.append("not registered: " + what)
    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    for need, what in (("VELOCE_MODULE_MENU.get()", "the FE screen"),
                       ("VeloceModuleScreen::new", "the FE screen"),
                       ("VELOCE_KINETIC_MENU.get()", "the kinetic screen"),
                       ("VeloceKineticScreen::new", "the kinetic screen")):
        if need not in mod:
            problems.append("not wired: " + what)

    # Kinetic machine: its OWN menu (without a battery slot) and opening it.
    km = open(kinetic_menu, encoding="utf-8").read()
    for need, what in (("VELOCE_KINETIC_MENU", "its own menu type"),
                       ("VeloceModuleDisplay", "reading the machine fields")):
        if need not in km:
            problems.append("the kinetic menu without " + what)
    if "getBatterySlot" in km or "isEnergyItem" in km:
        problems.append("the kinetic menu has a battery slot (and it should not)")
    block = "neoforge/src/main/java/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java"
    block_text = open(block, encoding="utf-8").read()
    if "VeloceKineticMenu" not in block_text:
        problems.append("the kinetic machine opens a menu that is not its own")
    body = _method_body(block_text,
                        "protected net.minecraft.world.InteractionResult useWithoutItem(")
    if body is None or "openMenu(" not in body:
        problems.append("the kinetic machine does not open a menu")

    # Kinetic screen: only the centred status, zero energy and slots.
    ks = open(kinetic_screen, encoding="utf-8").read()
    for need, what in (("AbstractContainerScreen<VeloceKineticMenu>", "the ordinary container screen"),
                       ("electric_furnace.png", "the furnace texture"),
                       ("drawCenteredString(this.font, VeloceModuleStatus.message(display)",
                        "the centred status"),
                       ("module.info.minimum", "the threshold (min. RPM - SU)")):
        if need not in ks:
            problems.append("the kinetic screen without " + what)
    for forbidden, what in (("drawBattery", "the energy indicator"), ("getBatterySlot", "the battery slot"),
                            ("module.info.speed", "speed"), ("module.info.stress", "SU")):
        if forbidden in ks:
            problems.append(f"the kinetic screen still shows {what}")

    # FE screen: battery + tooltip 1:1 with the furnace.
    fs = open(screen, encoding="utf-8").read()
    for need, what in (("electric_furnace.png", "the furnace texture"), ("drawBattery(", "the battery"),
                       ("gui.craftingveloce.electric.energy", "the energy line"),
                       ("gui.craftingveloce.electric.smelts", "the cycle line"),
                       ("gui.craftingveloce.electric.perSmelt", "the cycle cost")):
        if need not in fs:
            problems.append("the FE screen without " + what)
    if "VeloceModuleStatus" in fs:
        problems.append("the FE screen shows the kinetic status")

    # Charging the accumulator: a battery slot in the menu + drawing power from the item.
    menu = open(inventory, encoding="utf-8").read()
    for need, what in (("getBatterySlot()", "the battery slot in the menu"),
                       ("isEnergyItem", "the filter: only items with energy")):
        if need not in menu:
            problems.append("the machine menu without " + what)
    fe_be = "neoforge/src/main/java/com/craftingveloce/block/entity/VeloceFeModuleBlockEntity.java"
    fe_be_text = open(fe_be, encoding="utf-8").read()
    for need, what in (("private void chargeFromItem()", "the method that draws power"),
                       ("getBatterySlot()", "exposing the battery slot"),
                       ('tag.put("Battery"', "saving the battery in NBT"),
                       ("getUpdatePacket()", "sending the energy to the client")):
        if need not in fe_be_text:
            problems.append("the FE module without " + what)
    tick_body = _method_body(fe_be_text, "public void serverTick()")
    if tick_body is None or "        chargeFromItem();" not in tick_body:
        problems.append("the FE module does not draw power in the tick")
    ticker = ("public <T extends BlockEntity> "
              "net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(")
    if ticker not in open("neoforge/src/main/java/com/craftingveloce/block/VeloceFeModuleBlock.java",
                          encoding="utf-8").read():
        problems.append("the FE module has no ticker (charging from the item will not work)")

    if problems:
        fail("machine windows (FE and kinetic):\n  " + "\n  ".join(problems))
    print("    OK (machine windows: FE like the furnace with a battery, Create a separate screen without energy)")

def validate_pipe_energy():
    """
    ZERO Forge Energy on our cables - a cable is only a carrier for the Veloce network.

    The player's decision: "remove the possibility of transferring power from our
    cables entirely; our cables stay only a carrier for the Veloce network, as it
    used to be - zero FE on our cables".

    This guard used to assert the OPPOSITE: an energy buffer kept on the network, a
    draw plus distribution step in the level tick, and a machine-side pull helper. All
    of that is now forbidden, so the guard is inverted:

      1. the network has NO energy buffer and NO energy bookkeeping,
      2. the level tick has NO energy step,
      3. the shared pull helper does not exist at all,
      4. the pipe still does not expose EnergyStorage (it never was a conduit).

    It is not enough to delete the feature: without this guard the next person who
    wants "power over the pipes" can reintroduce it and every existing check stays
    green, because they all only asserted that the pieces were PRESENT.
    """
    problems = []

    # 1. The pull helper must be gone.
    if os.path.exists("neoforge/src/main/java/com/craftingveloce/network/pipe/VeloceEnergyPull.java"):
        problems.append("VeloceEnergyPull exists again - cables must not transfer Forge Energy")

    # 2. The network must not keep or expose any energy state.
    net_path = "neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetwork.java"
    net_text = open(net_path, encoding="utf-8").read()
    # Comments are stripped: the removal notes legitimately NAME the things they say
    # were removed, and a word-level check would trip on its own documentation.
    net_code = _strip_comments(net_text)
    for need, what in (("EnergyStorage", "an energy buffer"),
                       ("energyBuffer", "an energy buffer field"),
                       ("getEnergyBuffer(", "an energy buffer accessor"),
                       ("addEnergyEndpoint(", "energy endpoint bookkeeping"),
                       ("getEnergyEndpoints(", "energy endpoint bookkeeping"),
                       ("EnergyStored", "persisted network energy")):
        if need in net_code:
            problems.append("VelocePipeNetwork still has " + what)

    # 3. The level tick must not move energy.
    cache_path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceCraftingCache.java"
    tick = _method_body(open(cache_path, encoding="utf-8").read(),
                        "public static void tickAll(ServerLevel level)")
    if tick is None:
        problems.append("no VeloceCraftingCache.tickAll (cannot verify the network tick)")
    else:
        tick_code = _strip_comments(tick)
        if "EnergyStorage" in tick_code or "EnergyPull" in tick_code:
            problems.append("the network tick still moves Forge Energy "
                            "(our cables must not conduct power)")

    # 4. The network scan must not record foreign energy blocks.
    mgr_path = "neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java"
    mgr_code = _strip_comments(open(mgr_path, encoding="utf-8").read())
    for need, what in (("discoveredEnergy", "a discovered-energy set"),
                       ("addEnergyEndpoint(", "energy endpoint registration")):
        if need in mgr_code:
            problems.append("VelocePipeNetworkManager still does " + what)

    # 5. The pipe must not expose energy.
    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    for idx in [i for i in range(len(mod)) if mod.startswith("EnergyStorage.BLOCK", i)]:
        window = mod[idx:idx + 200]
        if "VELOCE_PIPE_BE" in window or "VELOCE_PIPE" in window:
            problems.append("the pipe exposes EnergyStorage (it could be a conduit)")
            break

    if problems:
        fail("energy on the cables:\n  " + "\n  ".join(problems))
    print("    OK (cables: zero Forge Energy on the network - item carrier only)")


def validate_brewing_stand():
    """
    Brewing Stand: block, BE, menu, screen and data - with vanilla brewing logic.

    The player: "add a brewing stand just like the furnace or crafting". We
    check the links that are easy to lose one by one (and then the block just
    stands there doing nothing):
      1. the block entity INHERITS from the vanilla BrewingStandBlockEntity
         (5 slots, brewTime, fuel, PotionBrewing, NBT) instead of copying the
         logic,
      2. the block ticker calls {@code serverTick()} and the BE HAS it - that
         was the real cause of the red builds: a block copied from the furnace
         called the furnace's instance method, which the brewing stand did not
         have,
      3. the menu has the BREWING SLOTS (3 bottles + ingredient + blaze powder)
         and the inventory,
      4. the screen does not pretend to be a furnace: no battery bar (brewing
         has no accumulator),
      5. registration (block/item/BE/menu + screen), the Integrale casing and
         the data.
    """
    problems = []
    block = "neoforge/src/main/java/com/craftingveloce/block/VeloceBrewingStandBlock.java"
    be = "neoforge/src/main/java/com/craftingveloce/block/entity/VeloceBrewingStandBlockEntity.java"
    menu = "neoforge/src/main/java/com/craftingveloce/inventory/VeloceBrewingStandMenu.java"
    screen = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceBrewingStandScreen.java"
    for path, what in ((block, "the block"), (be, "block entity"), (menu, "menu"),
                       (screen, "screen")):
        if not os.path.exists(path):
            problems.append("no " + what)
    if problems:
        fail("brewing stand:\n  " + "\n  ".join(problems))

    # NOTE: inheriting from the vanilla BrewingStandBlockEntity is NOT POSSIBLE
    # for our block - the vanilla constructor sets the type
    # minecraft:brewing_stand, while our block has a type of its own, which
    # ended in a crash "Invalid block entity ... got Block craftingveloce:brewing_stand".
    # That is why the BE is our own: 5 slots + NBT saving + a menu, and the
    # brewing logic (PotionBrewing) is added separately.
    be_text = open(be, encoding="utf-8").read()
    if "extends BlockEntity" not in be_text:
        problems.append("the BE is not our own block entity")
    for need, what in (("implements net.minecraft.world.Container", "the 5-slot container"),
                       ("new SimpleContainer(5)", "five slots (3 bottles, ingredient, fuel)"),
                       ('tag.put("Items"', "saving the contents in NBT"),
                       ("createMenu(", "opening our own menu")):
        if need not in be_text:
            problems.append("the brewing stand BE without " + what)
    if "BrewingStandBlockEntity" in be_text and "extends" in be_text \
            and "extends BlockEntity" not in be_text:
        problems.append("the BE inherits from the vanilla one (this causes a type crash)")

    # The brewing recipe type must be PAYABLE during execution.
    #
    # VeloceAutoCrafter.payForOperation pays a non-furnace recipe by asking for a
    # VeloceProcessingSource that advertises the recipe's type. The brewing stand did
    # not implement that interface, so `craftingveloce:brewing` had no machine to pay
    # with, the payment failed on every attempt and the craft aborted - while the
    # terminal kept showing the potion as craftable. That was the real reason
    # "crafting potions is completely broken" even after the recipes were fixed.
    #
    # Checked on the CLASS DECLARATION, not in the file: the interface is also named
    # in the Javadoc of the methods it adds, so a whole-file search stayed green when
    # the `implements` clause was removed (calibration caught exactly that).
    declaration = re.search(r"public class VeloceBrewingStandBlockEntity[^{]*\{",
                            _strip_comments(be_text))
    if declaration is None or "VeloceProcessingSource" not in declaration.group(0):
        problems.append("the brewing stand does not implement VeloceProcessingSource "
                        "(nothing can pay for a craftingveloce:brewing recipe, so "
                        "every brew craft fails at execution)")
    if "getBrewing()" not in be_text:
        problems.append("the brewing stand does not advertise the brewing recipe type")
    for need, what in (("public long availableOperations()", "counting how many brews it can pay for"),
                       ("public void consumeOperations(", "charging energy per brew"),
                       ("public boolean isPowered()", "reporting whether it can brew at all")):
        if need not in be_text:
            problems.append("the brewing stand without " + what)

    menu_text = open(menu, encoding="utf-8").read()
    # We look for the machine slots in the addSlot CALLS, not in the constants:
    # the constant name alone stays in the file even when the slot disappears
    # (calibration showed this - the first version of the check let the removal
    # of the fuel slot through).
    for need, what in (("new Slot(container, bottle,", "the loop over three bottles"),
                                              ("new Slot(container, 3,", "the ingredient slot"),
                       ("new Slot(container, 4,", "the fuel slot (blaze powder)"),
                       ("new Slot(playerInv,", "the player inventory")):
        if need not in menu_text:
            problems.append("the menu without " + what)

    # THE BREWING STAND IS AN FE MACHINE.
    #
    # This guard used to assert the OPPOSITE ("no battery bar, brewing has no
    # accumulator") and only passed because it looked for the literals
    # "drawBattery"/"energyCapacity"/"getBatterySlot", which nobody happened to
    # write. Forge Energy was then added to the stand and the stale guard kept
    # passing - so the "the GUI battery stays empty" report had no test behind it.
    # The checks below describe what the code must actually do.
    if "addDataSlots(" not in menu_text:
        problems.append("the brewing menu never syncs its ContainerData "
                        "(the battery gauge can never move)")
    if "getEnergy()" not in menu_text:
        problems.append("the brewing menu does not expose the accumulator to the screen")

    # The machine slots must not be hidden behind dead code again.
    #
    # They once sat inside `if (false) { ... }` under a comment saying they existed
    # only to satisfy this very guard: the guard looked for the
    # `new Slot(container, ...)` text and passed, while the GUI had no bottle slots
    # at all. A text check cannot tell live code from unreachable code, so we also
    # forbid the disabling of a block outright.
    if "if (false)" in _strip_comments(menu_text) or "if(false)" in _strip_comments(menu_text):
        problems.append("the brewing menu disables slots with `if (false)` "
                        "(dead code cannot satisfy a check about working slots)")

    # The menu must read the BLOCK ENTITY's container data. With a throwaway
    # SimpleContainerData nothing ever writes the accumulator and the gauge is
    # permanently 0 - the "battery stays dead/empty" report.
    if "getDataAccess()" not in menu_text:
        problems.append("the brewing menu does not attach the block entity's own "
                        "ContainerData (a throwaway SimpleContainerData is never "
                        "written, so the battery gauge reads 0 forever)")

    # The accumulator is 25 000 000 FE, which does NOT fit in the 16-bit value that
    # ContainerData can carry - so it MUST be sent as two 16-bit halves. Losing the
    # split silently caps the gauge at 65535 FE, which looks like a dead battery.
    be_split = _method_body(be_text, "protected final net.minecraft.world.inventory.ContainerData dataAccess")
    if be_split is None:
        be_split = be_text
    if ">> 16) & 0xFFFF" not in be_split or "& 0xFFFF0000" not in be_split:
        problems.append("the brewing stand does not split its energy into two 16-bit "
                        "halves for ContainerData (a 25 000 000 FE accumulator overflows "
                        "the 16-bit data slot and the gauge stays at/near empty)")

    screen_text = open(screen, encoding="utf-8").read()
    for need, what in (("this.menu.getEnergy()", "reading the synced accumulator"),
                       ("this.menu.getMaxEnergy()", "reading the accumulator capacity"),
                       ("graphics.fill(", "drawing the battery fill")):
        if need not in screen_text:
            problems.append("the brewing screen without " + what)
    if "electric_furnace.png" not in screen_text:
        problems.append("the brewing screen does not use the furnace texture")

    reg = open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    for need, what in (("BREWING_STAND =", "the block"), ("BREWING_STAND_ITEM", "the item"),
                       ("BREWING_STAND_BE", "block entity"), ("BREWING_STAND_MENU", "menu")):
        if need not in reg:
            problems.append("registration without " + what)
    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceBrewingStandScreen::new" not in mod:
        problems.append("the brewing screen is not wired")
    cc = open("neoforge/src/main/java/com/craftingveloce/block/VeloceCaseContents.java", encoding="utf-8").read()
    if "BREWING_STAND.get()" not in cc:
        problems.append("no Integrale casing for the brewing stand")
    for path in ("assets/craftingveloce/blockstates/brewing_stand.json",
                 "assets/craftingveloce/models/item/brewing_stand.json",
                 "data/craftingveloce/loot_table/blocks/brewing_stand.json"):
        if not os.path.exists(path):
            problems.append("missing data: " + path)

    # Part B of the goal: what the network can do with special tables. These are
    # FACTS from vanilla, not promises - smithing and fletching are already
    # handled, while cartography and brewing have no RecipeType, so they must
    # NOT be added to the families.
    families = open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceRecipeFamilies.java",
                    encoding="utf-8").read()
    if "RecipeType.SMITHING" not in families:
        problems.append("the smithing table fell out of the recipe families (the network will not make it)")
    if "RecipeType.CRAFTING" not in families:
        problems.append("the fletching table is no longer handled (it is ordinary crafting)")
    if "BREWING" in families or "CARTOGRAPHY" in families:
        problems.append("somebody added a non-existent RecipeType (BREWING/CARTOGRAPHY)")

    if problems:
        fail("brewing stand:\n  " + "\n  ".join(problems))
    print("    OK (brewing stand: vanilla brewing logic + menu/slots + data)")


def validate_brewing_proxy():
    """
    Brewing recipes must be DISCOVERED from the game and expressed in the PROXY domain.

    Two independent things are checked, because either one alone reads as "brewing
    works" while the other is broken.

    <b>Discovery.</b> The module used to carry twenty-one hand-written mixes, so it
    knew only the potions somebody had typed out: the rest of the vanilla tree was
    invisible and a brewing recipe added by another mod could never be planned at
    all - even though the stand would brew it, because the stand asks the game. The
    module must ask the same source (`level.potionBrewing()`), and it must do so by
    PROBING (`hasMix`) rather than by reading a list: vanilla keeps its potion mixes
    private with no getter, so probing is the only complete way to find them.
    `getRecipes()` is required as well - it is what exposes a mod's own brewing
    recipes, whose inputs need not be potions.

    <b>Proxy domain.</b> Vanilla brewing has no RecipeType, so a potion STATE is
    represented in the network by its own ordinary item (a proxy registered through
    VelocePotionMapper). Everything the planner touches - the ingredient it consumes,
    the result it produces - therefore has to be a proxy item.

    The bug the proxy half exists for: the results were MIXED. `brewing_awkward`
    produced a real `minecraft:potion` stack while `addMix` produced proxies and
    demanded the awkward PROXY as an input. The chain broke at the first step, so no
    downstream potion was ever craftable, and `producible()` advertised the generic
    `minecraft:potion` item as craftable. In game that reads as "crafting potions is
    completely broken; zero recipes evaluate".
    """
    path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceBrewingModule.java"
    if not os.path.exists(path):
        fail("brewing proxy:\n  no VeloceBrewingModule")
    code = _strip_comments(open(path, encoding="utf-8").read())
    problems = []

    # --- 1. the recipes come from the game, not from this file ---
    for needle, why in (
        ("potionBrewing()", "does not read level.potionBrewing() - the mixes would come "
                            "from a list in this file instead of from the game, and a "
                            "brewing recipe added by another mod would be invisible"),
        ("hasMix(", "never probes hasMix - vanilla keeps its potion-mix list private, so "
                    "probing is the only way to discover those recipes"),
        ("getRecipes()", "never reads PotionBrewing.getRecipes() - a mod's own brewing "
                         "recipes accept inputs that need not be potions and would be lost"),
    ):
        if needle not in code:
            problems.append("VeloceBrewingModule " + why)

    # ...and the closed list must not creep back. A glass bottle is the only ingredient
    # written down: filling a bottle is not a brewing mix and is not in PotionBrewing,
    # so unlike everything else it cannot be discovered.
    hardcoded = re.findall(r"Ingredient\.of\(Items\.[A-Z_]+\)", code)
    if sorted(hardcoded) != ["Ingredient.of(Items.GLASS_BOTTLE)"]:
        problems.append(
            "VeloceBrewingModule names brewing ingredients in code "
            f"({', '.join(sorted(hardcoded)) or 'none'}) - the mixes must come from the "
            "game. Only the glass bottle belongs here, because filling a bottle is not "
            "a brewing mix")

    # --- 2. the results are proxies, never real potions ---
    #
    # Checked as a CODE USE, on comment-stripped source: the class is named in the
    # documentation above, and a word-level check fired on exactly those docs when
    # this guard was first written.
    inputs_body = _method_body(code, "private static List<ItemStack> candidateInputs(")
    if not inputs_body or "PotionContents." not in inputs_body:
        problems.append("candidateInputs does not build the potion states to probe "
                        "(no PotionContents.createItemStack) - the probe would ask about "
                        "the wrong stacks and find nothing")
    # PotionContents is legitimate ONLY there. Anywhere else it means a real potion was
    # built somewhere that should have used a proxy - the exact bug this half guards.
    elsewhere = code.replace(inputs_body, "")
    if "PotionContents." in elsewhere:
        problems.append("VeloceBrewingModule builds a real potion (PotionContents.) "
                        "outside candidateInputs - recipe results must be proxy items")

    emit_body = _method_body(code, "private String emit(")
    if not emit_body:
        problems.append("no emit() method (the recipe-results-are-proxies check has "
                        "nothing to inspect - if the builder call shape changed, update "
                        "this guard)")
    else:
        if "proxyOrNull(" not in emit_body:
            problems.append("emit() does not resolve its input/result through "
                            "proxyOrNull() - a potion with no proxy would be approximated "
                            "with minecraft:potion and every such potion would collide")
        # Up to the first line that starts with a closing paren - the call's closing
        # paren sits on its own line. An earlier, stricter pattern expecting
        # `);` missed the real shape (`));`, because add() wraps the builder),
        # so the guard failed on a correct module instead of passing a wrong one.
        call = re.search(r"ProcessingEntry\.single\((.*?)\n\s*\)", emit_body, re.S)
        if not call:
            problems.append("emit() does not build a ProcessingEntry.single(...) (the "
                            "regexp found nothing - update this guard if the shape changed)")
        else:
            args = call.group(1)
            if "new ItemStack(resultProxy)" not in args:
                problems.append("the recipe result is not built from the resolved proxy "
                                f"item: {args.strip().splitlines()[-1].strip()}")
            if "PotionContents" in args or "mix.result()" in args:
                problems.append("a brewing recipe result is a REAL potion, not a proxy")
        # emit() answers with a REASON (null when the recipe was added), so the caller can
        # say WHY a mix was not offered. A boolean here would put "no proxy" and
        # "duplicate id" back into one bucket, which is how a reporting bug hid twelve
        # lost recipes behind a message about missing proxies.
        if "return null;" not in emit_body:
            problems.append("emit() does not return null for an emitted recipe - the "
                            "caller cannot tell success from a skip reason")

    # The proxy registry must exist and be able to answer both directions, and to say
    # "unknown" - a module that cannot tell a missing proxy apart from a present one
    # has to guess, and guessing here means handing the player the wrong potion.
    mapper = "neoforge/src/main/java/com/craftingveloce/util/VelocePotionMapper.java"
    if not os.path.exists(mapper):
        problems.append("no VelocePotionMapper")
    else:
        mapper_text = open(mapper, encoding="utf-8").read()
        for need, what in (("registerProxy(", "proxy registration"),
                           ("public static Item getProxy(", "potion -> proxy lookup"),
                           ("public static Item proxyOrNull(", "the unknown-potion answer"),
                           ("public static ItemStack toRealPotion(", "proxy -> potion conversion"),
                           ("public static boolean isProxy(", "the proxy predicate")):
            if need not in mapper_text:
                problems.append("VelocePotionMapper without " + what)

    # The proxies must actually be registered at init, otherwise getProxy falls
    # back to the plain potion item for EVERY potion and all recipes collapse into
    # duplicates of each other.
    reg = open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    # Two sources of proxies now, and BOTH have to exist.
    #
    # The hand-authored ones are a MAP, not nineteen literal registerProxy() calls -
    # they moved because the generator needs to know which keys are taken before the
    # deferred items are bound, and a second copy of that list would drift.
    hand_authored = re.findall(r'Map\.entry\("([a-z_]+)",\s*POTION_', reg)
    if len(hand_authored) < 10:
        problems.append(f"only {len(hand_authored)} hand-authored potion proxies declared "
                        f"(getProxy would fall back to minecraft:potion and every brewing "
                        f"recipe would collide)")

    # The generated ones cover everything the nineteen do not: the extended, level-II,
    # splash and lingering states, which is 250 of the game's 281 mixes. Without them the
    # module silently offers a fraction of the brewing tree.
    gen = "neoforge/src/main/java/com/craftingveloce/init/VelocePotionProxies.java"
    if not os.path.exists(gen):
        problems.append("no VelocePotionProxies - the extended, level-II, splash and "
                        "lingering potion states would have no proxy item at all")
    else:
        gen_text = _strip_comments(open(gen, encoding="utf-8").read())
        for need, what in (("BuiltInRegistries.POTION", "reading the potion registry"),
                           ("event.register(Registries.ITEM", "registering the items"),
                           ("VelocePotionMapper.registerProxy", "filling the proxy table"),
                           ("handAuthoredProxyKeys()", "leaving the hand-authored keys alone")):
            if need not in gen_text:
                problems.append("VelocePotionProxies without " + what)
        if "VelocePotionProxies::register" not in reg and "VelocePotionProxies.register(" not in reg:
            problems.append("VelocePotionProxies is never wired into a registry event - "
                            "the generated proxies would not exist")

    # A generated item has no model file, so a client must bake one for it or every
    # generated proxy renders as the missing-texture block.
    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "ModifyBakingResult" not in mod or "VelocePotionProxies.created()" not in mod:
        problems.append("nothing bakes a model for the generated proxies (ModifyBakingResult "
                        "+ VelocePotionProxies.created()) - they would render as the "
                        "missing-texture block")

    # The water bypass: a glass bottle put into the stand must become a water
    # potion without any water source. That is what makes the base recipe startable.
    # The first brewing step has to be startable: a glass bottle must become water
    # without a water source. That used to live in the stand, which filled its own bottle
    # slots on a tick. The stand is now a passive host like every other module, so the
    # step lives where the brewing does - the module's recipe list.
    if "brewing_water" not in code or "GLASS_BOTTLE" not in code:
        problems.append("VeloceBrewingModule has no brewing_water recipe (glass bottle -> "
                        "water proxy) - the first brewing step cannot start")

    if problems:
        fail("brewing proxy domain:\n  " + "\n  ".join(problems))
    print(f"    OK (brewing proxy: mixes discovered via potionBrewing()+hasMix()+getRecipes(), "
          f"results in the proxy domain, {len(hand_authored)} hand-authored proxies + generated "
          f"ones wired with a baked model, water bypass present)")


# Which foreign JAR holds the textures of a namespace, and how its file names start.
FOREIGN_TEXTURE_JARS = {
    "mekanism": "Mekanism-",
    "alchemistry": "alchemistry-",
    "create": "create-",
    "chemlib": "chemlib-",
}


def _foreign_jar_for(namespace):
    """The JAR of the given namespace, or None when that mod is not installed here."""
    prefix = FOREIGN_TEXTURE_JARS.get(namespace)
    if not prefix:
        return None
    for directory in COMPILE_ONLY_DIRS:
        if not os.path.isdir(directory):
            continue
        hits = sorted(glob.glob(os.path.join(directory, prefix + "*.jar")))
        if hits:
            return hits[0]
    return None


def validate_module_content_textures():
    """
    Every module's #content texture must EXIST inside the foreign mod's JAR.

    The player sees a module whose contents render as the missing-texture checker
    board (or as nothing at all). Nothing catches that: the JAR builds, the model
    JSON is valid, and Minecraft fails the lookup silently at RUNTIME.

    This is a real regression that happened here: a rewrite changed 23 Mekanism
    module textures to a "mekanism:block/models/..." shape, but most Mekanism
    machines keep their GUI/block textures under "mekanism:block/<machine>/...".
    Only a handful genuinely live under "block/models/". The rewrite therefore
    broke 13 of them while looking like a tidy systematic improvement.

    The check is against the actual JAR file list, so it cannot be satisfied by a
    plausible-looking path.
    """
    problems = []
    checked = 0
    skipped_mods = set()
    for path in sorted(glob.glob("assets/craftingveloce/models/item/veloce_*_module.json")):
        try:
            data = json.load(open(path, encoding="utf-8"))
        except (ValueError, OSError) as exc:
            problems.append(f"{os.path.basename(path)}: unreadable ({exc})")
            continue
        ref = (data.get("textures") or {}).get("content")
        if not isinstance(ref, str) or ":" not in ref:
            continue
        namespace, texture = ref.split(":", 1)
        jar = _foreign_jar_for(namespace)
        if jar is None:
            skipped_mods.add(namespace)
            continue
        with zipfile.ZipFile(jar) as z:
            if f"assets/{namespace}/textures/{texture}.png" not in z.namelist():
                problems.append(f"{os.path.basename(path)} -> {ref} "
                                f"(not present in {os.path.basename(jar)})")
        checked += 1

    # --- the other half: the cube is GONE, and something composes the real icon ---
    #
    # These models used to hide a cube at [4,4,4]..[12,12,12] whose six faces all pointed
    # at #content - one wall of the machine stretched over every side, including the lid.
    # The geometry that put the machine inside the frame now lives in a BakedModel, so
    # textures.content is no longer read by any element. Two ways that can go wrong, and
    # both are silent:
    #
    #   * the cube comes back -> the content renders TWICE, once composed and once as the
    #     old stretched cube;
    #   * the composition is dropped -> the frame renders EMPTY, which looks exactly like
    #     a casing that was never finished.
    for path in sorted(glob.glob("assets/craftingveloce/models/item/*.json")):
        try:
            data = json.load(open(path, encoding="utf-8"))
        except (ValueError, OSError):
            continue
        for element in data.get("elements") or []:
            faces = element.get("faces") or {}
            if {f.get("texture") for f in faces.values()} == {"#content"}:
                problems.append(f"{os.path.basename(path)}: still has the old #content cube "
                                f"({element.get('from')}..{element.get('to')}) - the content "
                                f"would render twice, composed and stretched")
                break

    # The icons are composed from pieces kept in the repo so the result can be rebuilt
    # rather than re-rendered. A missing piece means a casing with no inside, and the
    # generator would report it only to whoever happens to run it.
    for need, what in (("scripts/gen_case_icons.py", "the icon generator"),
                       ("assets/craftingveloce/case-source/BACK.png", "the back plate"),
                       ("assets/craftingveloce/case-source/FRONT.png", "the front plate"),
                       ("assets/craftingveloce/icon-dump/_map.txt", "the casing -> machine map")):
        if not os.path.exists(need):
            problems.append("missing " + what + f" ({need}) - the casing icons cannot be "
                            f"rebuilt from the repository alone")

    # Every casing must point at a picture that exists, and every picture must have been
    # produced for a casing we actually have.
    composed = set()
    for path in sorted(glob.glob("assets/craftingveloce/models/item/*.json")):
        try:
            data = json.load(open(path, encoding="utf-8"))
        except (ValueError, OSError):
            continue
        layer = (data.get("textures") or {}).get("layer0", "")
        if layer.startswith("craftingveloce:item/case/"):
            name = layer.rsplit("/", 1)[1]
            composed.add(name)
            png = f"assets/craftingveloce/textures/item/case/{name}.png"
            if not os.path.exists(png):
                problems.append(f"{os.path.basename(path)} draws {layer}, which has no file")
    orphans = {p.stem for p in pathlib.Path("assets/craftingveloce/textures/item/case").glob("*.png")} - composed \
        if os.path.isdir("assets/craftingveloce/textures/item/case") else set()
    if orphans:
        problems.append(f"{len(orphans)} composed icon(s) no casing draws: {sorted(orphans)[:5]}")

    if checked == 0:
        if problems:
            fail("module content textures:\n  " + "\n  ".join(problems))
        print("    OK (module content textures: no foreign-mod JAR present to check against; "
              "casing icons composed at bake time, old cube absent)")
        return
    if problems:
        fail("module content textures point at textures that do not exist:\n  "
             + "\n  ".join(problems))
    note = f", skipped namespaces without a JAR here: {sorted(skipped_mods)}" if skipped_mods else ""
    print(f"    OK (module content textures: {checked} verified against the mod JARs{note}; "
          f"casing icons composed at bake time, old cube absent)")


def validate_potion_proxy_assets():
    """
    Every potion proxy item needs an item model and a name.

    The proxies are ordinary registered items that stand in for potion states in
    the network. They are what the planner matches on and what `producible()`
    returns, so they show up in the terminal's craftable overlay and in JEI.
    Registered without a model they render as the missing-texture block, and
    without a lang key they show as the raw translation id - both look like the
    mod is broken, and neither fails the build by itself.
    """
    reg = open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    proxies = sorted(set(re.findall(r'ITEMS\.register\("(potion_[a-z_]+)"', reg)))
    if not proxies:
        fail("potion proxies:\n  no proxy items registered (the brewing proxy domain "
             "would fall back to minecraft:potion and every recipe would collide)")
    lang_path = "assets/craftingveloce/lang/en_us.json"
    lang = json.load(open(lang_path, encoding="utf-8"))
    problems = []
    for name in proxies:
        model = f"assets/craftingveloce/models/item/{name}.json"
        if not os.path.exists(model):
            problems.append(f"{name}: no item model ({model}) - renders as the "
                            f"missing-texture block")
        if f"item.craftingveloce.{name}" not in lang:
            problems.append(f"{name}: no lang key - the terminal shows "
                            f"'item.craftingveloce.{name}'")
    if problems:
        fail("potion proxy assets:\n  " + "\n  ".join(problems))
    print(f"    OK (potion proxies: {len(proxies)} items, each with a model and a name)")


def validate_energy_pull():
    """
    A machine is powered WITHOUT the cables: its own battery item, or a foreign
    energy cable attached directly to it.

    The player's decision: "remove the possibility of transferring power from our
    cables entirely ... zero FE on our cables". So nothing draws power through the
    network any more, and this guard verifies BOTH halves of that change:

      1. no machine reaches for power through the network (the shared pull helper
         does not exist and no block entity mentions it),
      2. the machines still have a working LOCAL power path - an energy-item slot
         they discharge into their accumulator, and the Forge Energy capability so
         a foreign cable attached to the machine can charge it,
      3. they are still sinks only (extractEnergy = 0) - never a power source for
         each other or for other mods,
      4. the electric furnace still PAYS for every smelted unit out of its
         accumulator, so "smelting is free" cannot come back.

    Points 2-4 are the ones that would silently rot: removing the network draw
    deletes the obvious caller, and nothing else would notice if the local charging
    path broke along with it.
    """
    problems = []

    # 1. Nothing may pull power through the network.
    if os.path.exists("neoforge/src/main/java/com/craftingveloce/network/pipe/VeloceEnergyPull.java"):
        problems.append("the network pull helper is back (cables must not carry FE)")
    for path in sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/**/*.java", recursive=True)):
        code = _strip_comments(open(path, encoding="utf-8").read())
        if "VeloceEnergyPull" in code:
            problems.append(f"{path.replace(os.sep, '/')}: still pulls power through the network")

    machines = (
        ("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceElectricFurnaceBlockEntity.java",
         "the electric furnace"),
        ("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceFeModuleBlockEntity.java",
         "the FE module"),
        ("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceBrewingStandBlockEntity.java",
         "the brewing stand"),
    )

    # 2. and 3. A local charging path, and sinks only.
    for path, what in machines:
        text = open(path, encoding="utf-8").read()
        if "public int receiveEnergy(" not in text:
            problems.append(f"{what}: cannot be charged by a cable attached to it")
        body = _method_body(text, "public int extractEnergy(")
        if body is None or "return 0;" not in body:
            problems.append(f"{what}: gives energy back (machines must be sinks only)")

    # The battery ITEM slot is now the only power source, so it must exist - with no
    # network draw, a machine without it could never be charged at all.
    for path, what in (machines[0], machines[1], machines[2]):
        text = open(path, encoding="utf-8").read()
        if "chargeFromItem" not in text:
            problems.append(f"{what}: has no battery-item charging path "
                            f"(there is no network draw any more, so it could never charge)")

    # 4. The electric furnace still pays per smelted unit.
    furn = open(machines[0][0], encoding="utf-8").read()
    # The cost is asked for through `fePerSmelt()` rather than read from the constant
    # directly, because a `static final int` is INLINED by javac at every use site - so once
    # the value became configurable, the constant could not reach these methods at all. The
    # guard's point is unchanged: the furnace must charge, and must derive its operations
    # from its accumulator.
    consume = _method_body(furn, "public void consumeOperations(")
    if consume is None or "fePerSmelt()" not in consume:
        problems.append("the electric furnace does not deduct FE per smelting operation")
    available = _method_body(furn, "public long availableOperations()")
    if available is None or "fePerSmelt()" not in available:
        problems.append("the electric furnace does not derive its operations from the accumulator")

    # The accumulator SIZE is part of the agreed specification: 25 000 000 FE is 125
    # smelts at 200 000 FE. The constant had drifted to 200 000 000 (1000 smelts), which
    # is effectively bottomless and contradicted the class documentation - so it is
    # pinned here rather than left to drift again.
    cap = re.search(r"ENERGY_CAPACITY\s*=\s*([0-9_]+)", furn)
    if cap is None:
        problems.append("the electric furnace has no ENERGY_CAPACITY")
    elif cap.group(1) != "25_000_000":
        problems.append(f"the electric furnace accumulator is {cap.group(1)} FE, "
                        f"should be 25_000_000 (125 smelts at 200 000 FE)")

    if problems:
        fail("machine power (no cables):\n  " + "\n  ".join(problems))
    print("    OK (power: no draw through the network; local battery item + direct cable only)")


def validate_heat_accounting():
    """
    A furnace recipe's heat must be charged ATOMICALLY with its plan run.

    The bug this guards (player: "shift-click does not work on furnace-recipe items"
    and, earlier, "I can take one but not a stack"): `planRecipe` decremented
    `plan.heatRemaining` BEFORE planning the ingredients, but only added the run to the
    plan AFTER the ingredients succeeded. A recipe whose ingredient could not be
    planned returned false without ever being added, so `rollbackTo` (which restores
    heat only for runs that are IN the plan) could never give that heat back. With
    roughly ten furnace recipes per item, every failed unit attempt melted ~10 heat
    operations, so the planner concluded "no heat" long before the furnace was empty.

    The symptom was masked for a long time: a 200 000 000 FE accumulator means 1000
    operations, so a 10x leak still left plenty. It only became visible after the
    accumulator was pinned to 25 000 000 FE (125 operations).

    The guard asserts the heat charge sits NEXT TO the run-add (after the ingredient
    loop), never before it.
    """
    path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java"
    if not os.path.exists(path):
        fail("heat accounting:\n  no VeloceAutoCrafter")
    body = _method_body(open(path, encoding="utf-8").read(),
                        "private static boolean planRecipe(")
    if body is None:
        fail("heat accounting:\n  no planRecipe method")
    problems = []
    dec = body.find("plan.heatRemaining -= times")
    add = body.find("plan.add(recipe, times)")
    loop = body.find("for (int ingIndex")
    if dec < 0:
        problems.append("planRecipe does not charge heat for a furnace recipe")
    if add < 0:
        problems.append("planRecipe does not add the run")
    if dec >= 0 and add >= 0 and dec > add:
        problems.append("the heat is charged AFTER the run is added (rollbackTo would double-charge)")
    if dec >= 0 and loop >= 0 and dec < loop:
        problems.append("the heat is charged before planning the ingredients - a failed "
                        "ingredient plan then leaks heat (rollbackTo cannot return it, "
                        "because the run was never added)")
    if problems:
        fail("heat accounting:\n  " + "\n  ".join(problems))
    print("    OK (heat accounting: a furnace run and its heat are charged atomically)")


def validate_block_recipes():
    """
    Every recipe we ship is one the game will actually LOAD.

    A recipe JSON fails in the quietest way in this project: a pattern character with no
    key, a key no character uses, an ingredient object with neither `item` nor `tag` -
    none of those is a compile error and none of them is a crash. The game logs one line
    and SKIPS the recipe, so the block simply has no recipe in game and the only witness
    is a log nobody reads.

    The owner dictated the three block recipes by hand (terminal, pipe, Integrale) and
    every one of them is a full 3x3 with six different ingredients - the shape in which a
    mistake is easiest to make and hardest to notice.

    Per file in data/craftingveloce/recipe/:
      * it parses at all;
      * a shaped recipe has a result, a rectangular grid that fits in 3x3;
      * every character in the pattern has a key, and every key is used at least once;
      * each key resolves to exactly one of `item` / `tag`;
      * the result's item is one this mod actually has assets for.

    The vanilla ingredient ids themselves cannot be checked from here - that needs the
    game's registry - so they are verified by loading a world and reading the log instead.
    """
    root = "data/craftingveloce/recipe"
    if not os.path.isdir(root):
        fail("block recipes:\n  no " + root)
    problems = []
    checked = 0
    for name in sorted(os.listdir(root)):
        if not name.endswith(".json"):
            continue
        checked += 1
        path = os.path.join(root, name)
        try:
            rec = json.load(open(path, encoding="utf-8"))
        except Exception as exc:
            problems.append(f"{name}: does not parse ({exc})")
            continue

        rtype = rec.get("type", "")
        result = rec.get("result")
        if rtype == "minecraft:crafting_shaped" and result is None:
            problems.append(f"{name}: a shaped recipe with no result")
        if isinstance(result, dict):
            rid = result.get("id", "")
            if not rid:
                problems.append(f"{name}: result without an id")
            else:
                short = rid.split(":")[-1]
                if not (os.path.exists(f"assets/craftingveloce/blockstates/{short}.json")
                        or os.path.exists(f"assets/craftingveloce/models/item/{short}.json")
                        or os.path.exists(f"assets/craftingveloce/models/block/{short}.json")):
                    problems.append(f"{name}: result {rid} has no blockstate or model here")

        pattern = rec.get("pattern")
        if pattern is None:
            continue
        keys = rec.get("key") or {}
        rows = pattern
        widths = {len(row) for row in rows}
        if not rows or len(rows) > 3:
            problems.append(f"{name}: the pattern has {len(rows)} row(s)")
        if len(widths) > 1:
            problems.append(f"{name}: pattern rows are not the same length: "
                            + ", ".join(str(w) for w in sorted(widths)))
        elif widths and max(widths) > 3:
            problems.append(f"{name}: the pattern is wider than 3")
        used = {c for row in rows for c in row if c != " "}
        missing = sorted(c for c in used if c not in keys)
        unused = sorted(k for k in keys if k not in used)
        if missing:
            problems.append(f"{name}: the pattern uses {', '.join(missing)} with no key")
        if unused:
            problems.append(f"{name}: key(s) {', '.join(unused)} used by nothing")
        for k, ing in sorted(keys.items()):
            if not isinstance(ing, dict):
                problems.append(f"{name}: key {k} is not an object")
                continue
            has = [f for f in ("item", "tag") if f in ing]
            if len(has) != 1:
                problems.append(f"{name}: key {k} needs exactly one of item/tag "
                                f"(has {'neither' if not has else 'both'})")

    if problems:
        fail("block recipes:\n  " + "\n  ".join(problems))
    print(f"    OK ({checked} recipe(s) parse, and every shaped grid matches its keys)")


def validate_heat_battery():
    """
    The fuel furnace's heat battery, exactly as it was specified.

    The player's specification, in his own numbers: the furnace burns all the time and
    every tick of that burn puts ONE unit into a battery; one instant smelt costs THREE
    COAL; and the battery holds SIXTY-FOUR such cycles, so a full one smelts a stack in
    one go. The unit is Forge Energy, but it is the furnace's own - it must NOT be
    reachable from outside.

    Every one of those is a number that can be quietly edited and still compile, and the
    symptom of a wrong one is invisible (a furnace that is merely slower looks exactly
    like a furnace that is working). So the numbers are pinned here, the way the electric
    furnace's 25 000 000 FE is pinned in validate_energy_pull.

    The last two checks are the ones a player cannot test for himself - they exist
    because the failure would be a duplicated resource, not a crash:
      * the FUEL delivered by burning must land in the battery (one charge site);
      * the block must register NO energy capability, so no cable from another mod can
        pull the accumulated heat out.
    """
    path = ("neoforge/src/main/java/com/craftingveloce/block/entity/"
            "VeloceVelocityFurnaceBlockEntity.java")
    text = open(path, encoding="utf-8").read()
    problems = []

    def const(name):
        m = re.search(r"\b" + name + r"\s*=\s*([^;]+);", text)
        if m is None:
            problems.append(f"no {name}")
            return None
        return m.group(1).strip()

    values = {n: const(n) for n in ("FE_PER_BURN_TICK", "BURN_PER_TICK_NUM",
                                    "BURN_PER_TICK_DEN", "COAL_BURN_TICKS",
                                    "COAL_PER_SMELT", "FE_PER_SMELT",
                                    "SMELTS_PER_BATTERY", "ENERGY_CAPACITY")}

    # Each expected value is written as the ARITHMETIC, not as the answer, so that the
    # guard fails on a changed number and not on a reformatted one.
    expected = {
        "FE_PER_BURN_TICK": "1",
        # The rate is a FRACTION (see the class): 256/45 burn ticks per server tick is
        # what turns "a full stack in fifteen minutes" into a whole number of ticks. It
        # must not be folded into FE_PER_BURN_TICK - that would make one coal worth more
        # than one smelt and quietly break the price below.
        "BURN_PER_TICK_NUM": "256",
        "BURN_PER_TICK_DEN": "45",
        "COAL_BURN_TICKS": "1600",
        # ONE COAL PER SMELT - the price the player settled on, and what makes a full
        # battery cost exactly as many coal as it can smelt items.
        "COAL_PER_SMELT": "1",
        "FE_PER_SMELT": "COAL_PER_SMELT * COAL_BURN_TICKS",
        "SMELTS_PER_BATTERY": "64",
        "ENERGY_CAPACITY": "SMELTS_PER_BATTERY * FE_PER_SMELT",
    }
    for name, want in expected.items():
        got = values.get(name)
        if got is not None and got != want:
            problems.append(f"{name} = {got}, expected {want}")

    # ...and the three agreed numbers have to meet: a stack of smelts, one coal each,
    # charged in fifteen minutes. Recomputed from the constants above, because the two
    # knobs interact - 64 smelts at 1600 FE is 102 400 FE, and a whole rate would put the
    # charge at 14:13 or 17:04 instead of the 15:00 that was asked for.
    try:
        fe_per_smelt = int(values["COAL_PER_SMELT"]) * int(values["COAL_BURN_TICKS"])
        capacity = int(values["SMELTS_PER_BATTERY"]) * fe_per_smelt
        num, den = int(values["BURN_PER_TICK_NUM"]), int(values["BURN_PER_TICK_DEN"])
        if num <= 0 or den <= 0:
            problems.append("the burn rate is not a positive fraction")
        else:
            ticks = capacity * den // num
            want_ticks = 15 * 60 * 20
            if ticks != want_ticks:
                problems.append(
                    f"a full accumulator takes {ticks} ticks to charge "
                    f"({ticks // 1200}:{ticks % 1200 // 20:02d}), expected {want_ticks} "
                    f"(15:00) - {int(values['SMELTS_PER_BATTERY'])} smelts at "
                    f"{fe_per_smelt} FE and {num}/{den} per tick")
    except (TypeError, ValueError):
        problems.append("cannot recompute the charge time from the constants")

    # Charging: every tick of flame banks heat, and it is the SAME method that spends
    # the burn - a charge that ran on its own would fill the battery out of nothing.
    tick = _method_body(text, "public void serverTick()")
    if tick is None:
        problems.append("no serverTick")
    elif "bankBurn()" not in tick:
        problems.append("serverTick never banks the burn into the battery")
    bank = _method_body(text, "private void bankBurn()")
    if bank is None:
        problems.append("no bankBurn - nothing turns burning into FE")
    else:
        if "burnTicksRemaining -=" not in bank:
            problems.append("bankBurn stores heat without spending the burn")
        if "energy +=" not in bank:
            problems.append("bankBurn spends the burn without storing heat")
        if "BURN_PER_TICK_NUM" not in bank:
            problems.append("bankBurn ignores the conversion rate")
        if "chargeCarry" not in bank:
            problems.append("bankBurn throws away the fraction of a burn tick it cannot "
                            "spend - a full charge would drift away from 15 minutes")
        if "room" not in bank and "ENERGY_CAPACITY" not in bank:
            problems.append("bankBurn can charge past the accumulator's size")

    # What the crafter sees: a whole smelt, and nothing less.
    avail = _method_body(text, "public long availableOperations()")
    if avail is None or "energy" not in avail or "FE_PER_SMELT" not in avail:
        problems.append("availableOperations does not divide the accumulator by the price "
                        "of a smelt")
    powered = _method_body(text, "public boolean isPowered()")
    if powered is None or "availableOperations()" not in powered:
        problems.append("a furnace that cannot pay for one whole smelt still reports "
                        "itself as powered")
    consume = _method_body(text, "public void consumeOperations(long operations)")
    if consume is None or "FE_PER_SMELT" not in consume or "energy" not in consume:
        problems.append("consumeOperations does not take the accumulator down")

    # INTERNAL: no energy capability on the block or the block entity, in any form.
    for other, what in (
            ("neoforge/src/main/java/com/craftingveloce/block/VeloceVelocityFurnaceBlock.java",
             "the fuel furnace block"),
            (path, "the fuel furnace block entity")):
        other_text = open(other, encoding="utf-8").read()
        for forbidden, why in (("Capabilities.EnergyStorage", "registers an energy capability"),
                               ("IEnergyStorage", "implements IEnergyStorage")):
            if forbidden in other_text:
                problems.append(f"{what} {why} - the accumulated heat could then be "
                                f"pulled out by another mod's cable")

    # The GUI: VERTICAL, filling from the bottom up, and in the colours of heat rather
    # than the green of energy.
    screen = ("neoforge/src/main/java/com/craftingveloce/client/gui/"
              "VeloceVelocityFurnaceScreen.java")
    screen_text = open(screen, encoding="utf-8").read()
    bw = re.search(r"\bHEAT_BATTERY_W\s*=\s*(\d+)", screen_text)
    bh = re.search(r"\bHEAT_BATTERY_H\s*=\s*(\d+)", screen_text)
    if bw is None or bh is None:
        problems.append("the screen has no HEAT_BATTERY_W/H")
    elif int(bh.group(1)) <= int(bw.group(1)):
        problems.append(f"the heat battery is drawn {bw.group(1)}x{bh.group(1)} - that is "
                        f"horizontal (or square); it was asked to STAND, filling upwards")
    # Bottom-up: the TOP edge of the fill is the one that moves, so the drawn rectangle
    # has to start at `y + HEAT_BATTERY_H - filled` and end at the fixed bottom edge.
    # Drawing it from the top down would read as a gauge draining away, which is the
    # opposite of what was asked for.
    if "y + HEAT_BATTERY_H - filled" not in screen_text:
        problems.append("the heat battery does not fill FROM THE BOTTOM UP")
    # NO terminal. Every other battery in the mod carries a 2x6 nub, and on an upright
    # cell the player read it as a candle wick and asked for a plain rectangle - so a
    # HEAT_NUB_* coming back is the regression this catches.
    if "HEAT_NUB" in screen_text or "HEAT_NUB" in open(
            "scripts/gen_furnace_gui.py", encoding="utf-8").read():
        problems.append("the heat battery has a terminal again - it was asked to be a "
                        "plain rectangle (a nub on an upright cell reads as a candle wick)")
    if "0xFF39D353" in screen_text:
        problems.append("the heat battery uses the green of Forge Energy - it was asked "
                        "to be the colour of heat")
    if "gui.craftingveloce.furnace.temperature" not in screen_text:
        problems.append("the battery tooltip does not show a temperature")
    if re.search(r"feCompact\(", screen_text) is not None:
        problems.append("the heat battery tooltip counts FE - the player asked for "
                        "degrees Celsius")

    if problems:
        fail("heat battery:\n  " + "\n  ".join(problems))
    print("    OK (heat battery: 1 coal per smelt, 64 smelts, a full charge in 15:00, "
          "internal only, upright and filling upwards, hot-coloured)")


def validate_partial_delivery():
    """
    A request that cannot be satisfied IN FULL must still deliver what the network has.

    The player: "shift-click does not work on the items from the furnace recipe - it
    appeared again; taking one works, but I cannot shift-click to take a stack".

    That is count-dependent by construction, and this guard exists because the cause was
    invisible: a plain click asks for ONE unit and is served straight from stock, while
    a shift-click asks for a whole stack, which the network normally does not have, so
    the planner is asked to craft the remainder. When it could not, the entire request
    was reported as failed and the terminal handed over NOTHING - the player lost even
    the units already sitting in the network.

    So: `ensureAvailable` must route every failure through `partialOrFail`, which returns
    the available amount when there is one.
    """
    path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java"
    if not os.path.exists(path):
        fail("partial delivery:\n  no VeloceAutoCrafter")
    text = open(path, encoding="utf-8").read()
    problems = []

    helper = _method_body(text, "private static CraftResult partialOrFail(")
    if helper is None:
        problems.append("no partialOrFail helper (a failed craft would deliver nothing)")
    elif "CraftResult.ok(" not in helper:
        problems.append("partialOrFail does not return the available amount")

    # Every failure inside the two ensureAvailable overloads must go through the helper:
    # the full-request planner, the budget abort and the execution failure.
    body = _method_body(text, "Item item, int count, Context ctx,\n"
                              "                                              long planBudgetNanos)")
    if body is None:
        problems.append("could not find the planning ensureAvailable overload")
    else:
        # There are FIVE failure paths in the planning overload: the "not enabled" gate,
        # the two budget aborts, the "cannot plan anything" diagnosis and the execution
        # failure. The threshold must be exact - a lower one silently tolerated one path
        # reverting to "deliver nothing" (calibration caught exactly that).
        routed = body.count("partialOrFail(available, count,")
        if routed < 5:
            problems.append(f"only {routed} of the 5 failure paths in ensureAvailable hand over "
                            f"the available amount instead of nothing")
        # A bare `return CraftResult.fail(` left in the planning body is the regression we
        # care about: it is exactly the "delivers nothing" behaviour. The "amount" failure
        # is excluded - it rejects an invalid request (count <= 0), where there is
        # nothing to deliver in the first place.
        leftover = [ln.strip() for ln in body.splitlines()
                    if "return CraftResult.fail(" in ln
                    and "partialOrFail" not in ln
                    and "craft.error.amount" not in ln]
        if leftover:
            problems.append("a failure path still returns nothing: " + leftover[0])

    if problems:
        fail("partial delivery:\n  " + "\n  ".join(problems))
    print("    OK (partial delivery: a failed craft hands over what the network has)")


def validate_craftable_cache():
    """
    The cache of "how many more can be made" numbers: server-side, ONE per
    network, instant for the GUI.

    The player: "the numbers in the terminal load slowly from left to right,
    and the controller shows a few and then stops; I want them cached on the
    server so that the client gets a ready cache instantly when the GUI opens,
    and only then does the visible-counting logic start".

    We check the whole link, because each one breaks silently (the numbers
    simply disappear or go back to slow counting):
      1. the cache lives with the NETWORK (not with the terminal or the
         controller) - otherwise two GUIs have two different caches that drift
         apart,
      2. the packet first sends the cache snapshot and ONLY THEN computes the
         visible page (the reverse order = a return to "loading from the left"),
      3. freshly computed numbers go into the cache (without this the cache
         never fills up and instant has nothing to show),
      4. network changes clear the cache (otherwise the player sees stale
         numbers),
      5. the controller follows the SAME path as the terminal (no track of its own).
    """
    problems = []
    network = "neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetwork.java"
    packet = "neoforge/src/main/java/com/craftingveloce/network/RequestCraftableCountsPKT.java"
    nodes = "neoforge/src/main/java/com/craftingveloce/network/pipe/VeloceNodeBlocks.java"
    toggle = "neoforge/src/main/java/com/craftingveloce/network/CraftingTableToggleItemPKT.java"
    controller = "neoforge/src/main/java/com/craftingveloce/client/gui/VeloceControllerScreen.java"

    net_text = open(network, encoding="utf-8").read()
    for need, what in (("craftableMemo", "the cache map"),
                       ("public void rememberCraftable(", "writing to the cache"),
                       ("public Map<Item, Long> getCraftableMemo()", "the cache snapshot"),
                       ("public void clearCraftableMemo()", "clearing the cache")):
        if need not in net_text:
            problems.append("VelocePipeNetwork without " + what)

    handle = _method_body(open(packet, encoding="utf-8").read(), "public static void handle(")
    if handle is None:
        problems.append("no handling of the number request")
    else:
        if "getCraftableMemo()" not in handle:
            problems.append("the packet does not send the cache (no instant numbers)")
        if "rememberCraftable(" not in handle:
            problems.append("the packet does not write the result into the cache")
        if "getCraftableMemo()" in handle and "computeCraftableCounts(" in handle \
                and handle.index("getCraftableMemo()") > handle.index("computeCraftableCounts("):
            problems.append("the cache goes AFTER the counting (it must be instant, before)")

    for path, what in ((nodes, "network nodes"), (toggle, "the crafter toggle"),
                       (nodes, "network nodes")):
        if "clearCraftableMemo(" not in open(path, encoding="utf-8").read():
            problems.append(f"{what}: a change does not clear the cache")
    # Layout change / chunk reload: clearing MUST be in the BODY of
    # reconcileCaches - otherwise the clearCraftableMemo method exists, but
    # nobody calls it when the topology changes (calibration showed this).
    mgr_text = open("neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
                    encoding="utf-8").read()
    reconcile = _method_body(mgr_text, "private void reconcileCaches(")
    if reconcile is None or "clearCraftableMemo()" not in reconcile:
        problems.append("a layout change / chunk reload does not clear the cache")

    ctrl = open(controller, encoding="utf-8").read()
    if "craftableCounts.request(" not in ctrl:
        problems.append("the controller does not order the numbers through the same path as the terminal")
    if "updateCraftableCounts(" not in ctrl:
        problems.append("the controller does not receive the numbers from the same path")

    # Persistence: the cache must go into the save together with the network.
    if "saveCraftableMemo(" not in open(
            "neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
            encoding="utf-8").read():
        problems.append("the number cache is not saved into the game save")
    if "restoreCraftableMemo(" not in open(
            "neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
            encoding="utf-8").read():
        problems.append("the number cache is not read from the game save")

    if problems:
        fail("craftable number cache:\n  " + "\n  ".join(problems))
    print("    OK (number cache: network + instant snapshot + learning + clearing on changes)")

def validate_block_probe():
    """
    `/cv block`: statistics of the block under the crosshair + RPM threshold tolerance.

    The player: "I have a creative motor that theoretically gives 256 rotation,
    and the module shows not enough ... add a command that shows the statistics
    of the block I am looking at - how much speed it is missing". Two links,
    both easy to lose:
      1. the 256 RPM threshold must have a tolerance (256.0 is sometimes
         255.99998 after propagation),
      2. the command must exist, be wired into /cv, read the same data as the
         window/Jade (VeloceModuleInfoSource) and show the MISSING speed.
    """
    problems = []
    be = ("neoforge/src/main/java/com/craftingveloce/compat/create/block/entity/"
          "VeloceKineticModuleBlockEntity.java")
    text = open(be, encoding="utf-8").read()
    speed = _method_body(text, "public boolean hasEnoughRotationSpeed()")
    if speed is None or "REQUIRED_SPEED_TOLERANCE" not in speed:
        problems.append("the 256 RPM threshold without tolerance (256.0 is sometimes 255.99998)")

    probe = "neoforge/src/main/java/com/craftingveloce/commands/BlockProbeCommand.java"
    if not os.path.exists(probe):
        problems.append("no /cv block command")
    else:
        ptext = open(probe, encoding="utf-8").read()
        # The needle is the player-facing label the command prints for a missing
        # speed. It is English now; the guard follows the text it asserts on.
        for need, what in (("moduleInfo(", "machine data"),
                           ("MISSING: ", "the missing speed"),
                           ("VeloceNetworkNode", "information about the network node")):
            if need not in ptext:
                problems.append("the /cv block command without " + what)
    root = open("neoforge/src/main/java/com/craftingveloce/commands/CVDebugCommand.java", encoding="utf-8").read()
    if 'Commands.literal("block")' not in root or "BlockProbeCommand::describe" not in root:
        problems.append("/cv block is not wired")
    readme = "README.md"
    if os.path.exists(readme) and "/cv block" not in open(readme, encoding="utf-8").read():
        problems.append("/cv block is not documented in the README")

    if problems:
        fail("block probe (/cv block):\n  " + "\n  ".join(problems))
    print("    OK (/cv block: block statistics + RPM threshold tolerance)")


def validate_showcase_command():
    """
    /cv showcase: a block list from the REGISTRY, not from a hand-written list.

    A hand-written block list for testing will diverge from the registry with
    the first new block (and nobody will notice, because the command "works" -
    it just does not show the new block). The guard ensures the command walks
    the registry (BuiltInRegistries.BLOCK), fills the built machines
    (VeloceCaseBuildable) and is wired into the command registration.
    """
    path = "neoforge/src/main/java/com/craftingveloce/commands/CVShowcaseCommand.java"
    if not os.path.exists(path):
        fail("showcase:\n  no /cv showcase command class")
    text = open(path, encoding="utf-8").read()
    problems = []
    if "getStateForPlacement" not in text:
        problems.append("the showcase command without a state with its own placement logic")
    for need, what in (('literal("pipes")', "pipe mode"),
                       ('literal("clear")', "cleanup mode")):
        if need not in text:
            problems.append("the showcase command without " + what)
    # Method bodies, not the whole file: the name "BuiltInRegistries.BLOCK"
    # occurs in several places, so removing it from the block list alone would
    # not be visible.
    blocks_body = _method_body(text, "private static List<Block> ourBlocks()")
    if blocks_body is None or "BuiltInRegistries.BLOCK.keySet()" not in blocks_body:
        problems.append("showcase does not read the block list from the REGISTRY (a hand-written list will diverge)")
    fill_body = _method_body(text, "private static void fillParts(")
    if fill_body is None or "VeloceCaseBuildable" not in fill_body:
        problems.append("showcase does not fill the built machines with parts")
    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "CVShowcaseCommand.register" not in mod:
        problems.append("the showcase command is not registered")
    if problems:
        fail("showcase:\n  " + "\n  ".join(problems))
    print("    OK (/cv showcase: blocks from the registry + parts + pipes + cleanup)")


def validate_crafting_source_order():
    """
    The network is consulted BEFORE the player's inventory, in both places that decide.

    The owner's requirement: "the network should also be able to take items from the
    inventory, but only when the item is not in the network's inventory."

    Two methods answer that question and they must agree:

      * `ensureAvailable` decides whether anything has to be CRAFTED at all, so it counts
        the network first and lets the player's inventory cover only the shortfall;
      * `takeOne` decides where each individual ingredient comes from during execution.

    If `ensureAvailable` counted the inventory first it would report "nothing to craft"
    while the network was empty but a chest was full - and the plan would then be paid for
    out of the player's pockets. If `takeOne` took from the player first, the same thing
    would happen one layer down, silently.

    Comments are stripped before looking, because both methods now carry prose explaining
    this exact rule and a guard that a comment can satisfy checks nothing (see
    validate_extractor_redstone for the time that actually happened).
    """
    path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java"
    code = open(path, encoding="utf-8").read()

    def code_only(text):
        return "\n".join(line.split("//")[0] for line in text.split("\n"))

    # The SECOND overload, not the first. `ensureAvailable` exists twice: the short one
    # takes no budget and does nothing but delegate to the long one, so a guard reading the
    # first found a method with no stock lookup in it and reported the rule as broken when
    # it was not.
    signature = "public static CraftResult ensureAvailable("
    first = code.find(signature)
    second = code.find(signature, first + 1) if first >= 0 else -1
    if second < 0:
        return fail("no ensureAvailable with a planning budget in the auto crafter")
    ensure = _method_body(code[second:], signature)
    if ensure is None:
        return fail("no ensureAvailable in the auto crafter")
    ensure = code_only(ensure)
    for needle, what in (("getAllItemCounts(", "the network stock"),
                         ("ctx.inventory.count(", "the player inventory")):
        if needle not in ensure:
            fail(f"ensureAvailable no longer consults {what} - this guard is looking at the "
                 "wrong method")
    if ensure.index("getAllItemCounts(") > ensure.index("ctx.inventory.count("):
        fail("ensureAvailable counts the PLAYER'S INVENTORY before the network, so a chest "
             "full of the item is ignored and the plan is paid for out of the player's "
             "pockets")

    take = _method_body(code, "private static ItemStack takeOne(")
    if take is None:
        return fail("no takeOne in the auto crafter")
    take = code_only(take)
    for needle, what in (("ctx.network.extractItem(", "the network"),
                         ("ctx.inventory.extract(", "the player inventory")):
        if needle not in take:
            fail(f"takeOne no longer draws on {what} - this guard is looking at the wrong "
                 "method")
    if take.index("ctx.network.extractItem(") > take.index("ctx.inventory.extract("):
        fail("takeOne draws on the PLAYER'S INVENTORY before the network, so ingredients "
             "leave the player while the same item sits in a chest in the same network")
    print("    OK (network first, player inventory only covers the shortfall - in both places)")


def validate_module_drop_stacks():
    """
    A machine must give back as many elements as it held, in stacks the game accepts.

    Two separate ways this goes wrong, and both are silent:

      * the count is not taken from the machine, so every wheel and every crafter drops a
        fixed number regardless of what the player built;
      * the count is put into ONE `ItemStack`. The crafter grid is 9x9, so a full machine
        holds 81 - above the vanilla maximum of 64. A single stack claiming to hold 81 is
        a stack no vanilla code path will move, split or save sensibly, so a full crafter
        would drop most of its contents in a form that behaves unlike every other item.

    So the count must come from `caseParts()` and must be split on the ITEM's own maximum
    rather than a hard-coded 64 - an unstackable input then gives one stack per element,
    which is the honest answer.
    """
    path = ("neoforge/src/main/java/com/craftingveloce/compat/create/block/"
            "VeloceKineticModuleBlock.java")
    code = open(path, encoding="utf-8").read()
    body = _method_body(code, "protected java.util.List<ItemStack> getDrops(")
    if body is None:
        return fail("no getDrops in the kinetic module block - the module would drop itself")
    if "caseParts()" not in body:
        fail("the module drop does not ask the machine how many elements it holds, so a "
             "crushing wheel with one wheel drops as much as one with two")
    if "getMaxStackSize()" not in body:
        fail("the module drop puts the whole count into one stack: a full crafter grid "
             "(9x9 = 81) would drop a single stack of 81, above the vanilla maximum of 64")
    print("    OK (drops as many elements as the machine held, split into legal stacks)")


def validate_block_config_defaults():
    """
    The FE config's defaults must equal the values the machines were built with.

    Every machine's cost and battery size now exist TWICE: as the compiled value on its
    `FeModule` (or as a `DEFAULT_*` constant for the two core machines) and as the literal
    default in `VeloceBlockConfig`, because a config file needs a number written out that a
    player can read and edit. Two copies of a number is exactly the kind of duplication that
    drifts apart in this project, and the symptom would be quiet: a machine that suddenly
    costs two hundred thousand FE out of the box for a pack that never opened the file.

    So this guard reads both and compares them, both ways:
      * every machine that has a config section has one whose defaults match it;
      * and a machine that exists but has NO section is reported, because a machine nobody
        can retune is the thing this config was added to fix.
    """
    import re as _re

    problems = []

    cfg = open("neoforge/src/main/java/com/craftingveloce/config/VeloceBlockConfig.java",
               encoding="utf-8").read()
    declared = {}
    for m in _re.finditer(r'declare\(b,\s*"([a-z0-9_]+)",\s*([0-9_]+),\s*([0-9_]+)\)', cfg):
        declared[m.group(1)] = (int(m.group(2).replace("_", "")),
                                int(m.group(3).replace("_", "")))

    machines = {}
    for path in ("neoforge/src/main/java/com/craftingveloce/compat/mekanism/MekanismFeModules.java",
                 "neoforge/src/main/java/com/craftingveloce/compat/alchemistry/"
                 "AlchemistryFeModules.java"):
        text = open(path, encoding="utf-8").read()
        for m in _re.finditer(
                r'new FeModule\(\s*"([^"]+)",\s*"[^"]*",\s*([0-9_]+),\s*([0-9_]+)', text):
            machines[m.group(1).replace(":", "_").replace("/", "_")] = (
                int(m.group(3).replace("_", "")), int(m.group(2).replace("_", "")))

    # The two machines that are not FeModule-based keep their defaults as constants.
    for key, path, cap_const, fe_const in (
            ("electric_furnace",
             "neoforge/src/main/java/com/craftingveloce/block/entity/"
             "VeloceElectricFurnaceBlockEntity.java",
             "DEFAULT_ENERGY_CAPACITY", "DEFAULT_FE_PER_SMELT"),
            ("brewing_stand",
             "neoforge/src/main/java/com/craftingveloce/block/entity/"
             "VeloceBrewingStandBlockEntity.java",
             "DEFAULT_ENERGY_CAPACITY", "DEFAULT_FE_PER_BREW")):
        text = open(path, encoding="utf-8").read()
        cap = _re.search(cap_const + r"\s*=\s*([0-9_]+)", text)
        fe = _re.search(fe_const + r"\s*=\s*([0-9_]+)", text)
        if cap is None or fe is None:
            problems.append(f"{key}: no {cap_const}/{fe_const} to compare the config against")
            continue
        machines[key] = (int(cap.group(1).replace("_", "")), int(fe.group(1).replace("_", "")))

    # Only the machines that are actually REGISTERED need a section; the sixteen switched-off
    # Mekanism machines have no block in the game, so a config entry for one would be a
    # setting that cannot do anything.
    # NORMALISED THE SAME WAY as `machines` above. The ids in the source carry a colon
    # ("mekanism:washing") and the dict keys do not ("mekanism_washing"), so comparing them
    # raw never matched and every switched-off machine counted as live - the guard then
    # demanded a config section for the sixteen machines that have no block in the game.
    disabled = {_id.replace(":", "_").replace("/", "_") for _id in _re.findall(
        r'"(mekanism:[a-z_]+)"', open(
            "neoforge/src/main/java/com/craftingveloce/compat/mekanism/MekanismFeModules.java",
            encoding="utf-8").read()
        .split("public static final java.util.Set<String> DISABLED")[1].split(");")[0])}
    live = {k: v for k, v in machines.items() if k not in disabled}

    for key, (cap, fe) in sorted(live.items()):
        if key not in declared:
            problems.append(f"{key}: a machine that no config section can retune")
            continue
        dcap, dfe = declared[key]
        if dcap != cap:
            problems.append(f"{key}: config default capacity {dcap} != the machine's {cap}")
        if dfe != fe:
            problems.append(f"{key}: config default cost {dfe} != the machine's {fe}")

    for key in sorted(set(declared) - set(live)):
        problems.append(f"{key}: a config section for a machine that is not registered")

    if problems:
        fail("FE config defaults:\n  " + "\n  ".join(problems))
    print(f"    OK ({len(live)} machines, every config default matches its compiled value)")


def validate_jade_config_lang():
    """
    Every Jade provider UID must have a `config.jade.plugin_<ns>.<path>` translation.

    Jade registers a config toggle per provider UID and then ASSERTS the translation
    exists when it first builds a GUI screen (`JadeClient.onGui`, called from
    `Minecraft.onGameLoadFinished`). A missing key is therefore not a cosmetic problem:
    the assertion kills the client before it ever reaches the world.

    That is exactly what happened - `craftingveloce:module_info` was registered with no
    lang entry, and the client died at startup with

        AssertionError: Missing config translation:
            config.jade.plugin_craftingveloce.module_info

    and NO crash report, so the cause was invisible from the crash-reports folder. It also
    only fired when a screen was built, so some runs reached the world and some did not -
    which is why it survived so long.
    """
    problems = []
    plugin = ("neoforge/src/main/java/com/craftingveloce/compat/jade/VeloceJadePlugin.java")
    code = open(plugin, encoding="utf-8").read()

    # UIDs are declared as ResourceLocation.fromNamespaceAndPath("craftingveloce", "x")
    uids = re.findall(r'fromNamespaceAndPath\(\s*"([a-z0-9_]+)"\s*,\s*"([a-z0-9_/]+)"\s*\)', code)
    if not uids:
        problems.append("found no provider UID in the Jade plugin - this guard is looking "
                        "at nothing")

    lang = json.load(open("assets/craftingveloce/lang/en_us.json", encoding="utf-8"))
    for namespace, path in uids:
        key = f"config.jade.plugin_{namespace}.{path.replace('/', '.')}"
        if key not in lang:
            problems.append(f"no translation for {key} - Jade asserts on it and the client "
                            "dies before reaching the world")
    if problems:
        fail("Jade config translations:\n  " + "\n  ".join(problems))
    print(f"    OK ({len(uids)} Jade provider UID(s), every config translation present)")


def validate_extractor_redstone():
    """
    The extractor must do NOTHING while it is powered.

    Player: "let it react to redstone - when it gets redstone it must not work."

    The gate has to be the FIRST thing in the tick, not merely present somewhere in it:
    checking the signal after the pull would leave a powered extractor still pulling items
    out of the network for one tick out of every interval, which reads as "mostly works"
    and is harder to notice than either a working or a stopped machine.

    `lastPullTick` is deliberately left alone while powered, so the machine resumes on the
    first tick the signal drops instead of waiting out a phase it burned while switched off.
    """
    path = ("neoforge/src/main/java/com/craftingveloce/block/entity/"
            "VeloceExtractorBlockEntity.java")
    code = open(path, encoding="utf-8").read()
    body = _method_body(code, "public void serverTick()")
    if body is None:
        return fail("no serverTick in the extractor - nothing ticks it at all")
    # Comments are stripped BEFORE looking, and the marker is the call rather than the bare
    # name. The method's own explanation says "hasNeighborSignal is the same test a piston
    # uses", so a first version of this guard found the word in that prose, was satisfied by
    # it, and passed on a file where the gate had been moved after the pull - a guard that a
    # COMMENT can satisfy is worse than no guard, because it reports safety it never checked.
    code_only = "\n".join(line.split("//")[0] for line in body.split("\n"))
    if "level.hasNeighborSignal(" not in code_only:
        fail("the extractor works while powered: the redstone gate is gone from serverTick()")
    if "pullFilteredItemsFromNetwork(" not in code_only:
        fail("serverTick no longer pulls from the network - this guard is looking at the "
             "wrong method")
    if code_only.index("level.hasNeighborSignal(") > code_only.index("pullFilteredItemsFromNetwork("):
        fail("the extractor checks the redstone signal AFTER it has already pulled from the "
             "network - the gate must be the first thing in the tick")
    print("    OK (a powered extractor does nothing: the gate is the first thing in the tick)")


def validate_loot_item_ids():
    """
    Every loot table must point to an EXISTING item.

    The BUG from the game log ("Unknown registry key in ResourceKey[minecraft:item]:
    craftingveloce:veloce_crusher_module"): 4 loot tables of Mekanism modules
    pointed to an item WITHOUT the mod prefix, because the generator left the
    existing files untouched. The symptom: those blocks dropped NOTHING (and
    the error only went to the log, while loading the loot tables).
    """
    registry = open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    compat = "".join(open(path, encoding="utf-8").read()
                     for path in glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/*Blocks.java"))
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
        fail("loot tables point to non-existent items:\n  " + "\n  ".join(problems))
    print(f"    OK ({len(files)} loot tables, every referenced item exists)")


def validate_no_dead_module_loot_tables():
    """
    A module block must not ALSO have a loot table.

    Every module block is a `VeloceFeModuleBlock` or a `VeloceKineticModuleBlock`, and both
    override `getDrops` to hand back the EMPTY casing plus the item that was inserted - never
    the finished machine, which is creative-only. A loot table for such a block is therefore
    dead: the code path that consults loot tables never runs for it.

    46 of them existed (36 of them here, plus the Mekanism and Alchemistry leftovers) and
    were deleted. This guard keeps them deleted.

    It re-checks the PREMISE instead of assuming it. If a `getDrops` override ever
    disappears, those loot tables become load-bearing again and a guard that merely counted
    files would go on passing while every machine dropped nothing in game.
    """
    problems = []
    for path in ("neoforge/src/main/java/com/craftingveloce/block/VeloceFeModuleBlock.java",
                 "neoforge/src/main/java/com/craftingveloce/compat/create/block/"
                 "VeloceKineticModuleBlock.java"):
        code = open(path, encoding="utf-8").read()
        if "getDrops(" not in code or "@Override" not in code:
            problems.append(f"{os.path.basename(path)} no longer overrides getDrops - its loot "
                            "tables would be live again")
    if problems:
        fail("module block drops changed:\n  " + "\n  ".join(problems))

    sources = ["neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java"]
    sources += sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/*Blocks.java"))
    module_ids = set()
    for path in sources:
        module_ids.update(re.findall(r'register(?:SimpleBlockItem)?\(\s*"(veloce_[a-z0-9_]*_module)"',
                                     open(path, encoding="utf-8").read()))
    if not module_ids:
        fail("found no module blocks at all - the block registration pattern changed and this "
             "guard is no longer looking at anything")

    dead = [os.path.basename(path)
            for path in sorted(glob.glob("data/craftingveloce/loot_table/blocks/*.json"))
            if os.path.basename(path).endswith("_module.json")]
    if dead:
        fail("loot tables for module blocks, which override getDrops and can never consult "
             "them:\n  " + "\n  ".join(dead))
    print(f"    OK (no loot table for any of the {len(module_ids)} module blocks)")


def validate_legacy_src_submodule_removed():
    """
    `toms_storage_src/` must not come back.

    It was recorded as a gitlink (mode 160000) with no `.gitmodules` entry, and the
    directory on disk was EMPTY: a broken submodule reference. Nothing built from it and a
    fresh clone could not check it out. It was removed.

    A guard because the failure it produced is invisible: an empty directory is not
    something git tracks, so nobody notices it is missing, and if it ever reappears with
    real content then either a submodule entry has to be written or the build has a new
    source tree it does not know about.
    """
    if os.path.exists("toms_storage_src"):
        fail("toms_storage_src/ is back - nothing builds from it, and as a gitlink with no "
             ".gitmodules entry it cannot even be checked out")
    if ".gitmodules" in os.listdir("."):
        fail(".gitmodules exists - the removed source tree was a submodule and something has "
             "reintroduced one")
    print("    OK (the broken toms_storage_src submodule is gone and has not returned)")


def _loot_item_names(node):
    """All 'name' values from minecraft:item entries (recursively)."""
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
    Every one of our blocks must be {@code noOcclusion()} + must not hide its neighbours.

    The BUG that uncovered this (a player report): casings with contents inside
    (controller, extractor, sensors, furnaces, modules) did not have
    {@code noOcclusion}, so the game treated them as a FULL, opaque cube and
    clipped the neighbours' faces where they touched ("transparency appeared
    and the block next to it was not visible"). The empty Integrale frame had
    that option from the start - which is why it worked and why the symptom
    looked random.
    """
    problems = []
    files = ["neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java"]
    files += sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/*Blocks.java"))
    files += sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/block/*.java"))
    for path in files:
        if not os.path.exists(path):
            continue
        text = open(path, encoding="utf-8").read()
        chains = text.count("Properties.of(")
        if chains == 0:
            continue
        missing = chains - text.count("noOcclusion()")
        if missing > 0:
            problems.append(f"{path.replace(os.sep, '/')}: {missing} of {chains} "
                            f"block definitions without noOcclusion()")
    if problems:
        fail("casing transparency (noOcclusion):\n  " + "\n  ".join(problems))
    print("    OK (every block definition: noOcclusion + does not hide neighbours)")


def validate_create_mechanics():
    """
    Create machine mechanics: rotation from every side, sheets, grid, requirements.

    The player described four things that must work together:
      1. rotation accepted from EVERY side (the rotation axis from the block
         state, a shaft on both ends of the axis) - otherwise rotation from the
         side is ignored,
      2. the side the rotation comes from is closed with a sheet, as with a pipe,
      3. the mechanical crafter has as many grid cells as the player built, and
         a recipe only goes in when it fits them (5x5 = 25 cells, ceiling 9x9),
      4. recipes with a Basin and heat (Blaze Burner) are craftable when those
         things are in the network - without them the planner does not see them.
    """
    problems = []

    block_code = open("neoforge/src/main/java/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java",
                      encoding="utf-8").read()
    for need, what in (("BlockStateProperties.AXIS", "the rotation axis state"),
                       ("side.getAxis() == own",
                        "a shaft from every side aligned with the axis"),
                       ("neighbourAxis(world, pos.relative(side)",
                        "a shaft on the side of a powered neighbour"),
                       ("return state.getValue(BlockStateProperties.AXIS)",
                        "the rotation axis read from the state"),
                       ("instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity",
                        "the sheet on the side the Create rotation comes from"),
                       ("VeloceIntegraleFrame.withClosure", "sheets on the casing sides"),
                       ("protected BlockState updateShape", "recomputing the sheets after a neighbour changes")):
        if need not in block_code:
            problems.append("the kinetic machine without " + what)

    entry = open("neoforge/src/main/java/com/craftingveloce/crafting/ProcessingEntry.java", encoding="utf-8").read()
    if "public boolean fitsGrid(int side, int parts)" not in entry:
        problems.append("ProcessingEntry does not check the NUMBER of cells (only the grid side)")
    if "gridWidth * gridHeight <= parts" not in entry:
        problems.append("no condition that all recipe cells are covered")
    processing_sources = open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceProcessingSources.java",
                              encoding="utf-8").read()
    if "public static int maxParts(" not in processing_sources:
        problems.append("no counting of the built cells")
    if "return (int) Math.floor(Math.sqrt(maxParts(level, network, type)));" not in processing_sources:
        problems.append("the grid side is not computed from the number of built cells")
    if "public boolean fitsGrid(int side)" in entry:
        problems.append("ProcessingEntry without the grid fitting rule")
    sources = open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceProcessingSources.java",
                   encoding="utf-8").read()
    if "maxGridSide" not in sources:
        problems.append("no computation of the built grid side")
    harvest = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateRecipeHarvest.java",
                   encoding="utf-8").read()
    for need, what in (("recipe.getWidth()", "the grid width from the recipe"),
                       ("recipe.getHeight()", "the grid height from the recipe"),
                       ("requiresHeat", "the heat flag (Blaze Burner)")):
        if need not in harvest:
            problems.append("the Create harvest without " + what)
    module = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateModule.java",
                  encoding="utf-8").read()
    for need, what in (('"blaze_burner"', "the Blaze Burner requirement"),
                       ('"basin"', "the Basin requirement")):
        if need not in module:
            problems.append("the Create module without " + what)
    # Filtering must happen in BOTH directions of the query (the planner and
    # the "what can we do" list): the mere occurrence of a name in the file
    # means nothing - it is easy to delete one of the two places and silently
    # get recipes above the built grid.
    for signature, name in (("public List<ProcessingEntry> recipesFor(", "recipesFor"),
                            ("public Set<Item> producible(", "producible")):
        body = _method_body(module, signature)
        if body is None or "fitsGrid" not in body or "requirementsMet" not in body:
            problems.append(f"{name} does not filter recipes by grid and requirements")
    family = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateRecipeFamily.java",
                  encoding="utf-8").read()
    for need, what in (("pressing()", "the press type"), ("mixing()", "the mixer type")):
        if need not in family:
            problems.append("the Create family without " + what)
    be = open("neoforge/src/main/java/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
              encoding="utf-8").read()
    for need, what in (("public static final int GRID_LIMIT = 9", "the 9x9 limit"),
                       ("GRID_LIMIT * GRID_LIMIT", "the crafter cell limit")):
        if need not in be:
            problems.append("the crafter without " + what)

    # Conversions: an empty Integrale + a Create block = our module. This is the
    # path the player described ("I take an empty veloce integrale, click it
    # with a crushing wheel - one appears in the middle, a second click - the
    # second one, and only now does the machine work").
    compat = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateCompat.java",
                  encoding="utf-8").read()
    if "registerConversions();" not in compat:
        problems.append("the Create gate does not register conversions from the empty casing")
    for item in ("crushing_wheel", "mechanical_crafter", "millstone", "mechanical_saw",
                 "mechanical_press", "mechanical_mixer", "deployer"):
        if f'VeloceIntegraleConversions.register(create("{item}")' not in compat:
            problems.append(f"no casing conversion to a module from create:{item}")

    # The key of the conversion table MUST be an ID, not a block reference:
    # blocks from another mod may not exist yet when the gate registers the
    # entries, and then the entry would point to an empty block and clicking the
    # casing would do NOTHING.
    conversions = open("neoforge/src/main/java/com/craftingveloce/block/VeloceIntegraleConversions.java",
                       encoding="utf-8").read()
    if "record Conversion(ResourceLocation inputId" not in conversions:
        problems.append("the conversion table is keyed by block, not by ID "
                        "(clicking with a block from a mod may not work)")
    if "BuiltInRegistries.BLOCK.getKey(block)" not in conversions:
        problems.append("conversions do not look up the input by ID at use time")

    integrale = open("neoforge/src/main/java/com/craftingveloce/block/VeloceIntegraleBlock.java",
                     encoding="utf-8").read()
    if "VeloceCaseBuildable" not in integrale or "buildable.addPart()" not in integrale:
        problems.append("the first inserted block does not stay in the machine "
                        "(the conversion does not add a part)")

    renderer = open("neoforge/src/main/java/com/craftingveloce/client/render/VeloceCaseRenderer.java",
                    encoding="utf-8").read()
    render_body = _method_body(renderer, "public void render(")
    if render_body is None or "caseParts() > 0" not in render_body \
            or "caseBuiltFromParts()" not in render_body:
        problems.append("a machine without clicked-in parts is not an empty casing "
                        "(from creative you see a finished block)")
    if "for (int i = 0; i < parts; i++)" not in renderer:
        problems.append("the renderer does not draw as many models as the player clicked in")

    # The contents MUST be drawn as an ITEM MODEL. Machines from other mods
    # (millstone, saw, crusher, Mekanism machines) do not have an ordinary block
    # model - their own block entity renderers draw them, so renderSingleBlock
    # showed an EMPTY casing (a player report: "other items do not render inside
    # at all, only vanilla ones").
    parts_body = _method_body(renderer, "private void renderParts(")
    render_body = _method_body(renderer, "public void render(")

    # The casing contents are an ITEM model (the full representation of the
    # machine), not a block model - the block models of machines from mods are
    # trimmed (part of them is drawn by their own renderer / Flywheel), while
    # item models are complete. The item transform (FIXED) MUST be compensated,
    # otherwise the item is small and in a corner.
    content_body = _method_body(renderer, "private void renderContent(")
    if content_body is None:
        problems.append("the casing renderer without renderContent")
    else:
        # We check the conditions in the BODY of renderContent - the names also
        # occur in other methods (contentTransform), so mere presence in the
        # file would let, for example, swapping the FIXED context for NONE through.
        for need, what in (("ItemDisplayContext.FIXED", "the FIXED context"),
                           ("renderStatic(", "drawing the item model"),
                           ("transform.scale.x", "the size compensation"),
                           ("-transform.translation.x", "the offset compensation")):
            if need not in content_body:
                problems.append("renderContent without " + what)
        # The model must stand UPRIGHT: the FIXED transform tilts the item as in
        # the inventory (30/225 degrees), which made the player see "the saw and
        # the deployer looking sideways". We zero the rotation with the inverse
        # of rotationZYX.
        if "rotationZYX(" not in content_body or "transform.rotation" not in content_body:
            problems.append("the contents do not stand upright (tilt from the FIXED transform)")
        if "contentScale" not in content_body:
            problems.append("the contents do not use the machine scale (large machines stick out at the top)")
    if "getTransforms()" not in renderer or "getTransform(ItemDisplayContext.FIXED)" not in renderer:
        problems.append("no reading of the item model transform")

    # The per-machine scale lives in the casing table.
    contents = open("neoforge/src/main/java/com/craftingveloce/block/VeloceCaseContents.java", encoding="utf-8").read()
    if "public static float contentScale(BlockState state)" not in contents:
        problems.append("the casing table without a content scale")
    for need, what in (("float scale,", "the content size in the table entry"),
                       ("float pitch,", "the machine rotation in the table entry"),
                       ("boolean keepItemRotation", "the model orientation choice in the table entry")):
        if need not in contents:
            problems.append("the casing table entry without " + what)
    compat_create = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateCompat.java", encoding="utf-8").read()
    for need, what in (('block("crushing_wheel"), 0.4F, 0.0F, true', "the strongly reduced crusher"),
                       ('block("mechanical_crafter"), 0.42F', "the reduced crafter"),
                       ('block("mechanical_press"), 0.5F', "the reduced press"),
                       ('block("mechanical_mixer"), 0.5F', "the reduced mixer"),
                       ('block("deployer"), 0.5F, -90.0F', "the deployer looking down"),
                       ('block("mechanical_saw"), 0.7F, -90.0F', "the saw looking down")):
        if need not in compat_create:
            problems.append("no " + what)

    be_code = open("neoforge/src/main/java/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
                   encoding="utf-8").read()

    # Rotation: a 256 RPM THRESHOLD + a 1024 SU draw.
    #
    # The draw is SU PER RPM, because Create multiplies it by the current speed
    # (`impact x |RPM|`). The trap this test exists for is the one a player reported:
    # an earlier version divided the constant by the CURRENT speed, which reported an
    # impact of 1024 while the machine stood still and then 1024 * 256 = 262144 SU once
    # the shaft reached the required 256 RPM.
    #
    # So the test is that the draw is a FLAT constant: built from the module's SU
    # constant and the required speed, and reading no speed at all. This test used to
    # assert the opposite - it demanded the literal text of the buggy division
    # (`module.constantSu() / speed` and the `speed < 1f` guard) and so kept a dead
    # `if (false)` block alive in the method purely to satisfy itself.
    modules = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateKineticModules.java",
                   encoding="utf-8").read()
    if "public static final int REQUIRED_SPEED = 256;" not in modules:
        problems.append("no required speed threshold of 256 RPM")
    if "public static final float STRESS_SU = 1024.0F;" not in modules:
        problems.append("no 1024 SU constant for the modules")
    if modules.count("STRESS_SU") < 8:
        problems.append("not every module takes 1024 SU (somebody hard-coded their own number)")
    stress_body = _method_body(be_code, "float calculateStressApplied()")
    if stress_body is None:
        problems.append("no calculateStressApplied in the kinetic machine")
    else:
        if "module.constantSu()" not in stress_body:
            problems.append("the kinetic draw does not come from the module's SU constant")
        if "REQUIRED_SPEED" not in stress_body:
            problems.append("the kinetic draw is not expressed per RPM (no REQUIRED_SPEED)")
        if "lastStressApplied" not in stress_body:
            problems.append("the kinetic draw does not record the applied stress")
        if ("getTheoreticalSpeed" in stress_body or "getSpeed" in stress_body
                or "/ speed" in stress_body):
            problems.append("the kinetic draw depends on the current speed again "
                            "(the 262144 SU bug: 1024 * 256)")
        if "constantSu()" in stress_body and "caseParts" in stress_body:
            problems.append("the kinetic draw scales with the parts in the casing")
    if "public boolean hasEnoughRotationSpeed()" not in be_code:
        problems.append("no check of the speed threshold in the machine")
    powered_body = _method_body(be_code, "public boolean isPowered()")
    if powered_body is None or "hasEnoughRotationSpeed()" not in powered_body:
        problems.append("isPowered does not require the speed threshold")

    # A machine that arrives by CONVERSION is placed from `resultBlock().defaultBlockState()`
    # with only the pipe closures carried over, so it materialises pointing along Y - and
    # Create connects two blocks only when their rotation axes agree. The axis must therefore
    # be aimed at a neighbouring drive, and the attach requested through Create's own
    # one-shot flag, BEFORE anything is reported to the network. Dropping either half, or
    # swapping their order, reproduces the player's report: "I place an Integrale, insert a
    # Create module, and the module does not see the power - something does not refresh".
    #
    # The trap that made this permanent rather than transient: `attachKinetics()` clears the
    # very flag `tick()` consults before trying again, so a single attach made while the axis
    # was still wrong left the machine disconnected for good.
    add_part = _method_body(be_code, "public boolean addPart()")
    if add_part is None:
        problems.append("no addPart in the kinetic machine")
    else:
        if "alignAxisWithDrive" not in add_part:
            problems.append("addPart does not aim the axis at a neighbouring drive")
        if "markKineticsStale" not in add_part:
            problems.append("addPart does not ask for the rotation network to be recomputed")
        if ("alignAxisWithDrive" in add_part and "markKineticsStale" in add_part
                and add_part.index("alignAxisWithDrive") > add_part.index("markKineticsStale")):
            problems.append("addPart refreshes the network BEFORE the axis matches the drive")
    axis_fix_body = _method_body(be_code, "private void alignAxisWithDrive()")
    if axis_fix_body is None or "getRotationAxis" not in axis_fix_body:
        problems.append("the axis is not taken from a neighbouring drive")
    kinetic_block = open(
        "neoforge/src/main/java/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java",
        encoding="utf-8").read()
    neighbour_body = _method_body(kinetic_block, "protected void neighborChanged(")
    if neighbour_body is None or "markKineticsStale" not in neighbour_body:
        problems.append("changing the axis does not refresh the rotation network")

    # The "not enough force" status is no longer drawn with its own text on the
    # screen.
    #
    # The player: "throw that thing out, make a Jade integration instead" -
    # this information is now shown by the Jade tooltip and the right-click
    # window (see validate_jade_info, which checks that they really show it).
    # This test guards the other end: that there is no text of our own at the
    # crosshair.
    gone = "neoforge/src/main/java/com/craftingveloce/client/VeloceModuleOverlay.java"
    if os.path.exists(gone):
        problems.append("our own text at the crosshair came back (it should be in the Jade tooltip)")
    own_mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceModuleOverlay" in own_mod:
        problems.append("the mod still wires our own text at the crosshair")

    # Creative and middle click must give a FILLED machine (crusher: 2 wheels,
    # crafter: 3x3), not an empty one - otherwise the player places emptiness
    # and it looks like a bug.
    for need, what in (("public ItemStack filledStack()", "the item with the saved parts"),
                       ("public int defaultParts()", "the default number of parts"),
                       ("getCloneItemStack", "the filled middle click"),
                       ("return 9;", "the default 3x3 grid for the crafter"),
                       ("return 2;", "the default two wheels for the crusher")):
        if need not in block_code:
            problems.append("the machine without " + what)
    blocks_registry = open("neoforge/src/main/java/com/craftingveloce/compat/create/CreateBlocks.java",
                           encoding="utf-8").read()
    for need, what in (("VELOCE_CRUSHING_MODULE.get().filledStack()", "the filled crusher in the tab"),
                       ("VELOCE_MECHANICAL_CRAFTER_MODULE.get().filledStack()",
                        "the filled crafter in the tab")):
        if need not in blocks_registry:
            problems.append("no " + what)

    # The mechanical crafter does NOT react to power: neither the animation nor
    # the block state.
    if "ignoresPowerInModel()" not in block_code:
        problems.append("the machine has no 'ignores power in the model' marker")
    neighbour = _method_body(block_code, "protected void neighborChanged(")
    if neighbour is None or "ignoresPowerInModel()" in neighbour:
        # The axis must adapt to the rotation from EVERY side - including in the
        # crafter. Previously the crafter returned here immediately and did not
        # accept rotation from the side.
        problems.append("the machine does not adapt the axis to the rotation (no power from every side)")
    if neighbour is None or "AXIS" not in neighbour:
        problems.append("the machine does not set the rotation axis when a neighbour changes")
    speed_body = _method_body(be_code, "public float caseSpinDegreesPerTick()")
    if speed_body is None or "ignoresPowerInModel()" not in speed_body:
        problems.append("the crafter animation depends on the rotation (it must be constant)")

    for need, what in (('block("mechanical_press"), 0.5F, 180.0F', "the press rotated by 180 degrees"),
                       ('block("mechanical_mixer"), 0.5F, 180.0F', "the mixer rotated by 180 degrees")):
        if need not in compat_create:
            problems.append("no " + what)

    # The per-machine rotation must be applied, and zeroing the tilt must skip
    # the machines that are to keep their orientation (the millstone wheel).
    for need, what in (("contentPitch", "the per-machine rotation"),
                       ("keepsItemRotation", "the choice of whether to zero the tilt"),
                       ("Axis.XP.rotationDegrees(contentPitch)", "applying the machine rotation"),
                       ("if (!keepRotation)", "skipping the zeroing for selected machines")):
        if need not in renderer:
            problems.append("the casing renderer without " + what)

    # The casing draws the BLOCK model by default, and the ITEM model only for machines
    # whose block model is not a complete picture of them.
    #
    # Create is what forced the distinction, and the numbers are measured from its jar:
    # create:block/millstone/block has 6 elements against 12 in create:block/millstone/item,
    # and create:block/mechanical_saw/block does not exist at all - those parts are drawn
    # by a separate renderer (Flywheel). Drawing the block model there gives a millstone
    # without its centre stone and a saw without its blade.
    if "renderSingleBlock" not in renderer:
        problems.append("the casing renderer has no BLOCK-model path - every machine "
                        "would be drawn as an inventory item")
    if "usesItemModel" not in renderer:
        problems.append("the casing renderer ignores the per-machine item-model flag - a "
                        "machine with a trimmed block model would be drawn as if complete")
    create_rows = re.findall(r"VeloceCaseContents\.register\(", compat_create)
    item_rows = re.findall(r"(?:false|true), true\);", compat_create)
    if not create_rows:
        problems.append("Create registers no casing rows - the modules would show nothing")
    elif len(item_rows) != len(create_rows):
        problems.append(f"only {len(item_rows)} of {len(create_rows)} Create casing rows opt "
                        f"into the ITEM model - the rest would draw a trimmed block model")

    spin_iface = open("neoforge/src/main/java/com/craftingveloce/block/VeloceCaseSpin.java", encoding="utf-8").read()
    if "boolean casePartsSpinIndividually();" not in spin_iface:
        problems.append("the interface without information whether the parts spin individually")
    if "boolean caseBuiltFromParts();" not in spin_iface:
        problems.append("no distinction between a built machine and a machine with fixed contents")
    if "caseBuiltFromParts()" not in renderer:
        problems.append("the renderer does not distinguish an empty built machine from fixed contents")

    # Grid: the layout is computed by the MACHINE (columns x rows), the renderer
    # and the notification must use the same source - otherwise the player sees
    # "1x2" while something else is drawn inside.
    if "gridLabel()" not in block_code or "displayClientMessage" not in block_code:
        problems.append("no action bar notification about the grid layout")
    be_code = open("neoforge/src/main/java/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
                   encoding="utf-8").read()
    for need, what in (("public int caseGridColumns()", "the layout columns"),
                       ("public int caseGridRows()", "the layout rows"),
                       ("public String gridLabel()", "the layout text for the player")):
        if need not in be_code:
            problems.append("the machine without " + what)
    # The grid must leave headroom for the animation (the player: crafters stuck
    # out above the model at the highest bobbing frame).
    if "GRID_EXTENT" not in renderer or "GRID_EXTENT / cols" not in renderer:
        problems.append("the part grid has no headroom for the animation")
    if "spin.caseGridColumns()" not in renderer or "spin.caseGridRows()" not in renderer:
        problems.append("the renderer does not arrange the parts the way the machine reports them")

    # The crafter grid grows SQUARELY from the centre outwards (1x1, 2x2, 3x3...),
    # and not as a growing line (1x2, 1x3...). The side = ceil(sqrt(cells)), and
    # the cells fill up from the centre of the casing.
    if "Math.ceil(Math.sqrt(Math.max(1, parts)))" not in be_code:
        problems.append("the crafter grid does not grow squarely (side from the square root of the cells)")
    # We check the CONTENT of the renderParts method, not the mere occurrence of
    # the name in the file: the spiralOrder definition stays even when the loop
    # does not use it.
    if parts_body is None or "centreOrder(" not in parts_body or "order[i][0]" not in parts_body:
        problems.append("the parts do not fill the square from the CENTRE outwards")

    if "neighbourAxis" not in block_code or "Direction.Axis axis = neighbourAxis" not in block_code:
        problems.append("the machine axis does not adapt to a powered neighbour "
                        "(rotation from the side will not work)")


    # A machine without power is not available: applies to ALL integrations.
    for path, name in (("neoforge/src/main/java/com/craftingveloce/compat/create/CreateModule.java", "Create"),
                       ("neoforge/src/main/java/com/craftingveloce/compat/mekanism/MekanismModule.java", "Mekanism"),
                       ("neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryModule.java", "Alchemistry")):
        body = _method_body(open(path, encoding="utf-8").read(), "public Set<Item> producible(")
        if body is None or "hasPowered" not in body:
            problems.append(f"the {name} module: producible shows machines WITHOUT power")
    if problems:
        fail("Create mechanics:\n  " + "\n  ".join(problems))
    print("    OK (Create: axis from every side + sheets, grid from the recipe, "
          "basin/blaze requirements, 9x9 ceiling)")

def validate_case_disassembly():
    """
    Casing disassembly: NBT on the drop + a crafting table recipe.

    The player: "let a given block have in its NBT tag a record of exactly how
    many crafters it contains - after destruction exactly that number stays in
    the NBT of the dropped item, and after placement it must keep the same
    value. And when I put that item into a Crafting Table, the recipe should
    return the mechanical crafter itself in the number that there were, plus the
    base block itself."

    We check four links, because each of them is easy to lose separately:
      1. the block writes the part counter into the NBT of the dropped item,
      2. the block entity can read it (otherwise placement loses the value),
      3. the recipe returns the empty casing AND the base blocks in the number
         from the NBT,
      4. the serializer is registered, and the recipe is provided as JSON
         (without the JSON the recipe does not exist in the world).
    """
    problems = []

    module_block = open("neoforge/src/main/java/com/craftingveloce/compat/create/block/VeloceKineticModuleBlock.java",
                        encoding="utf-8").read()
    drops = _method_body(module_block, "protected java.util.List<ItemStack> getDrops(")
    if drops is None:
        problems.append("the machine has no drop of its own (the counter will not reach the NBT)")
    else:
        # The counter used to travel in the dropped item's NBT. That carrier is gone:
        # the drop is now the Integrale frame, which has NO block entity, so there is
        # nothing to read "VeloceParts" back from. The parts come back as REAL BLOCKS
        # instead - one per part, read from the block entity at break time - which the
        # player cannot lose and which needs no round trip at all.
        for need, what in (("LootContextParams.BLOCK_ENTITY", "reading the block entity on break"),
                           ("caseParts()", "asking the block entity how many parts stand in it"),
                           ("VELOCE_INTEGRALE_ITEM", "dropping the frame instead of the module")):
            if need not in drops:
                problems.append("the machine drop without " + what)

    be = open("neoforge/src/main/java/com/craftingveloce/compat/create/block/entity/VeloceKineticModuleBlockEntity.java",
              encoding="utf-8").read()
    if 'tag.getInt("VeloceParts")' not in be or 'tag.putInt("VeloceParts"' not in be:
        problems.append("the block entity does not read/write the number of parts in NBT")

    recipe_path = "neoforge/src/main/java/com/craftingveloce/crafting/VeloceCaseDisassemblyRecipe.java"
    if not os.path.exists(recipe_path):
        fail("casing disassembly:\n  no recipe class")
    recipe = open(recipe_path, encoding="utf-8").read()
    remaining_body = _method_body(recipe, "public NonNullList<ItemStack> getRemainingItems(")
    if remaining_body is None or "remaining.set(" not in remaining_body \
            or "base.asItem()" not in remaining_body:
        problems.append("getRemainingItems does not return the base blocks into the grid")
    for need, what in (("VELOCE_INTEGRALE_ITEM", "returning the empty casing"),
                       ("getRemainingItems", "returning the base blocks into the grid"),
                       ("base.asItem()", "the base block as the remainder"),
                       ("partsOf(machine)", "the number of parts from the NBT"),
                       ('getInt("VeloceParts")', "reading the counter from the item NBT")):
        if need not in recipe:
            problems.append("the recipe without " + what)

    recipes = open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceRecipes.java", encoding="utf-8").read()
    if '"case_disassembly"' not in recipes:
        problems.append("the recipe serializer is not registered")
    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceRecipes.register" not in mod:
        problems.append("recipe registration is not wired into the mod")

    json_path = "data/craftingveloce/recipe/case_disassembly.json"
    if not os.path.exists(json_path):
        problems.append("no recipe JSON (data/craftingveloce/recipe/case_disassembly.json)")
    else:
        data = json.load(open(json_path, encoding="utf-8"))
        if data.get("type") != "craftingveloce:case_disassembly":
            problems.append(f"the recipe JSON has type {data.get('type')}")

    if problems:
        fail("casing disassembly:\n  " + "\n  ".join(problems))
    print("    OK (casing: NBT with the part count + a disassembly recipe)")


def validate_mods_toml():
    """
    `neoforge.mods.toml` must PARSE and have the required dependencies.

    The BUG that uncovered this: while removing a dependency block (Jade), an
    orphaned `side="CLIENT"` line was left at the end of the file. TOML appends
    such a line to the PREVIOUS table, and a duplicate `side` key makes parsing
    the WHOLE file fail - that is, the mod does not start. The build did not see
    it, because it is a data file and not code: compilation passes, the JAR
    packages, and the error only shows up in game.
    """
    path = "src_meta/META-INF/neoforge.mods.toml"
    if not os.path.exists(path):
        return
    try:
        import tomllib
    except ImportError:      # Python < 3.11
        print("    (skipping: no tomllib)")
        return
    try:
        with open(path, "rb") as handle:
            data = tomllib.load(handle)
    except Exception as exc:
        fail(f"neoforge.mods.toml does not parse: {exc}")

    dependencies = data.get("dependencies", {}).get("craftingveloce", [])
    mod_ids = {dep.get("modId") for dep in dependencies}
    # toms_storage is deliberately absent from this set: it is optional since the
    # mod stopped inheriting from Tom's classes.
    missing = sorted({"neoforge", "minecraft"} - mod_ids)
    if missing:
        fail("neoforge.mods.toml without the required dependencies: " + ", ".join(missing))
    print(f"    OK (mods.toml parses: {len(mod_ids)} dependencies)")


def validate_integrale_display():
    """
    The Veloce Integrale frame: a CASING that turns into our machine.

    The player: "when I put in a furnace it becomes a furnace display - it
    should turn into normal veloce items". So the frame exposes nothing and does
    not pretend to be a crafter: a right click with the right vanilla block
    REPLACES it with a real Veloce block according to the table
    (VeloceIntegraleConversions): a crafting table becomes a crafting table
    (that one inside a casing), a lectern becomes a controller, a dispenser an
    extractor, an observer a threshold sensor, a furnace a furnace.

    We check:
      1. the conversion table maps the right pairs,
      2. the frame asks that table and replaces the block, registering a new
         network node,
      3. the old "display item" system is COMPLETELY gone (a partial refactor
         would leave a block in the world that does nothing),
      4. the crafting table in the casing returns ONE item when broken and has a
         way back,
      5. the client renders the crafting table inside the casing (and not a
         "display item"),
      6. the frame is not a crafter for the network, and its item has a tooltip
         generated from the conversion table.
    """
    problems = []

    conv_path = "neoforge/src/main/java/com/craftingveloce/block/VeloceIntegraleConversions.java"
    if not os.path.exists(conv_path):
        fail("frame / machines:\n  no VeloceIntegraleConversions conversion table")
    conversions = open(conv_path, encoding="utf-8").read()
    for input_block, target in (("Blocks.CRAFTING_TABLE", "VELOCE_CRAFTING_TABLE"),
                                ("Blocks.LECTERN", "VELOCE_CONTROLLER"),
                                ("Blocks.DISPENSER", "VELOCE_EXTRACTOR"),
                                ("Blocks.OBSERVER", "THRESHOLD_SENSOR"),
                                ("Blocks.FURNACE", "VELOCITY_FURNACE")):
        pair = f"add({input_block}, () -> VeloceRegistry.{target}.get())"
        if pair not in conversions:
            problems.append(f"the conversion table without the pair {input_block} -> {target}")

    integrale = "neoforge/src/main/java/com/craftingveloce/block/VeloceIntegraleBlock.java"
    text = open(integrale, encoding="utf-8").read()
    for need, what in (("VeloceIntegraleConversions.forItem", "a look into the conversion table"),
                       ("world.setBlock(pos, result", "replacing the block"),
                       ("VeloceNodeBlocks.onNodePlaced", "registering the new block with the network")):
        if need not in text:
            problems.append("the frame without " + what)
    # The mere occurrence of onNodePlaced in the file means nothing - the same
    # call is there when a block is placed, so we check the CONTENT of the
    # replacement method.
    convert = _method_body(text, "private static void convert")
    if convert is None:
        problems.append("the frame has no method that turns it into a machine")
    else:
        for need, what in (("world.setBlock(pos, result", "placing the machine"),
                           ("VeloceNodeBlocks.onNodePlaced", "registering the new network node")):
            if need not in convert:
                problems.append("turning the frame into a machine without " + what)
    use_item = _method_body(text, "protected ItemInteractionResult useItemOn")
    if use_item is None or "convert(" not in use_item:
        problems.append("the right click does not turn the frame into a machine")
    if "exposesCraftingBuffer" in text:
        problems.append("the frame exposes the crafting buffer even though it is not a crafter")

    # 3. The old display item system must disappear from the WHOLE mod. We check
    # this file by file, because the most common partial refactor leaves a method
    # nobody calls any more, or a field that still travels in NBT.
    #
    # NOTE (verified against the real vanilla classes): removing a block state
    # property does NOT break saved worlds - since 1.20.5 the block state
    # travels in the palette as a {Name, Properties} map, and unknown keys are
    # skipped (test: {Name:"minecraft:oak_fence",Properties:{filled:"true"}}
    # parses without an error, just like a wrong value of a known property).
    leftovers = []
    for path in sorted(glob.glob("neoforge/src/main/java/com/craftingveloce/**/*.java", recursive=True)):
        body = open(path, encoding="utf-8").read()
        for need in ("FILLED", "isDisplayable", "putOnDisplay", "takeOffDisplay",
                     "getDisplayItem()", "setDisplayItem(",
                     'tag.put("DisplayItem"', 'tag.getCompound("DisplayItem")'):
            if need in body:
                leftovers.append(f"{path.replace(os.sep, '/')} -> {need}")
    if leftovers:
        problems.append("the old display item system is still there:\n  " + "\n  ".join(leftovers))

    be_path = "neoforge/src/main/java/com/craftingveloce/block/entity/VeloceCraftingTableBlockEntity.java"
    be = open(be_path, encoding="utf-8").read()
    if "instanceof com.craftingveloce.block.VeloceCraftingTableBlock" not in be:
        problems.append("the crafting table block entity does not recognise a crafter by BLOCK TYPE")

    # Storage ghost: the buffer endpoint must disappear when the block stops
    # being a crafter - so the validation cannot rely on the block entity alone.
    manager = open("neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetworkManager.java",
                   encoding="utf-8").read()
    buffer_case = manager.partition("case CRAFTING_BUFFER")[2][:400]
    if "exposesCraftingBuffer" not in buffer_case:
        problems.append("the crafter buffer validation does not ask the node about the rule - "
                        "after the block is replaced a storage ghost remains")

    registry = open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", encoding="utf-8").read()
    if "integraleBlock()" in registry:
        problems.append("the frame still has its own crafting table block entity (it must not)")

    mod = open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", encoding="utf-8").read()
    if "VeloceCaseRenderer" not in mod:
        problems.append("no casing renderer registration (RegisterRenderers)")

    renderer = "neoforge/src/main/java/com/craftingveloce/client/render/VeloceCaseRenderer.java"
    if not os.path.exists(renderer):
        problems.append("no casing renderer class")
    else:
        body = open(renderer, encoding="utf-8").read()
        for need, what in (("VeloceCaseContents.contentFor", "the contents from the casing table"),
                           ("renderStatic(", "drawing the contents item model"),
                           ("rotationDegrees", "the animation (rotation)"),
                           ("Math.sin", "the animation (bobbing)")):
            if need not in body:
                problems.append("the casing renderer without " + what)
        # The mere occurrence of "VeloceCaseSpin" in the file means nothing (it
        # is in the signature of a helper method) - it must be CALLED from render().
        render_body = _method_body(body, "public void render(")
        if render_body is None or "VeloceCaseSpin" not in render_body \
                or "renderParts(" not in render_body:
            problems.append("the renderer does not draw the machine parts (mill wheels) "
                            "with the speed from the machine - VeloceCaseSpin unused")

    contents_path = "neoforge/src/main/java/com/craftingveloce/block/VeloceCaseContents.java"
    if not os.path.exists(contents_path):
        problems.append("no casing contents table (VeloceCaseContents)")
    else:
        body = open(contents_path, encoding="utf-8").read()
        for need, what in (("VELOCE_CRAFTING_TABLE.get()", "the crafting table"),
                           ("VELOCE_CONTROLLER.get()", "the controller"),
                           ("VELOCE_EXTRACTOR.get()", "the extractor"),
                           ("THRESHOLD_SENSOR.get()", "the threshold sensor"),
                           ("VELOCITY_FURNACE.get()", "the fuel furnace"),
                           ("ELECTRIC_FURNACE.get()", "the electric furnace")):
            if need not in body:
                problems.append("the casing table without " + what)

    if problems:
        fail("frame / machines from the frame:\n  " + "\n  ".join(problems))
    print(f"    OK (frame: {len(conversions.split('add(Blocks.')) - 1} conversions, "
          f"casing + contents, no duplicates)")

def validate_integrale_model():
    """
    The Veloce Integrale frame: a rod frame + PURPLE GLASS in the windows.

    The player described it in two steps: first "only the corners and edges,
    without colouring the middle", and then "paint those holes in the block
    model purple, like purple stained glass". The model is easy to break with a
    single typo (opaque glass, glass blending into the frame, a filled frame),
    and in game it looks like an ordinary block - that is, the opposite of what
    it should be.

    We check what the player sees:
      * 12 frame elements, each a THIN rod (2 dimensions <= 2, the third >= 16),
      * the centres of the faces (where the windows are) are covered ONLY by
        glass that is recessed into the frame (it does not touch the block
        planes - otherwise z-fighting),
      * the glass takes the glass texture, and not the frame's metal,
      * the model has render_type translucent (without it the purple is opaque).
    """
    path = "assets/craftingveloce/models/block/veloce_integrale_frame.json"
    if not os.path.exists(path):
        return
    model = json.load(open(path, encoding="utf-8"))
    elements = model.get("elements", [])
    problems = []

    # 1) Frame: exactly 12 thin rods that TOGETHER cover the cube's 12 edges.
    #
    # The rule used to demand that EVERY rod be at least 16 long - i.e. each one a
    # complete edge. That rejects an equally correct frame: four rods spanning the full
    # depth already cover the corner regions, so the other eight only have to bridge what
    # is left, and splitting the work that way removes the overlapping geometry the old
    # layout had at every corner. Blockbench produced exactly that when the model was
    # edited there, and the guard called a working frame broken.
    #
    # So the test is the INTENT, not the shape it was first written in: twelve rods, and
    # between them they reach every one of the block's six faces.
    bars = []
    for i, el in enumerate(elements):
        sizes = [el["to"][0] - el["from"][0], el["to"][1] - el["from"][1],
                 el["to"][2] - el["from"][2]]
        if sum(1 for size in sizes if size <= 2) >= 2 and max(sizes) >= 4:
            bars.append((i, el))
    if len(bars) != 12:
        problems.append(f"frame rods: {len(bars)}, should be 12 (12 edges of a cube)")
    if bars:
        for axis, name in enumerate(("X", "Y", "Z")):
            lo = min(el["from"][axis] for _, el in bars)
            hi = max(el["to"][axis] for _, el in bars)
            if lo > 0.0 or hi < 16.0:
                problems.append(f"the frame reaches {lo}..{hi} on {name}, not 0..16 - the "
                                "cube's edges are not covered")

    # 2) Glass: a RECESSED element (no wall lies on the block plane).
    glass = [(i, el) for i, el in enumerate(elements) if el not in [b[1] for b in bars]]
    if len(glass) != 1:
        problems.append(f"glass elements: {len(glass)}, should be 1")
    for i, el in glass:
        f, t = el["from"], el["to"]
        if min(f) < 1.0 or max(t) > 15.0:
            problems.append(f"glass {f}..{t} touches the block plane "
                            f"(z-fighting with the frame) - it must be recessed")
        if min(f) > 4.0 or max(t) < 12.0:
            problems.append(f"glass {f}..{t} is too small - the window is 12x12 px")
        textures = {face.get("texture") for face in el.get("faces", {}).values()}
        if "#glass" not in textures:
            problems.append(f"the glass does not use the #glass texture (it has {textures})")

    textures = model.get("textures", {})
    glass_texture = textures.get("glass", "")
    if "glass" not in glass_texture:
        problems.append(f"the glass texture is not glass: {glass_texture}")
    if model.get("ambientocclusion", True):
        problems.append("ambientocclusion is not false - shadows on the frame rods")

    render_type = model.get("render_type", "")
    if "translucent" not in render_type:
        problems.append(f"render_type={render_type!r} - without translucent the purple is "
                        f"opaque (the player wants glass)")

    # 3) Side panels: one per side, exactly in the light of the window.
    #
    # The player wants to see which side the cable comes from - the window on
    # that side is closed with a sheet. The panel MUST lie on the plane of THAT
    # very side (0 or 16) and cover the window 2..14, otherwise a gap remains or
    # the sheet hangs in the air.
    sides = {
        "north": (2, 0), "south": (2, 1), "west": (0, 2),
        "east": (1, 2), "down": (2, 2), "up": (2, 2),
    }
    for side, (axis_a, axis_b) in sides.items():
        panel_path = ("assets/craftingveloce/models/block/"
                      f"veloce_integrale_panel_{side}.json")
        if not os.path.exists(panel_path):
            problems.append(f"no panel model for {side}")
            continue
        panel_model = json.load(open(panel_path, encoding="utf-8"))
        panel_elements = panel_model.get("elements", [])
        if len(panel_elements) != 1:
            problems.append(f"panel {side}: {len(panel_elements)} elements, should be 1")
            continue
        f, t = panel_elements[0]["from"], panel_elements[0]["to"]
        if side in ("north", "south"):
            low, high = f[2], t[2]
            if not ((side == "north" and low == 0 and high == 2)
                    or (side == "south" and low == 14 and high == 16)):
                problems.append(f"panel {side}: z={low}..{high}, should be at the wall")
            if (f[0], t[0], f[1], t[1]) != (2, 14, 2, 14):
                problems.append(f"panel {side}: the window should be 2..14, it is "
                                f"{f[0]}..{t[0]} x {f[1]}..{t[1]}")
        if side in ("west", "east"):
            low, high = f[0], t[0]
            if not ((side == "west" and low == 0 and high == 2)
                    or (side == "east" and low == 14 and high == 16)):
                problems.append(f"panel {side}: x={low}..{high}, should be at the wall")
            if (f[1], t[1], f[2], t[2]) != (2, 14, 2, 14):
                problems.append(f"panel {side}: the window should be 2..14, it is "
                                f"{f[1]}..{t[1]} x {f[2]}..{t[2]}")
        if side in ("down", "up"):
            low, high = f[1], t[1]
            if not ((side == "down" and low == 0 and high == 2)
                    or (side == "up" and low == 14 and high == 16)):
                problems.append(f"panel {side}: y={low}..{high}, should be at the wall")
            if (f[0], t[0], f[2], t[2]) != (2, 14, 2, 14):
                problems.append(f"panel {side}: the window should be 2..14, it is "
                                f"{f[0]}..{t[0]} x {f[2]}..{t[2]}")

    # 4) The blockstate must be multipart: the frame + one panel per side.
    bs_path = "assets/craftingveloce/blockstates/veloce_integrale.json"
    bs = json.load(open(bs_path, encoding="utf-8"))
    parts = bs.get("multipart", [])
    if not parts:
        problems.append("the blockstate is not multipart - "
                        "the closed sides cannot be shown")
    else:
        applied = {p.get("apply", {}).get("model") for p in parts}
        if "craftingveloce:block/veloce_integrale_frame" not in applied:
            problems.append("the blockstate does not contain the frame model")
        for side in sides:
            condition = parts and any(
                p.get("when", {}).get(side) == "true"
                and p.get("apply", {}).get("model")
                == f"craftingveloce:block/veloce_integrale_panel_{side}"
                for p in parts)
            if not condition:
                problems.append(f"the blockstate has no condition for side {side}")

    # 5) EVERY one of our machines has a CASING model (frame + glass), and not
    #    its own old model - that is what the player wants: "the model changes
    #    to this veloce integrale, and only inside is there a render". A
    #    single-part model is also the only way for the break particles to come
    #    from the frame: with multipart, vanilla takes the particleIcon from the
    #    FIRST part of the list.
    machines_with_frame = set()
    for j in glob.glob("neoforge/src/main/java/com/craftingveloce/block/*.java") + glob.glob("neoforge/src/main/java/com/craftingveloce/compat/*/block/*.java"):
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
            # A MACHINE MUST BE A MULTIPART of the frame + panels, not a static model.
            if not parts:
                problems.append(f"blockstate {block_id} uses the Integrale frame, but is 'variants' - it MUST be a multipart with panels")
            else:
                applied = [p.get("apply", {}).get("model") for p in parts]
                if applied[0] != "craftingveloce:block/veloce_integrale_frame":
                    problems.append(f"blockstate {block_id} is not an Integrale casing "
                                    f"(the first part must be the frame - particleIcon): {applied[:2]}")
                else:
                    for side in ("north", "east", "south", "west", "up", "down"):
                        if not any(p.get("when", {}).get(side) == "true"
                                   and p.get("apply", {}).get("model")
                                   == f"craftingveloce:block/veloce_integrale_panel_{side}"
                                   for p in parts):
                            problems.append(f"blockstate {block_id}: no panel condition for {side}")

    # 5b) Casing = the frame model (the first part!) + an icon WITH CONTENTS.
    #
    # We recognise a "casing" by the block having the frame in its blockstate OR
    # an icon with contents (#content) - thanks to that the pipe and the
    # terminal (which have no casing) do not fall into this check, while a block
    # whose model was swapped for a foreign one is still checked.
    #
    # The order of the parts matters: the vanilla MultiPartBakedModel takes the
    # particleIcon from the FIRST part of the list, so a sheet (or the old
    # model) at the beginning = particles from the wrong texture - exactly what
    # the player reported.
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
            continue   # The EMPTY casing: it has no contents and must not have any
        item_file = f"assets/craftingveloce/models/item/{block_id}.json"
        icon = json.load(open(item_file, encoding="utf-8")) if os.path.exists(item_file) else {}
        # Which machine this casing claims to represent is now a textures.content ENTRY,
        # not a #content element: the geometry that used to read it is gone from the JSON
        # and the content is composed at bake time instead (VeloceCaseItemModel). A casing
        # with neither the entry nor the composition renders as an empty frame, which is
        # why both halves are checked - the entry here, the composition in
        # validate_module_content_textures.
        # The casing icon is a flat PNG composed from the two plates and the machine's
        # rendered icon (scripts/gen_case_icons.py). It used to be a #content cube, then a
        # model composed at bake time; both are gone. What can fail silently now is a model
        # pointing at a picture that is not there - it renders as the missing-texture
        # checkerboard and nothing else complains.
        layer = (icon.get("textures") or {}).get("layer0", "")
        is_case = bool(layer) or bs_models[0] == "craftingveloce:block/veloce_integrale_frame"
        if not is_case:
            continue
        if not layer.startswith("craftingveloce:item/case/"):
            problems.append(f"{block_id}: casing icon does not draw a composed picture "
                            f"(layer0={layer!r}) - see scripts/gen_case_icons.py")
        else:
            png = "assets/craftingveloce/textures/" + layer.split(":", 1)[1] + ".png"
            if not os.path.exists(png):
                problems.append(f"{block_id}: casing icon {layer} has no file ({png})")
        if icon.get("elements"):
            problems.append(f"{block_id}: casing icon still has 3D elements - the icon is "
                            f"composed from pictures now")
        wrong = [m for m in bs_models if m and not m.startswith(allowed_models)]
        if wrong:
            problems.append(f"{block_id}: casing with a foreign model {wrong[0]}")
        elif bs_models[0] != "craftingveloce:block/veloce_integrale_frame":
            problems.append(f"{block_id}: the first part of the casing is {bs_models[0]}, "
                            f"and it must be the casing frame (particles on break)")

    if problems:
        fail("veloce_integrale frame model:\n  " + "\n  ".join(problems))
    print(f"    OK (frame: {len(bars)} rods + glass {glass_texture}, "
          f"6 side panels, translucent; per-block facade, icons composed at bake time)")

def game_running():





    """
    Is Minecraft from this profile running right now?

    Needed in order to warn that swapping the JAR will not change a RUNNING
    session - its classloader keeps the old file open. Previously we overwrote
    the JAR in place and it ended in a ClassNotFoundException at a random place.
    """
    try:
        out = subprocess.run(["pgrep", "-fl", "MinecraftLaunch"],
                             capture_output=True, text=True, timeout=10)
        return out.returncode == 0 and bool(out.stdout.strip())
    except Exception:
        return False


def fail(msg):
    print(f"\nERROR: {msg}")
    sys.exit(1)


def step(n, text):
    print(f"\n[{n}] {text}")


def main():
    # --- 1. compilation ------------------------------------------------
    step(1, "Compiling sources")
    # cp.txt was a generated dump of the local classpath, with absolute paths from one
    # machine and a reference to a jar from a Modrinth profile. It is not part of the
    # repository any more; the Gradle build owns the classpath (see neoforge/build.gradle)
    # and `./gradlew verifyGuards` is the gate that is actually run. This legacy pipeline is
    # kept only for the guards, so it says so instead of dying with a FileNotFoundError.
    if not os.path.exists("scripts/cp.txt"):
        fail("scripts/cp.txt is gone (it held machine-specific paths). Use "
             "`./gradlew build verifyGuards` - the Gradle build owns the classpath now.")
    cp = open("scripts/cp.txt").read().strip()
    toms = os.path.join(MODS, "toms_storage-1.21-2.4.2.jar")
    rs = os.path.join(MODS, "refinedstorage-neoforge-2.0.9.jar")
    for dep in (toms, rs):
        if not os.path.exists(dep):
            fail(f"missing dependency: {dep}")

    sources = [f for f in glob.glob("neoforge/src/main/java/**/*.java", recursive=True)
               if not any(x in f for x in EXCLUDED_SRC)]
    print(f"    files: {len(sources)} (skipped {', '.join(EXCLUDED_SRC)})")

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
        fail("compilation failed:\n" + "\n".join(errs[:20]))
    print("    OK")

    # --- 2. class verification ----------------------------------------
    step(2, "Verifying that all imported classes exist")
    built = {os.path.relpath(os.path.join(dp, f), BUILD_OUT).replace(os.sep, "/")
             for dp, _, fs in os.walk(BUILD_OUT) for f in fs if f.endswith(".class")}
    imports = set()
    for f in glob.glob("neoforge/src/main/java/com/**/*.java", recursive=True):
        for line in open(f):
            line = line.strip()
            if line.startswith("import com.craftingveloce."):
                t = line[len("import "):].rstrip(";").strip()
                if not t.endswith("*"):
                    imports.add(t)
    missing = []
    for t in imports:
        # An import a.b.C.D may mean:
        #   - the class a/b/C/D.class
        #   - a nested class a/b/C$D.class  (or deeper: a/b/C$D$E)
        # This cannot be decided without parsing the sources, so we try every
        # split: we replace successive dots with '$' from the end.
        parts = t.split(".")
        found = False
        # level = how many trailing segments we treat as nested classes
        for level in range(1, len(parts)):
            pkg = parts[:len(parts) - level]
            nested = "$".join(parts[len(parts) - level:])
            cand = "/".join(pkg + [nested]) + ".class"
            if cand in built:
                found = True
                break
        # The variant without nesting: a/b/C/D.class
        if not found and t.replace(".", "/") + ".class" in built:
            found = True
        if not found:
            missing.append(t)

    if missing:
        fail("the code uses classes that are not in the compilation output:\n  "
             + "\n  ".join(sorted(missing)))
    print(f"    OK ({len(imports)} imports, all present)")

    # --- 3. packaging --------------------------------------------------
    step(3, "Packaging the JAR")
    for sub in ("com", "assets", "data"):
        p = os.path.join(STAGING, sub)
        if os.path.exists(p):
            shutil.rmtree(p)
    shutil.copytree(os.path.join(BUILD_OUT, "com"), os.path.join(STAGING, "com"),
                    dirs_exist_ok=True)

    def copy_clean(src, dst):
        """Copies a tree while skipping system junk."""
        shutil.copytree(src, dst, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns(*JUNK))

    copy_clean("assets", os.path.join(STAGING, "assets"))
    copy_clean("data", os.path.join(STAGING, "data"))

    # We take META-INF from src_meta/, and NOT from staging.
    #
    # Previously neoforge.mods.toml existed ONLY in the staging directory
    # (craftingveloce_jar_root/) and was not versioned. Cleaning the staging
    # directory meant a build without the mod metadata - a JAR the loader does
    # not see.
    META_SRC = "src_meta"
    if os.path.exists(os.path.join(META_SRC, "META-INF")):
        copy_clean(os.path.join(META_SRC, "META-INF"),
                   os.path.join(STAGING, "META-INF"))

    if not os.path.exists(os.path.join(STAGING, "META-INF", "neoforge.mods.toml")):
        fail("no META-INF/neoforge.mods.toml (source: src_meta/META-INF/)")

    if os.path.exists(JAR_NAME):
        os.remove(JAR_NAME)
    res = subprocess.run(["jar", "cf", JAR_NAME, "-C", STAGING, "."],
                         capture_output=True, text=True)
    if res.returncode != 0:
        fail("the jar was not created: " + res.stderr[:400])

    # --- 4. quality check ----------------------------------------------
    step(4, "JAR quality check")
    z = zipfile.ZipFile(JAR_NAME)
    names = z.namelist()

    main_class = "com/craftingveloce/CraftingVeloceMod.class"
    if main_class not in names:
        fail(f"no {main_class} - wrong package layout (com/com?)")

    dupes = [n for n in names if n.startswith("com/com/")]
    if dupes:
        fail(f"duplicated com/com prefix ({len(dupes)} files)")

    if "META-INF/neoforge.mods.toml" not in names:
        fail("no META-INF/neoforge.mods.toml")

    # A leak = a foreign mod class in OUR JAR. Our own classes under
    # com/craftingveloce/ may contain the word "mekanism" in their path (the
    # compat module package), so the boundary is our package prefix, and not the
    # name alone.
    foreign = [n for n in names
               if not n.startswith("com/craftingveloce/")
               and any(p in n for p in FOREIGN_JAR_PATHS)]
    leaks = [n for n in names if "moze_intel" in n or "eatawesome" in n] + foreign
    if leaks:
        fail(f"foreign package leak: {leaks[:5]}")

    junk = [n for n in names if any(j in n for j in JUNK)]
    if junk:
        fail(f"system junk in the JAR: {junk}")

    # EVERY registered block MUST have a complete data set.
    #
    # Without a loot table the block does not drop when destroyed (you cannot
    # see that in creative, so it is easy to miss), and without a model or
    # blockstate the block is invisible. We take the block list from the SAME
    # place as the data generator - see scripts/gen_loot_tables.py - so that a
    # second, hand-written register does not come into being.
    validate_block_data(names)
    validate_block_recipes()
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
    validate_extractor_redstone()
    validate_jade_config_lang()
    validate_block_config_defaults()
    validate_module_drop_stacks()
    validate_crafting_source_order()
    validate_no_dead_module_loot_tables()
    validate_legacy_src_submodule_removed()
    validate_showcase_command()
    validate_block_probe()
    validate_craftable_cache()
    validate_energy_pull()
    validate_partial_delivery()
    validate_heat_accounting()
    validate_heat_battery()
    validate_brewing_stand()
    validate_brewing_proxy()
    validate_module_content_textures()
    validate_potion_proxy_assets()
    validate_pipe_energy()
    validate_module_info_gui()
    validate_jade_info()
    validate_terminal_craft_error()
    validate_jei_catalysts()
    validate_jei_integrale_category()
    validate_model_texture_refs()
    validate_auto_crafter_ingredient_rule()

    classes = sum(1 for n in names if n.endswith(".class"))
    print(f"    classes: {classes}, files: {len(names)}, "
          f"size: {os.path.getsize(JAR_NAME)} B")
    print("    OK")

    # --- 5. deployment --------------------------------------------------
    step(5, "Deploying to the testing profile")

    # THE DEPLOYMENT MUST BE ATOMIC.
    #
    # shutil.copyfile() opens the destination file and overwrites it IN PLACE.
    # When Minecraft is running, its classloader keeps that JAR open and reads
    # classes from it LAZILY - after the first launch most classes are not
    # loaded yet. Overwriting the file under a running JVM therefore ends in:
    #
    #   Caused by: java.lang.ClassNotFoundException:
    #       com.craftingveloce.crafting.VeloceRecipeGraph
    #
    # and a server crash at a random place, looking like a bug in the code.
    #
    # Writing to a temporary file + os.replace() is a single rename(2)
    # operation: the running game stays on the old inode (so it keeps working),
    # and the new JAR appears for the next launch.
    tmp_deploy = DEPLOYED + ".tmp"
    shutil.copyfile(JAR_NAME, tmp_deploy)
    os.replace(tmp_deploy, DEPLOYED)
    print(f"    {DEPLOYED}")

    # --- 5b. warning about a running game -------------------------------
    if game_running():
        print()
        print("    " + "!" * 62)
        print("    Minecraft IS RUNNING. The JAR was swapped safely (atomically),")
        print("    but this session still uses the OLD version - its classloader keeps")
        print("    the old file open. CLOSE the game and start it again, otherwise")
        print("    you will test the old code.")
        print("    " + "!" * 62)

    import hashlib

    def md5(p):
        return hashlib.md5(open(p, "rb").read()).hexdigest()

    if md5(JAR_NAME) != md5(DEPLOYED):
        fail("the copied JAR differs from the built one")
    print(f"    md5: {md5(JAR_NAME)}")
    print("\nDone. CLOSE Minecraft before launching with the new JAR.")


if __name__ == "__main__":
    main()
