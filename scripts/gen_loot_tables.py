#!/usr/bin/env python3
"""Generates loot tables and "mineable" tags for ALL registered blocks.

Why a generator instead of hand-written files
---------------------------------------------
Without a loot table a block does not drop when destroyed - in survival mode
the player loses a pipe, a terminal or an extractor irrecoverably (in creative
you do not see it, so it is easy to overlook). This is exactly the same class
of bug that has already hit this project four times: a list rewritten BY HAND
in a second place, which drifts apart over time. That is why the block list is
NOT written out here - we read it from the registry (VeloceRegistry.java),
which is the single source of truth.

The script is idempotent:
  * missing loot tables are added,
  * EXISTING loot tables are left alone (the hand-tuned ones survive),
  * mineable tags are rewritten in full from the registry.

Usage:  python3 scripts/gen_loot_tables.py
"""

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "src/com/craftingveloce/init/VeloceRegistry.java")

# Block registries of the optional modules (compat/<mod>/XBlocks.java). We read
# them ALSO when the mod is absent: the data (loot table, mineable tag) are
# ordinary JSON files and do no harm when the block does not register. Without
# this a new module block would get no loot table - and that is a bug visible
# only in game, after the block is destroyed.
COMPAT_REGISTRY_GLOB = "src/com/craftingveloce/compat/*/*Blocks.java"
LOOT_DIR = os.path.join(ROOT, "data/craftingveloce/loot_table/blocks")
TAG_DIR = os.path.join(ROOT, "data/minecraft/tags/block/mineable")

MODID = "craftingveloce"

# The tool with which the block is mined fastest. Listed EXPLICITLY so that a
# new block with no decision immediately screams a warning instead of quietly
# getting a pickaxe.
TOOL_BY_BLOCK = {
    "veloce_tom_terminal": "axe",        # the terminal's wooden casing
    "veloce_crafting_table": "axe",      # like the vanilla crafting table
    "veloce_pipe": "pickaxe",            # metal pipe
    "veloce_extractor": "pickaxe",       # machine
    "veloce_controller": "pickaxe",      # machine
    "velocity_furnace": "pickaxe",       # like the vanilla furnace
    "electric_furnace": "pickaxe",       # like the vanilla furnace
    "threshold_sensor": "pickaxe",       # like the vanilla observer
    "veloce_mekanism_crusher_module": "pickaxe",     # machines from the Mekanism module
    "veloce_mekanism_enrichment_module": "pickaxe",
    "veloce_mekanism_combiner_module": "pickaxe",
    "veloce_mekanism_sawmill_module": "pickaxe",
    "veloce_alchemistry_compactor_module": "pickaxe",  # machines from the Alchemistry module
    "veloce_alchemistry_combiner_module": "pickaxe",
    "veloce_alchemistry_fission_module": "pickaxe",
    "veloce_alchemistry_fusion_module": "pickaxe",
    "veloce_create_millstone_module": "pickaxe",       # machines from the Create module
    "veloce_create_saw_module": "pickaxe",
    "veloce_create_crushing_module": "pickaxe",
    "veloce_create_mechanical_crafter_module": "pickaxe",
    "veloce_integrale": "pickaxe",                     # decorative cage
}
DEFAULT_TOOL = "pickaxe"

# Blocks that must NOT drop (e.g. technical ones). Empty - all of our blocks
# are normal blocks to place and break.
NO_DROP = set()


def registered_block_ids():
    """Extracts block identifiers from the registries - the only sources of truth."""
    files = [REGISTRY] + sorted(glob.glob(os.path.join(ROOT, COMPAT_REGISTRY_GLOB)))
    ids = []
    for path in files:
        if not os.path.exists(path):
            continue
        ids += re.findall(r'\bBLOCKS\.register\(\s*"([a-z0-9_]+)"',
                          open(path, encoding="utf-8").read())
    # The MENU_TYPES registry uses the same pattern but a different
    # DeferredRegister, so the pattern above does not catch it. We guard against
    # it anyway, for the future.
    ids = [i for i in ids if not i.endswith("_menu")]
    # Registration order = order in the output files (stable diffs).
    seen, ordered = set(), []
    for i in ids:
        if i not in seen:
            seen.add(i)
            ordered.append(i)
    return ordered


def loot_table_for(block_id):
    """Standard drop: the block itself, preserved on explosion."""
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
    created, kept, skipped, repaired = [], [], [], []
    for b in block_ids:
        path = os.path.join(LOOT_DIR, f"{b}.json")
        # An existing file is NOT blindly left in place: we compare its content
        # with what the generator would produce today and repair any drift. Old
        # files used to be left untouched, and because of that 4 loot tables of
        # Mekanism modules pointed at a non-existent item ("Unknown registry
        # key ... veloce_crusher_module").
        if os.path.exists(path):
            try:
                current = json.load(open(path, encoding="utf-8"))
            except Exception:
                current = None
            expected = loot_table_for(b)
            if current != expected:
                write_json(path, expected)
                repaired.append(b)
                continue
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
    """Groups blocks by tool and rewrites the mineable tags."""
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

    # A tag for a tool that no longer has any block is deleted - otherwise a
    # spare file is left behind and confuses whoever reads the data.
    for name in os.listdir(TAG_DIR) if os.path.isdir(TAG_DIR) else []:
        if not name.endswith(".json"):
            continue
        tool = name[: -len(".json")]
        if tool not in written:
            os.remove(os.path.join(TAG_DIR, name))
    return written


def main():
    if not os.path.exists(REGISTRY):
        print(f"ERROR: cannot find the registry {REGISTRY}", file=sys.stderr)
        return 1

    blocks = registered_block_ids()
    if not blocks:
        print("ERROR: found no block in the registry", file=sys.stderr)
        return 1

    print(f"blocks in registry: {len(blocks)}")
    created, kept, skipped = generate_loot_tables(blocks)
    print(f"  loot tables added: {len(created)}" + (f" -> {created}" if created else ""))
    print(f"  existing loot tables (untouched): {len(kept)}")
    if skipped:
        print(f"  intentionally without a drop: {skipped}")

    tools = generate_mineable_tags(blocks)
    for tool, n in sorted(tools.items()):
        print(f"  mineable/{tool} tag: {n} block(s)")

    known = set(TOOL_BY_BLOCK) | NO_DROP
    unknown = [b for b in blocks if b not in known]
    if unknown:
        print(f"  note: new blocks with no tool assigned (they got '{DEFAULT_TOOL}'): {unknown}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
