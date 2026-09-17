#!/usr/bin/env python3
"""Verify a translation changed ONLY comments and string literals.

Strips comments and string literals from a file's current content and from its
git HEAD version, then compares the remaining code skeleton. If the skeletons
match, the edit was text-only and could not have altered logic.

Usage:
    python3 scripts/check_skeleton.py <path> [<path> ...]
    python3 scripts/check_skeleton.py --changed     # all modified src files
"""
import re
import subprocess
import sys


def strip_java(text):
    """Return the code skeleton: comments and string/char literals removed."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            out.append('""')
        elif c == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            out.append("''")
        else:
            out.append(c)
            i += 1
    # Collapse whitespace so reflowing a comment cannot cause a false positive.
    return re.sub(r"\s+", " ", "".join(out)).strip()


def head(path):
    r = subprocess.run(["git", "show", f"HEAD:{path}"],
                       capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args == ["--changed"]:
        r = subprocess.run(["git", "diff", "--name-only", "HEAD"],
                           capture_output=True, text=True)
        paths = [p for p in r.stdout.split("\n")
                 if p.startswith("src/") and p.endswith(".java")]
    else:
        paths = args

    bad = []
    for p in paths:
        old = head(p)
        if old is None:
            print(f"NEW      {p} (not in HEAD, skipped)")
            continue
        try:
            new = open(p, encoding="utf-8").read()
        except OSError as e:
            print(f"ERROR    {p}: {e}")
            bad.append(p)
            continue
        if strip_java(old) == strip_java(new):
            print(f"OK       {p}")
        else:
            print(f"CHANGED  {p}  <-- code skeleton differs!")
            bad.append(p)
    print(f"\n{len(paths) - len(bad)}/{len(paths)} code skeletons identical")
    if bad:
        print("CODE CHANGED IN:")
        for b in bad:
            print("  " + b)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
