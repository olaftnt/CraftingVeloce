"""
Builds the casing item icons from the pieces.

Run it after changing the plate, the scale or an icon:

    python3 scripts/gen_case_icons.py            # 90%, as chosen
    python3 scripts/gen_case_icons.py 0.80       # to try another size

Pieces, all committed on purpose so the icon can be rebuilt instead of
re-rendered:
  * assets/craftingveloce/case-source/FRONT.png and BACK.png - the casing split by
    hand in an image editor, so the machine sits between the two halves;
  * assets/craftingveloce/icon-dump/*.png   - the machine icons, rendered from the
    game by /cv icon all (see VeloceIconDump);
  * assets/craftingveloce/icon-dump/_map.txt - which machine goes in which casing.
    It comes from VeloceCaseContents, so it is not a second copy of the table.

Output goes to assets/craftingveloce/textures/item/case/<casing>.png and is what the
item models draw. Nothing here runs in the game.
"""
import pathlib
import sys
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "assets/craftingveloce/icon-dump"
PLATES = ROOT / "assets/craftingveloce/case-source"
OUT = ROOT / "assets/craftingveloce/textures/item/case"

# The window in the casing, measured from the empty-casing render rather than guessed:
# the bounding box of the glass. Every plate is this size, so the icon can be placed
# without any scaling of the plates themselves.
WINDOW = (84, 94, 593, 638)

# How much of the window the machine fills.
FIT = float(sys.argv[1]) if len(sys.argv) > 1 else 0.90


def main():
    back = Image.open(PLATES / "BACK.png").convert("RGBA")
    front = Image.open(PLATES / "FRONT.png").convert("RGBA")
    x0, y0, x1, y1 = WINDOW
    win_w, win_h = x1 - x0 + 1, y1 - y0 + 1
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

    pairs = [line.strip().split("=") for line in
             (SRC / "_map.txt").read_text(encoding="utf-8").splitlines() if "=" in line]
    OUT.mkdir(parents=True, exist_ok=True)

    made, skipped = 0, []
    for casing, content in pairs:
        icon_path = SRC / f"{content}.png"
        if not icon_path.exists():
            skipped.append(content)
            continue
        icon = Image.open(icon_path).convert("RGBA")
        scale = min(win_w * FIT / icon.width, win_h * FIT / icon.height)
        size = (max(1, round(icon.width * scale)), max(1, round(icon.height * scale)))
        icon = icon.resize(size, Image.LANCZOS)
        out = Image.new("RGBA", back.size, (0, 0, 0, 0))
        out.alpha_composite(back)
        out.alpha_composite(icon, (cx - size[0] // 2, cy - size[1] // 2))
        out.alpha_composite(front)
        out.save(OUT / f"{casing.replace('veloce_', '')}.png")
        made += 1

    print(f"scale {FIT:.0%}: {made} icon(s) -> {OUT.relative_to(ROOT)}")
    if skipped:
        print(f"  no render for: {', '.join(sorted(skipped))}")


if __name__ == "__main__":
    main()
