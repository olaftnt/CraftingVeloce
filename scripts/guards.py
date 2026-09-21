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


# DISCOVERED, not hardcoded.
#
# This was the literal "neoforge/build/libs/craftingveloce-1.0.0.jar", and it broke the
# moment the release file name gained the loader and the game version
# (craftingveloce-1.0.0-NeoForge-1.21.1.jar): every jar guard would have run against a path
# that no longer exists, and a guard that cannot open its jar is a guard that cannot fail
# loudly. A hardcoded artifact name is a second register of something Gradle already owns -
# the same class of drift this file's own docstring describes for the guard list.
#
# The directory normally holds exactly one jar. If it holds more (a stale build, a
# classifier), the newest wins, which is what a human would pick.
def _default_jar():
    fallback = os.path.join(ROOT, "neoforge", "build", "libs",
                            "craftingveloce-1.1.0-NeoForge-1.21.1.jar")
    libs = os.path.join(ROOT, "neoforge", "build", "libs")
    try:
        jars = [os.path.join(libs, f) for f in os.listdir(libs) if f.endswith(".jar")]
    except OSError:
        return fallback
    return max(jars, key=os.path.getmtime) if jars else fallback


DEFAULT_JAR = _default_jar()

# build.py compiles into its own hardcoded /tmp directory. Under Gradle the
# classes land in the module's build dir instead, and validate_compat_gates runs
# `javap` against build.BUILD_OUT. Point it at the real output, otherwise it
# silently inspects stale classes left over from an old build.py run - which is
# exactly how a missing class can look like a pass.
GRADLE_CLASSES = "neoforge/build/classes/java/main"

# Guards that need arguments (`validate_block_data(names)`, the two jar ones and
# validate_isolation_runtime) are driven explicitly in main(). Everything else is
# DISCOVERED from build.py rather than listed here.
#
# Why discovery: this list used to be written out by hand, and it silently fell
# behind - two guards added on main (validate_heat_accounting,
# validate_partial_delivery) simply never ran, while the run still reported a
# clean pass. A hand-maintained list of checks is the exact failure mode the
# project's own guards exist to prevent, so it is not repeated here.
SKIP = {
    # needs (cp, toms, rs): the resolved dependency classpath, which Gradle owns
    "validate_isolation_runtime",
    # arguments are supplied by the caller in main()
    "validate_block_data",
    "validate_compat_gates",
    "validate_jar_isolation",
}


def discover_guards():
    """Every validate_* in build.py that takes no required argument."""
    import inspect

    found = []
    for name in sorted(dir(build)):
        if not name.startswith("validate_"):
            continue
        if name in SKIP:
            continue
        fn = getattr(build, name)
        if not callable(fn):
            continue
        params = [
            p for p in inspect.signature(fn).parameters.values()
            if p.default is inspect.Parameter.empty
            and p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD, p.KEYWORD_ONLY)
        ]
        if params:
            continue
        found.append(name)
    return found


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

        # Guards that only read the sources / assets - discovered, not listed.
        for gname in discover_guards():
            results.append((gname, *run_one(gname, getattr(build, gname))))

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
