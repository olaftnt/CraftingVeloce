# Veloce texture packs — proposals to choose from

Four variants of **the same** texture set, each in a different material.
They are not deployed to the mod — the mod still uses its current textures.
They exist to be looked at and chosen from.

## Previews

- **`PREVIEW-ALL.png`** — all 4 variants in a single sheet
  (rows: netherite / iron / obsidian / copper, columns: crafter, extractor,
  pipe, controller, furnace, electric furnace, wrench).
- `PREVIEW.png` in each folder — the 11 textures of that one variant.

## How it is made

**The base is real vanilla blocks**, repainted in the material. That way every
block still looks like what it is — just made of something else:

| our block | vanilla base |
|---|---|
| crafter (auto-crafter) | `crafting_table_top` |
| extractor | `dropper_front` |
| Velocity Furnace | `furnace_front` / `_side` / `_top` |
| Velocity Electric Furnace | `blast_furnace_front` / `_side` / `_top` |

The repaint works on **brightness**: we compute the brightness of every pixel of
the vanilla texture, stretch it to the full range and substitute the colour from
the material palette. The shape, shading, rivets and details stay.

**The pipe, controller and wrench are a different story** — as agreed they keep
the **mod's original scheme**, and only the **accent colour** changes: we take
the original hue (purple ~290 degrees) and swap just the hue, preserving
saturation and brightness. The dark body stays exactly as it was.

**The terminal is deliberately in no pack** — it stays unchanged.

## The four proposals

| variant | material | accent |
|---|---|---|
| `netherite` | dark, warm graphite (like a netherite block) | glowing orange |
| `iron` | bright, cool steel | steel blue |
| `obsidian` | almost black with a purple tint | obsidian purple |
| `copper` | warm copper brown | green patina |

## How to view it in game

The packs are already copied into the profile:

    ~/Library/Application Support/ModrinthApp/profiles/testing/resourcepacks/

**Options -> Resource Packs** and enable **one** of them: `veloce_netherite`,
`veloce_iron`, `veloce_obsidian`, `veloce_copper`. Enable them one at a time —
they overwrite the same files. Disabling a pack or deleting the folder returns
you to the mod's current textures; the mod itself is not changed in any way by
this.

## Regeneration / changes

    python3 scripts/gen_material_textures.py

The script is deterministic. The material palettes and accents live in a single
`MATERIALS` dictionary at the top of the file — changing a colour is changing one
line. Adding another material is adding one entry to that map.

## Once you choose

Tell me which variant (or which elements from which), and I will deploy it as the
mod's textures — then they will end up in `assets/craftingveloce/textures/` and
in the JAR.
