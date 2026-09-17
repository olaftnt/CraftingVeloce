#!/usr/bin/env python3
"""Run the scripts/build.py guards against an already-built jar.

`build.py` is still the home of the ~37 `validate_*` guards - they are the real
test suite of this project, and Gradle cannot replace them. This runner lets
Gradle drive them: it builds the jar, then calls every guard and reports which
ones pass, instead of letting the first failure abort the whole run.

    ./gradlew verifyGuards

The guards were written against the flat `src/` tree; on this branch the sources
live in `neoforge/src/main/java`, and build.py exposes that as `SRC_ROOT`. The
game assets (`assets/`, `data/`) and `src_meta/` deliberately stayed at the
repository root, so those guards need no change.

This is a bridge, not the destination. The plan is to rewrite the guards as
Gradle/JUnit tests; until then this keeps them runnable and wired into the build.
"""
import contextlib
import io
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import build  # noqa: E402  (path set up above)

DEFAULT_JAR = "neoforge/build/libs/craftingveloce-1.0.0.jar"

# build.py compiles into its own hardcoded /tmp directory. Under Gradle the
# classes land in the module's build dir instead, and validate_compat_gates runs
# `javap` against build.BUILD_OUT. Point it at the real output, otherwise it
# silently inspects stale classes left over from an old build.py run - which is
# exactly how a missing class can look like a pass.
GRADLE_CLASSES = "neoforge/build/classes/java/main"

# Guards that only read the tree / the jar contents.
NO_ARG_GUARDS = [
    "validate_packet_docs",
    "validate_lang_keys",
    "validate_filter_labels",
    "validate_gui_layout",
    "validate_helper_docs",
    "validate_sensor_row",
    "validate_node_blocks",
    "validate_recipe_model",
    "validate_module_block_ids",
    "validate_core_isolation",
    "validate_create_kinetics",
    "validate_number_format",
    "validate_module_recipe_access",
    "validate_block_models",
    "validate_integrale_model",
    "validate_integrale_display",
    "validate_mods_toml",
    "validate_create_mechanics",
    "validate_case_disassembly",
    "validate_case_occlusion",
    "validate_loot_item_ids",
    "validate_showcase_command",
    "validate_block_probe",
    "validate_craftable_cache",
    "validate_energy_pull",
    "validate_brewing_stand",
    "validate_brewing_proxy",
    "validate_module_content_textures",
    "validate_potion_proxy_assets",
    "validate_pipe_energy",
    "validate_module_info_gui",
    "validate_jade_info",
    "validate_terminal_craft_error",
    "validate_jei_catalysts",
    "validate_auto_crafter_ingredient_rule",
]


def run_one(name, fn, arg=None):
    """Call a guard, capturing its output. Returns (ok, detail)."""
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            fn() if arg is None else fn(arg)
        return True, ""
    except SystemExit as e:
        if e.code in (0, None):
            return True, ""
        return False, buf.getvalue().strip().split("\n")[-1][:200]
    except Exception as e:  # noqa: BLE001 - a crashing guard is a failing guard
        return False, f"{type(e).__name__}: {e}"[:200]


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_JAR
    if not os.path.exists(jar):
        print(f"no jar: {jar}\nRun ./gradlew :neoforge:build first.")
        return 2

    build.BUILD_OUT = GRADLE_CLASSES
    if not os.path.isdir(GRADLE_CLASSES):
        print(f"no compiled classes at {GRADLE_CLASSES}\nRun ./gradlew :neoforge:build first.")
        return 2
    print(f"guards against {jar}")
    print(f"classes       {GRADLE_CLASSES}\n")

    results = []

    with zipfile.ZipFile(jar) as z:
        names = z.namelist()

        # Guard that inspects the jar's entry list.
        results.append(("validate_block_data", *run_one(
            "validate_block_data", build.validate_block_data, names)))
        # Guards that need the open archive.
        for gname in ("validate_compat_gates", "validate_jar_isolation"):
            fn = getattr(build, gname, None)
            if fn is None:
                continue
            results.append((gname, *run_one(gname, fn, z)))

        # Guards that only read the sources / assets.
        for gname in NO_ARG_GUARDS:
            fn = getattr(build, gname, None)
            if fn is None:
                results.append((gname, None, "not present in build.py"))
                continue
            results.append((gname, *run_one(gname, fn)))

    passed = [r for r in results if r[1] is True]
    failed = [r for r in results if r[1] is False]
    skipped = [r for r in results if r[1] is None]

    for name, ok, detail in results:
        if ok is True:
            print(f"  PASS  {name}")
        elif ok is None:
            print(f"  SKIP  {name}  ({detail})")
        else:
            print(f"  FAIL  {name}")
            if detail:
                print(f"        {detail}")

    print(f"\n{len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
