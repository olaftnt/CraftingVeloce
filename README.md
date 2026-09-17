# CraftingVeloce — NeoForge 1.21.1 Minecraft Mod

A mod that adds an intelligent logistics network to Minecraft, built on top of the Tom's Simple Storage system. It lets you manage your storage, automatically pull items out of the network, and auto-craft.

---

## 🟢 Project status (2026-09-15)

**Stable / under active development.** The mod runs in game. The last session ended with shipping the Veloce Crafting Table and fixing a critical bug in the Java module.

---

## 📦 Blocks and items

### 1. Veloce Storage Terminal (`veloce_tom_terminal`)
- Extends `AbstractStorageTerminalBlock` from Tom's Storage
- Opens a Creative Inventory style screen (no hotbar) listing every item in the network
- Clicking an item pulls it out of the network (1 item with LMB, up to a full stack with SHIFT+LMB)
- Draws counts next to items (green digits) showing how many are in the network
- Tooltip: the default MC tooltip + a **red reason for a failed craft** on any item that cannot be crafted
- **Why it failed** is shown in the tooltip of the clicked item ("Cannot craft: no furnace in the network", "Cannot craft: missing iron ingot", "recipe tree too complex"...). The server sends the reason in a `TerminalCraftErrorPKT` packet rather than via `displayClientMessage` — the action bar is **invisible** in the terminal GUI, so the player never saw any response. The reason sticks for 30 s (`VeloceCraftErrorHints`) and is tracked per item, just like the network counters
- Connects to the network through `IInventoryCable` (Tom's Storage)

### 2. Veloce Extractor (`veloce_extractor`)
- Square network node block
- **Right click** → opens an interface with 9 filter slots (left side) + 9 output slots (right side)
- **Filter (left side):** clicking any filter slot opens a creative picker (no hotbar) for choosing a ghost item as the filter. It shows the counts of the matching items available in the network
- **Output (right side):** real slots holding the items pulled out of the network according to the filter
- Every tick the extractor itself pulls matching items from the network into the output inventory
- **Compatibility:** implements `WorldlyContainer` → it can be sucked by a hopper; implements `Capabilities.ItemHandler.BLOCK` → it can be sucked by NeoForge pipes (Pipez etc.)
- A Veloce pipe connected to the extractor supports only **Connected/Disconnected** (no push/pull nozzle)
- Connects to the network through `IInventoryCable`

### 3. Veloce Crafting Table (`veloce_crafting_table`)
- Square network node block
- **Right click** → opens a creative inventory (no hotbar) with coloured overlays on the items:
  - 🟥 **Red** = it has a crafting recipe and is **OFF** (the default)
  - 🟩 **Green** = it is **ON** (auto-crafted)
  - 🟪 **Purple** = there is **no recipe** in a regular crafting table (cannot be enabled)
- Clicking toggles red ↔ green; purple items are inactive
- The state is saved to NBT per block (persistence across a server restart)
- Veloce pipe: only **Connected/Disconnected** (no push/pull)
- Connects to the network through `IInventoryCable`

### 4. Veloce Pipe (`veloce_pipe`)
- The pipe connecting all network blocks
- Renders as a thin conduit with a nozzle (the extracting head) when it is in Pull mode
- **Wrench:** right-clicking a connection toggles the mode:
  - On a regular inventory: `Push/Pull → Pull → Disconnected → Push/Pull`
  - On the extractor/crafting table: `Connected → Disconnected → Connected`
- Waterloggable
- Connects to Tom's Storage, Refined Storage, regular inventories, and the NeoForge ItemHandler
### 5. Veloce Wrench (`wrench`)
- A tool for configuring pipe connections
- Right-clicking a `VelocePipeBlock` calls `onWrenchClicked`
- Cycles the pipe side mode: **Push/Pull → Pull → Disconnected**
  - `Pull` = this storage may only be extracted from (insertion blocked)
  - `Disconnected` = no connection (the storage drops out of the network)
  - the pipe↔pipe link flag has a **single owner** (the lower position), so
    toggling works from either side; details in the `togglePipeLink` comment

### 6. Velocity Furnace (`velocity_furnace`)
- The heat source for the auto-crafter; it burns **non-stop**, including while idle
- 6 fuel filter slots (chosen the same way as in the extractor) + a fuel slot; with empty
  filters it accepts **any** fuel
- Output: one OPERATION = one instant smelt
  (`burnTicksRemaining / SMELT_HEAT_COST`)
- Once the heat is used up, the furnace **immediately** pulls fuel from the network
  (`wantImmediatePull`), instead of waiting for the end of `PULL_INTERVAL_TICKS`

### 7. Velocity Electric Furnace (`electric_furnace`)
- An internal FE accumulator (25 000 000 FE = 125 smelts, 200 000 FE per smelt). It is charged
  **only** by the energy item in its own battery slot, or by a cable from another mod
  attached **directly** to the furnace
- **Zero FE travels on Veloce cables.** A Veloce cable is a carrier for the Veloce
  network (item logistics) and is never an energy conduit — not for our machines and
  not for anyone else's. Nothing draws power through the network
- **Priority 0** — the crafter draws from it first, and the fuel furnace (priority 1)
  is the fallback, including mid-run: a shortage of power for 5 smelts means 3 from
  power and 2 from fuel

### 8. Veloce Threshold Sensor (`threshold_sensor`)
- **One centred row:** an item slot, a number field (38 px), `+`, `-`
  and a mode button — with no text at all in the GUI (only the block title)
- The mode button is a **redstone torch**: lit = `When at least`,
  unlit = `When below`; clicking inverts the signal
- It watches how many of the filtered item there **physically** are in the network and emits redstone
- The default threshold is **64**, the lower bound is **1** (a threshold of 0 means nothing)
- The result is kept in the **block state** (`POWERED`), because it is a state change broadcast
  to neighbours that starts machines; the power is **strong** (`getSignal` + `getDirectSignal`)
- It polls the network once per second (`CHECK_INTERVAL_TICKS = 20`), because scanning the storage
  is expensive and the sensor is meant to start a factory, not to clock one

### 9. Veloce Integrale (`veloce_integrale`)
- **A cage of purple glass**: the frame is 12 thin rods (2×2 px) along
  the edges of the cube, and the windows are filled with **purple glass** — taken directly
  from vanilla (`minecraft:block/purple_stained_glass`), so it looks exactly
  like `purple stained glass`
- The glass is **inset by 2 px** (exactly within the frame opening): it does not touch
  the block planes, so there is no z-fighting with the rods
- The model has `render_type: minecraft:translucent` — without it the purple would be
  opaque (the world is visible through the glass)
- **Collision is a plain full block** — the cage looks like a skeleton but behaves
  like a normal block (you can walk on it, you cannot walk through it);
  `noOcclusion` + light transmission, so that a neighbour does not render
  "into a hole"
- **Closing the sides that carry cables**: when a Veloce pipe arrives from a given side, the window
  on that side **closes with a metal panel** — you can see at a glance which side
  the cage is connected on. The state is kept in the **block state** (six
  booleans of the vanilla `PipeBlock`, one per side), and `updateShape`
  recomputes only that one side; there is no block entity and no ticker, so it also works
  on the client without packets
- **A Veloce network node**: pipes connect on every side, but the cage does **not
  keep its chunk loaded** (`VeloceNetworkNode.keepChunkLoaded()` = `false`) — it is
  decoration, and force-loads are meant to hold what actually works
- The blockstate is **multipart**: a frame model + one
  panel per side (`veloce_integrale_panel_<side>`), rather than 64 variants
- **Casing: the block turns into our machine (block replacement)**: right-clicking
  with the right vanilla block **replaces the whole cage** with a real Veloce
  block according to the `VeloceIntegraleConversions` table:

  | You insert | You get |
  |----------|----------|
  | `crafting_table` | `veloce_crafting_table` |
  | `lectern` | `veloce_controller` |
  | `dispenser` | `veloce_extractor` |
  | `observer` | `threshold_sensor` |
  | `furnace` | `velocity_furnace` |
  | `create:crushing_wheel` | `veloce_create_crushing_module` (1 click = 1 wheel) |
  | `create:mechanical_crafter` | `veloce_create_mechanical_crafter_module` (1 click = 1 eye) |
  | `create:millstone` / `mechanical_saw` | `veloce_create_millstone_module` / `..._saw_module` |
  | `create:mechanical_press` / `mechanical_mixer` | `veloce_create_press_module` / `..._mixer_module` |
  | `create:deployer` | `veloce_create_deployer_module` |

  A block outside the table **does nothing** (no "putting it on display inside")
- **Every Veloce block looks like a casing with something inside**: the block has a
  frame model (`veloce_integrale_frame`), and the client renders the model of the
  base block inside it — a lectern in the controller, a dispenser in the extractor, an observer
  in the sensor, a crafting table in the table, a furnace in the furnace. One table
  (`VeloceCaseContents`) + **one** generic renderer (`VeloceCaseRenderer`,
  the contents follow from the block TYPE, so the server synchronizes nothing).
  The old models of these blocks were **deleted**, and the item icons are generated
  (the casing frame + the base block cube inside)
- **Names without duplicates**: "Veloce Integrale" is exclusively an **empty casing**
  for other blocks; the machines are called Veloce Crafting Table / Controller /
  Extractor / Threshold Sensor / Furnace / Electric Furnace. There is no longer a `facade`
  state or a separate "frame + table" item — after the appearance was unified it
  was a **duplicate** of the plain crafting table
- **Why block replacement rather than a dedicated block entity**: the first version
  kept a table BE inside the cage and **pretended** to be a crafter, the second "put a
  display piece out". Both meant that the cage was not a machine: the recipe mods
  (JEI/EMI) had nothing to recognise, and the network had to know the exceptions. Now
  our block simply stands in the world, and it is a crafter **by block type**
  (`isActiveCrafter`)
- **Content animation**: the renderer gently rotates the display piece and sways it
  (left-right, up-down). Machines that have their own **moving parts**
  (the Create crusher: mill wheels) draw them **side by side**, and the rotation
  speed comes from the machine (`VeloceCaseSpin`) — the core does not know the Create types
- The cage is **not** a crafter (the block type decides), it does not expose a network buffer
  and does not keep its chunk loaded; the machine made from it does all three
- The model and the icons are guarded by `validate_integrale_model` (12 rods, inset
  glass made of stained glass, `render_type: translucent`, `ambientocclusion: false`, 6 panels
  exactly within the window opening, the casing in every machine's blockstate, the icon
  carrying the `#content` content), and the cage mechanics by `validate_integrale_display`
  (the conversion table, block replacement, node reporting to the network, and the **absence**
  of leftovers from display pieces and from the duplicate station)



---

## 🏗️ Technical architecture

### Project location
```
/Users/olafalencynowicz/Desktop/CraftingVeloce/
├── src/                          # Flat source root (NOT main/java)
│   ├── com/craftingveloce/
│   │   ├── CraftingVeloceMod.java          # Main mod class
│   │   ├── block/                          # Blocks
│   │   │   ├── VelocePipeBlock.java
│   │   │   ├── VeloceExtractorBlock.java
│   │   │   ├── VeloceCraftingTableBlock.java
│   │   │   ├── VeloceTomTerminalBlock.java
│   │   │   └── entity/                     # Block Entities
│   │   │       ├── VelocePipeBlockEntity.java
│   │   │       ├── VeloceExtractorBlockEntity.java
│   │   │       ├── VeloceCraftingTableBlockEntity.java
│   │   │       └── VeloceTomTerminalBlockEntity.java
│   │   ├── client/
│   │   │   ├── ClientTerminalHelper.java   # Dispatcher for packet handlers (client-side)
│   │   │   └── gui/
│   │   │       ├── VeloceTerminalScreen.java       # Terminal GUI
│   │   │       ├── VeloceExtractorScreen.java      # Extractor GUI (menu-based)
│   │   │       ├── VeloceFilterPickerScreen.java   # Creative picker for filters
│   │   │       └── VeloceCraftingTableScreen.java  # Crafting table GUI
│   │   ├── network/
│   │   │   ├── VelocePacketHandler.java            # Registration of all packets
│   │   │   ├── OpenTerminalScreenPKT.java          # S→C: open the terminal
│   │   │   ├── SyncTerminalCountsPKT.java          # S→C: send Map<Item,Long> with the counts
│   │   │   ├── OpenFilterPickerPKT.java            # S→C: open the filter picker
│   │   │   ├── SyncExtractorFiltersPKT.java        # S→C: send the filters to the extractor
│   │   │   ├── OpenCraftingTableScreenPKT.java     # S→C: open the crafting table GUI
│   │   │   ├── SyncCraftingTableStatePKT.java      # S→C: sync the crafting table state
│   │   │   ├── TerminalPullItemPKT.java            # C→S: pull an item from the network
│   │   │   ├── ExtractorSetFilterPKT.java          # C→S: set a filter in the extractor
│   │   │   ├── ExtractorOpenFilterPKT.java         # C→S: open the filter picker (triggers a counts sync)
│   │   │   └── CraftingTableToggleItemPKT.java     # C→S: toggle an item in the crafting table
│   │   │   └── pipe/
│   │   │       ├── VelocePipeNetworkManager.java   # SavedData: manages the pipe networks
│   │   │       ├── VelocePipeNetwork.java          # A single network (set of pipes + terminals)
│   │   │       └── ConnectedEndpointInfo.java      # Info about a connected inventory
│   │   ├── inventory/
│   │   │   └── VeloceExtractorMenu.java            # AbstractContainerMenu for the extractor
│   │   ├── init/
│   │   │   └── VeloceRegistry.java                 # DeferredRegister for blocks/items/BE/menu
│   │   ├── item/
│   │   │   └── VeloceWrenchItem.java
│   │   ├── rs/
│   │   │   └── RefinedStorageHelper.java           # Refined Storage integration
│   │   └── commands/
│   │       └── CVDebugCommand.java
│   └── moze_intel/projecte/...                     # CAUTION: these are patches to the inventoryexchange mod
│                                                   # do NOT compile them into the craftingveloce JAR!
├── assets/craftingveloce/
│   ├── blockstates/             # JSON blockstate for every block
│   ├── models/block/            # JSON block models
│   ├── models/item/             # JSON item models
│   ├── textures/block/          # PNG textures
│   └── lang/en_us.json         # Translations
├── data/                        # Data pack (Tom's Storage tags, loot tables)
├── scripts/
│   ├── cp.txt                   # Classpath for compilation (generated, huge)
│   ├── build.py                 # Compilation + packaging + quality checks (step 4)
│   ├── gen_loot_tables.py       # Loot tables and "mineable" tags from the block registry
│   └── gen_integrale_crafting_item.py  # Icon of the "table in a cage" item from the frame model
├── mod_jar/                     # External JARs for compilation (inventoryexchange)
├── craftingveloce_classes/      # Compiled classes (javac output)
├── craftingveloce_jar_root/     # Staging dir for the JAR (classes + assets + META-INF)
└── craftingveloce-1.0.0.jar    # The finished mod JAR
```

### Build Process
```bash
# 1. Compile (EXCLUDE src/moze_intel and src/eatawesome!)
python3 -c "
import subprocess, glob
with open('scripts/cp.txt') as f:
    cp = f.read().strip()
tom_jar = '/Users/olafalencynowicz/Library/Application Support/ModrinthApp/profiles/testing/mods/toms_storage-1.21-2.4.2.jar'
rs_jar = '/Users/olafalencynowicz/Library/Application Support/ModrinthApp/profiles/testing/mods/refinedstorage-neoforge-2.0.9.jar'
src_files = [f for f in glob.glob('src/**/*.java', recursive=True)
             if 'eatawesome' not in f and 'moze_intel' not in f]
res = subprocess.run(['javac', '--release', '21', '-cp', cp+':'+tom_jar+':'+rs_jar,
                      '-d', 'craftingveloce_classes'] + src_files,
                     capture_output=True, text=True)
print(res.returncode, res.stderr[:500])
"

# 2. Build the JAR
import shutil, subprocess
shutil.copytree('craftingveloce_classes', 'craftingveloce_jar_root', dirs_exist_ok=True)
shutil.copytree('assets', 'craftingveloce_jar_root/assets', dirs_exist_ok=True)
shutil.copytree('data', 'craftingveloce_jar_root/data', dirs_exist_ok=True)
subprocess.run(['jar', 'cf', 'craftingveloce-1.0.0.jar', '-C', 'craftingveloce_jar_root', '.'])

# 3. Deploy
shutil.copyfile('craftingveloce-1.0.0.jar',
    '/Users/olafalencynowicz/Library/Application Support/ModrinthApp/profiles/testing/mods/craftingveloce-1.0.0.jar')

# 4. Commit & push
git add -A && git commit -m "..." && git push origin main
```

### Key compilation dependencies
- `scripts/cp.txt` — the full classpath (MC + NeoForge + every mod)
- `toms_storage-1.21-2.4.2.jar` — from the mods folder (the testing profile)
- `refinedstorage-neoforge-2.0.9.jar` — from the mods folder (the testing profile)
- **compileOnly (optional integrations)** — `scripts/build.py` finds these JARs itself
  in `libs/`, in the mods folder of the `testing` profile, or in `~/Downloads`:
  `create-1.21.1-6.0.10.jar`, `ponder-neoforge-1.0.82+mc1.21.1.jar` (extracted
  automatically from Create's `META-INF/jarjar/`), `alchemistry-1.21.1-2.4.5.jar`,
  `alchemylib-1.21.1-1.1.6.jar`, `chemlib-1.21.1-2.1.5.jar`,
  `Mekanism-...-api.jar` (or the full `Mekanism-...jar`).
  The JARs are required **only when** some source actually imports a given
  foreign package — the "gates only, zero blocks" stage compiles without them.
  `*.jar` is in `.gitignore`, so none of this ends up in the repository.


### ⚠️ Important pitfalls

1. **`src/moze_intel/`** — this is NOT part of our mod! These are patched classes from `inventoryexchange-1.0.0.jar`. If they end up in our JAR → `ResolutionException` at MC startup (duplicate package export). Always exclude them from the compilation into craftingveloce.

2. **`src/eatawesome/`** — also not ours, it is the old InventoryExchange mod. Do not compile.

3. **`src_meta/META-INF/`** — the ONLY source of `neoforge.mods.toml`. The build cleans the
   staging dir only of `com/`, `assets/`, `data/` and copies `META-INF` from `src_meta/`,
   so the file is versioned and does not disappear on a clean rebuild. Do not keep a
   copy of `mods.toml` in `craftingveloce_jar_root/` — that was the cause of a JAR without
   metadata (the loader does not see such a mod).

4. **VeloceRegistry.createBEType()** — it uses reflection because NeoForge 21.1.x has no public 3-arg `BlockEntityType` constructor. Do not change it without need.

5. **Creative Screen without a hotbar** — all 3 GUIs (terminal, filter picker, crafting table) must: replace the player inventory slots with dummy slots, fill the hotbar area with grey `0xFFC6C6C6`, and restore the `GameType` on close.

6. **Package layout in the JAR** — the output of `javac -d <dir>` creates `<dir>/com/craftingveloce/...`.
   When copying that into the staging dir, you must copy the **contents** of `<dir>/com` into `<root>/com`, not
   the whole `com` folder — otherwise the JAR ends up with `com/com/craftingveloce/...` and the mod **does not
   load at all**. Always verify: `unzip -l craftingveloce-1.0.0.jar | grep CraftingVeloceMod.class`
   must print `com/craftingveloce/CraftingVeloceMod.class`.

7. **Do not delete `META-INF/` on rebuild** — when cleaning the staging dir, remove only
   `com/`, `assets/`, `data/`. `META-INF/neoforge.mods.toml` must stay.

8. **Files that exist only in the staging dir** — `data/minecraft/tags/block/mineable/axe.json` once existed
   solely in `craftingveloce_jar_root/` and was deleted by a clean rebuild. Keep
   all resources in the source `data/` so that the build is reproducible.

---

## 🔌 Integrations

| System | How |
|--------|-----|
| Tom's Simple Storage | `VeloceTomTerminalBlock extends AbstractStorageTerminalBlock`; all blocks implement `IInventoryCable` |
| Refined Storage | `RefinedStorageHelper.hasRSNetwork()` — checks for, and connects to, an RS network |
| NeoForge ItemHandler | `Capabilities.ItemHandler.BLOCK` registered for the extractor (lets a Pipez pipe suck from it) |
| Vanilla Hopper | `VeloceExtractorBlockEntity implements WorldlyContainer` |
| Create / Alchemistry / Mekanism | optional (`compat/*`) — see below |
| JEI | optional — our blocks as catalysts for recipe categories (`compat/jei/`) |

### Optional integrations (`com/craftingveloce/compat/`)

The overriding rule: **the core knows no foreign mod**. An integration module lives in
`compat/<mod>/`, has foreign types only in method bodies, and is called only after
the presence of the mod is checked in a gate (`XCompat.isPresent()` → `XCompat.register()`
from `CraftingVeloceMod`). Every mod is listed in `neoforge.mods.toml` as
`type="optional"` + `ordering="AFTER"`.

A `try/catch` around integration code **does not work** — `NoClassDefFoundError` is thrown
while the class is being loaded and linked, before the `try` can kick in. That is why a foreign type
must not appear in a field or in the signature of an unconditionally loaded class.

Four checks in `scripts/build.py` (step 4) guard this:

| Check | What it guards |
|----------|----------------|
| `validate_core_isolation` | no file outside `compat/` imports a foreign package; `compat/<mod>` does not import another foreign mod |
| `validate_compat_gates` | classes loaded always (core + `compat/` + gates) have no foreign type in their signature — at the bytecode level (`javap`) |
| `validate_jar_isolation` | no core class in the JAR references a foreign package (it also catches use without an `import`) |
| `validate_isolation_runtime` | **L1**: runs `scripts/isolation/L1Test.java` without the foreign mods — the gates must load and return `false` |
| `validate_jei_catalysts` | the JEI plugin knows **only** JEI (no Create/Mekanism/Alchemistry), the *category UID → our block* pairs agree pair by pair, and the core does not touch the plugin |

The scope of the recipe families is registered by the modules (`XRecipeFamily`) in
`VeloceRecipeFamilies.registerModFamily` — inside `FMLCommonSetupEvent`, because
a foreign mod's DeferredHolders are bound only after registration.

### JEI - our blocks as recipe machines

For every recipe, JEI shows the icons of the machines that handle it on the left-hand side.
Our plugin (`compat/jei/VeloceJeiPlugin`, annotation `@JeiPlugin`) adds our blocks to those lists:

| JEI category | Our block |
|---------------|-------------|
| `minecraft:crafting` | `veloce_crafting_table` |
| `create:milling` / `create:sawing` / `create:crushing` | `veloce_create_millstone_module` / `..._saw_module` / `..._crushing_module` |
| `create:mechanical_crafting` / `create:pressing` / `create:mixing` / `create:deploying` | `veloce_create_mechanical_crafter_module` / `..._press_module` / `..._mixer_module` / `..._deployer_module` |
| `mekanism:crushing` / `mekanism:enriching` / `mekanism:combining` / `mekanism:sawing` | `veloce_mekanism_crusher_module` / `..._enrichment_module` / `..._combiner_module` / `..._sawmill_module` |
| `alchemistry:compactor` / `...:combiner` / `...:fission` / `...:fusion` | `veloce_alchemistry_compactor_module` / `..._combiner_module` / `..._fission_module` / `..._fusion_module` |

Three things that are easy to break here, and are therefore guarded by
`validate_jei_catalysts`:

1. **A category UID is not the recipe type name.** The Create saw has the JEI category
   `create:sawing`, even though its recipe type is `create:cutting` — that is why the UIDs
   were read from the mods' bytecode (`Create.asResource(...)`,
   `RecipeTypeRegistryObject.getId()`, `RecipeType.create("alchemistry", ...)`),
   rather than guessed from names. Swapping two UIDs is a bug too: the mill
   would show up under the crusher.
2. **The plugin knows no Create/Mekanism/Alchemistry.** It is loaded by JEI itself (the
   `@JeiPlugin` scan), so it also exists for a player without those mods — importing their classes
   would end in `NoClassDefFoundError`. It knows only the category UIDs and looks up the type
   through `IJeiHelpers.getRecipeType(UID)`. The second reason is technical:
   `RecipeType.equals` compares the *class* of the recipe, so a hand-assembled type
   would never match a category registered by that mod.
3. **Registration phase.** We add catalysts in `registerRecipeCatalysts`, that is,
   after the `registerCategories` phase of all plugins — only then does
   `getRecipeType(UID)` have something to return. Categories that are not found go to the log:
   `[Veloce][JEI] katalizatory: N dodanych, M bez kategorii [...]`, because that is the only
   signal that some UID stopped matching after an update of that mod.

The category tables are filled in by the integration modules (`CreateJeiCatalysts`,
`MekanismJeiCatalysts`, `AlchemistryJeiCatalysts`) into a shared registry
`compat/VeloceJeiCatalysts` — **without touching JEI and without foreign mod types**
(just UIDs and our blocks). The `minecraft:crafting` category is filled in by the
core (`VeloceJeiCatalysts.registerDefaults()`), because the Veloce table works without
any of those mods.

### Jade — machine description under the crosshair

You look at one of our machines and see in the Jade tooltip the same thing as in the window after a
right click: current / required / maximum speed, SU draw, the number of clicked-in elements,
accumulator state (for FE machines), pipe network state
and the **status — including "not enough power"**.

This replaced the custom "not enough rotation speed" text that we used to draw
in the middle of the screen (`VeloceModuleOverlay` — **removed**). The reason was
twofold: the player asked for it directly ("get rid of that thing, do the Jade integration"),
and the HUD text was visible from under the GUI anyway as a blurred smudge.

How it works (`compat/jade/`):

| Element | Role |
|---------|------|
| `VeloceJadePlugin` (`@WailaPlugin`) | registers server data for **all** block entities and the tooltip component for **all** blocks |
| `VeloceModuleDataProvider` | `shouldRequestData` asks only for BEs implementing `VeloceModuleInfoSource`; `appendServerData` inserts a ready `moduleInfo(ServerLevel)` |
| `VeloceModuleComponentProvider` | adds **one** line: the work status (working / not enough power / not enough energy) |

Three things that are easy to break here, and are therefore guarded by `validate_jade_info`:

1. **The plugin knows no Create/Mekanism/Alchemistry.** It is loaded by Jade itself
   (the `@WailaPlugin` scan), so it also exists for a player without those mods.
   The filter goes by the core interface `VeloceModuleInfoSource`, and not by
   the block entity classes of the integration modules.
2. **The server computes the numbers.** Only it knows the required speed, the SU draw and the network
   state — the client gets them through Jade's NBT, exactly as through the packet for the window.
3. **A single source of texts.** The GUI and Jade build their lines from
   `VeloceModuleInfoLines.build(...)`; a private copy in the plugin would make the two
   descriptions diverge at the first change.

### Machine modules (blocks from `compat/*`)

Implemented modules: **Mekanism** and **Alchemistry**. All item machines
share one core block (`VeloceFeModuleBlock`) and one block entity
(`VeloceFeModuleBlockEntity`) — they differ only in their `FeModule` description (recipe type, FE cost, label).

| Block | Recipe family | Cost |
|------|------------------|-------|
| `veloce_mekanism_crusher_module` | `mekanism:crushing` | 4000 FE/operation |
| `veloce_mekanism_enrichment_module` | `mekanism:enriching` | 4000 FE/operation |
| `veloce_mekanism_combiner_module` | `mekanism:combining` (2 inputs) | 4000 FE/operation |
| `veloce_mekanism_sawmill_module` | `mekanism:sawing` (random output) | 4000 FE/operation |
| `veloce_alchemistry_compactor_module` | `alchemistry:compactor` | 2500 FE/operation |
| `veloce_alchemistry_combiner_module` | `alchemistry:combiner` (N ingredients) | 10 000 FE/operation |
| `veloce_alchemistry_fission_module` | `alchemistry:fission` (1 → 2 outputs) | 15 000 FE/operation |
| `veloce_alchemistry_fusion_module` | `alchemistry:fusion` (2 → 1) | 15 000 FE/operation |
| `veloce_create_millstone_module` | `create:milling` | kinetics |
| `veloce_create_saw_module` | `create:cutting` | kinetics |
| `veloce_create_crushing_module` | `create:crushing` (random outputs) | kinetics |
| `veloce_create_mechanical_crafter_module` | `create:mechanical_crafting` (grids > 3×3) | kinetics |

The costs are copied from the original machines (Mekanism: 20 FE/t × 200 t;
Alchemistry: `energyPerTick` × `ticksPerOperation`), with buffers of
40 000 FE and 100 000 FE respectively. Every machine sits in the network like any other, accepts FE
**directly from a cable attached to the machine** (the `EnergyStorage` capability) or from the energy
item in its own battery slot — but **never through the Veloce network**: our cables carry zero FE.
Clicking the machine shows the accumulator state on the action bar. The sawmill plans only the main
output, and adds the extra one after a dice roll; fission plans both outputs, because both are guaranteed.

Create machines are **kinetic**, not FE-based: "being powered" means `getSpeed() != 0`
(Create itself returns 0 under overstress and on a stopped network). The SU draw is
**constant** regardless of RPM — `calculateStressApplied()` divides a constant by the
speed, because `CStress.setImpact` throws for blocks outside Create.

Drive arrives **from every side**: the block has an `axis` state (the rotation axis chosen when
it is placed, like a Create shaft), the shaft is on both ends of that axis, and `rotate`
switches between the X and Z axes. The side that the **drive** (or a Veloce pipe) arrives from
closes with a **panel** — you can see at a glance where the machine gets its rotation
(`VeloceIntegraleFrame.withClosure` + `updateShape`).

The machines have **elements** that the player adds with a right click:

| Machine | Elements | How it works |
|---------|----------|------------|
| crusher | **2 mill wheels** (1 click = 1 wheel) | with one wheel it spins, but does nothing (`isPowered` requires the full set) |
| mechanical crafter | **eyes** (up to 9×9 = 81) | the recipe grid is computed from the recipe (`getWidth()/getHeight()`) and compared with the built cells; 25 built eyes = everything in the pack; after every click the action bar shows the grid layout itself (`1x2`, `1x3`, … `9x9`) |
| deployer | — | `create:deploying` recipes (e.g. precision mechanism): applies one item onto another |

**Content appearance**: the model is drawn **upright** (we zero out the tilt from the
item transform, because the player said: "the saw and the deployer look sideways, and they should
look up") and with a **per-machine scale** (`VeloceCaseContents.register(...,
scale)`) — machines larger than the casing window are scaled down so that during the animation
they do not poke out above the glass: crusher 0.5, crafter 0.6, press/mixer/
deployer 0.6, saw 0.75, mill 0.8. The values live in one place (the
`CreateCompat` gate), so they can be tuned with a single line.

**Disassembling a machine**: an assembled machine saves the **number
of built-in elements** (`VeloceParts`) in the item's NBT, and placing it recreates it from that NBT.
Putting such an item into the **crafting table** gives back an **empty
casing** (`veloce_integrale`) and **as many base blocks as were inside**
(e.g. 25 eyes → 25 × `create:mechanical_crafter`); the parts return to the grid through
the vanilla mechanic (`getRemainingItems`). The recipe:
`data/craftingveloce/recipe/case_disassembly.json` + the serializer
`craftingveloce:case_disassembly`.

**How a machine is built**: you place an **empty casing** (`veloce_integrale`),
take the Create base block and right-click — **the first click turns the
casing into our module and inserts one element**, and every following click adds
another one (crusher: two mill wheels, crafter: eyes up to 9×9). Inside, **as many
models render as you clicked in** (one, two side by side, then a grid), so
until the set is complete the machine is empty and does not work. A module placed
straight from the creative tab also starts **empty** — it does not pretend to be a finished
machine.

Recipes that require a **Basin** (press, mixer) or **heat** (Blaze
Burner) are available when those things are in the network — we do not build a model of the
flows, we ask about the **presence of the item** (`basin`, `blaze_burner`).
Recipes with fluids are still skipped (we have no fluid layer).

Finished kinetic machines: mill, saw, crusher, mechanical crafter, press,
mixer and deployer — each as an Integrale casing with the Create base block inside.

How to add another module — the whole procedure:

1. `XCompat.register(...)` → `XBlocks`, `XBlockEntities`, `XCapabilities`,
   `XRecipeFamily` and `XModule` (module registration in `FMLCommonSetupEvent`).
2. The block in `compat/<mod>/block/` implements `VeloceNetworkNode` and calls
   `VeloceNodeBlocks.onNodePlaced` / `onNodeRemoved` (guarded by
   `validate_node_blocks`).
3. The block entity implements `VeloceProcessingSource` (+ `IEnergyStorage` for
   FE): it provides `recipeTypes()`, `availableOperations()`, `consumeOperations()`,
   `isPowered()`. The core does not know a single name from the foreign mod.
4. `XModule implements VeloceProcessingModule`: `producible`, `available`,
   `powered`, `recipesFor` — and nothing more.
5. The foreign mod's recipes are translated by `XRecipeHarvest` into a shared `ProcessingEntry`
   (outputs, chances, item counts per ingredient).
6. Resources: blockstate + block model + item model + loot table (the generator
   `scripts/gen_loot_tables.py` reads the `compat/*/*Blocks.java` registries, so
   a new block automatically gets a loot table and a `mineable` tag) + keys in `en_us.json`.
7. An entry in the creative tab **inside** the `displayItems` lambda, under
   `isPresent()` — the tab is always built, also without that mod.

The core requires no change for any of this.

#### Machine window (right click)

**A plain container — identical to the furnace window.** The same `AbstractContainerMenu`
+ `AbstractContainerScreen`, the same texture (`electric_furnace.png`), the same
layout: a title with the block name, the "Inventory" label and the player inventory (x=26, y=84/142).
No custom windows, packets or on-screen drawing.

* **energy machine** (Mekanism, Alchemistry): a battery bar like in the furnace,
  plus the number of operations the accumulator can still afford,
* **kinetic machine** (Create): **no battery** — in its place three lines of
  text: what speed we are getting, whether that is enough, and how much SU the block uses.

The menu reads the numbers from the client-side block entity (`VeloceModuleDisplay.moduleDisplay()`);
the kinetic speed is synchronized by Create, the energy by our block entity.

---

## 🌐 Network (Packets)

All packets use the NeoForge `CustomPacketPayload` / `StreamCodec`.

| Packet | Direction | Contents |
|--------|----------|-----------|
| `BufferPullItemPKT` | C→S | `BlockPos pos, ItemStack itemStack, int count` |
| `ControllerFlowRequestPKT` | C→S | `BlockPos pos` |
| `CraftingTableCycleRecipePKT` | C→S | `BlockPos pos, Item item, ResourceLocation recipeId` |
| `CraftingTableToggleItemPKT` | C→S | `BlockPos pos, Item item` |
| `ExtractorToggleCraftingPKT` | C→S | `BlockPos pos, int filterIndex` |
| `OpenControllerScreenPKT` | S→C | `BlockPos pos, Map<Item, Long> stock, Set<Item> craftingEnabled, Set<Item> furnaceCraftable, boolean furnacePowered, Set<Item> furnacePreferred` |
| `OpenCraftingTableScreenPKT` | S→C | `BlockPos pos, Set<Item> enabledItems, Map<Item, ResourceLocation> preferredRecipes, List<ItemStack> bufferContents` |
| `OpenFilterPKT` | C→S | `BlockPos pos, int filterIndex` |
| `OpenFilterPickerPKT` | S→C | `BlockPos pos, int filterIndex` |
| `OpenTerminalScreenPKT` | S→C | `BlockPos terminalPos` |
| `RequestCraftableCountsPKT` | C→S | `BlockPos pos, List<Item> items` |
| `SensorConfigPKT` | C→S | `BlockPos pos, long threshold, boolean highMode` |
| `ControllerPreferKindPKT` | C→S | `BlockPos pos, Item item, boolean preferFurnace` |
| `SetFilterPKT` | C→S | `BlockPos pos, int filterIndex, ItemStack filterItem` |
| `SyncControllerFlowPKT` | S→C | `BlockPos pos, Map<Item, Long> changed, Set<Item> removed, Map<Item, Float> rates, boolean full` |
| `SyncCraftableCountsPKT` | S→C | `BlockPos pos, Map<Item, Long> counts, boolean complete` |
| `SyncCraftingTableStatePKT` | S→C | `BlockPos pos, Set<Item> enabledItems, Map<Item, ResourceLocation> preferredRecipes` |
| `SyncExtractorFiltersPKT` | S→C | `BlockPos pos, List<ItemStack> filters, List<Boolean> allowCrafting` |
| `SyncTerminalCountsPKT` | S→C | `Map<Item, Long> itemCounts, Map<Item, Long> craftableCounts` |
| `TerminalCraftErrorPKT` | S→C | `BlockPos terminalPos, ItemStack itemStack, String reason, String detail` |
| `TerminalPullItemPKT` | C→S | `BlockPos terminalPos, ItemStack itemStack, int count` |
| `TerminalStoreItemPKT` | C→S | `BlockPos terminalPos, int mode` |
| `TerminalWatcherPKT` | C→S | `BlockPos pos, boolean watching` |

> This table is generated from `VelocePacketHandler.java`. It used to list
> packets that no longer exist (`ExtractorSetFilterPKT`, `ExtractorOpenFilterPKT`)
> and was unaware of eight new ones — exactly the same drift as with the other
> hand-written lists in this project. The build (`scripts/build.py`, step 4)
> now checks that **every registered packet is listed here**.

---

## 🧭 `/cv` diagnostic commands

`/cv block` — **stats for the block you are looking at**: speed and **how many RPM
are missing**, SU draw (and how much the module is asking for), the stress/capacity of the kinetic network,
the clicked-in elements, and for FE machines the energy, the cycle cost and the number of cycles.
The data comes from the same `VeloceModuleInfoSource.moduleInfo` that the machine window
and Jade use, so the command works for Create, Mekanism and Alchemistry.


All of them require **permission level 2** (like `/gamemode`) — previously they had
no requirement at all, so on a server any player could use them.

| Command | What it does |
|---------|---------|
| `/cv debug` | Connection trace for the block you are looking at (side, mode, neighbours) |
| `/cv perf` | Sizes of the chunk loader tracking maps and caches |
| `/cv chunk on\|off\|status\|cleanup` | Preview / cleanup of chunk force-loads |
| `/cv trace` | Toggle for verbose logging of network events |
| `/cv testnet [build]` | Builds a test network and prints the scan result |
| `/cv getitems <item> [recipe]` | Puts **all the ingredients** of the given item's recipe into the chest you are looking at (the id auto-completes like in `/give`; the number selects a recipe when the item has several) |

The permissions are defined in **one** place: `CVCommandRoot.root()`.
Four command files register the same `cv` root (Brigadier merges them into a single
node), so the shared access condition must be identical — otherwise one
command could quietly decide access for all of them.

`/cv getitems` takes the ingredients from **all recipe sources**
(`VeloceRecipeFinder`): crafting table, module families (Create/Alchemistry/
Mekanism) and the furnace — in that order, deduplicated by recipe id. Thanks to
that, an item produced exclusively in a module machine (e.g. `create:crushing_wheel`
from mechanical crafting) has its recipe visible even when the player does not
yet have that machine in the network. The chat shows where the recipe came from
(`Recipe machine: create:mechanical_crafting`), how many items one
execution yields, and **which ingredient variants from the tags** were chosen (e.g. which
planks), because only those go into the chest. It gives **one level** of ingredients (it does not
expand trees like planks → logs) and throws nothing on the ground: whatever did not
fit is listed in the chat.

### ClientTerminalHelper

The only place where the client-side packet handlers touch screens.
Previously every packet looked up its own screen (`instanceof` in several
places), so adding a screen meant remembering all of them.

All public methods (as of now — make sure this list does not
fall behind, the way the packet list and the GUI section once did):

- `openTerminalScreen(BlockPos pos)`
- `clearSavedTerminalViews()` — clears the remembered terminal views (leaving the world)
- `handleSyncCounts(Map<Item, Long> itemCounts)` / `handleSyncCounts(itemCounts, craftableCounts)`
- `openFilterPickerScreen(BlockPos pos, int filterIndex)`
- `reopenFilterHostScreen(BlockPos pos)` — returns from the picker to the GUI of the block it
  was opened from. It restores the **remembered screen** (it does not ask about the menu type —
  that version crashed) and **gives the player back that screen's menu**: the picker
  extends the creative screen, which in its constructor replaces
  `player.containerMenu` with its own `ItemPickerMenu`, and without handing the menu back every
  click in the inventory of the returning GUI is silently rejected
  (`Ignoring click in mismatching container`). It returns `false` when there is nothing to
  return to — in that case the caller must close the screen itself.
- `handleSyncExtractorFilters(BlockPos pos, List<ItemStack> filters, List<Boolean> allowCrafting)`
- `getClientHitResult()` — what the player sees under the cursor (client)
- `openCraftingTableScreen(BlockPos pos, Set<Item> disabledItems, Map<Item, ResourceLocation> preferredRecipes, List<ItemStack> bufferContents)`
- `updateCraftingTableState(BlockPos pos, Set<Item> disabledItems, Map<Item, ResourceLocation> preferredRecipes)`
- `handleCraftableCounts(BlockPos pos, Map<Item, Long> counts, boolean complete)` — the server's response with the "how many can be made" counts
- `handleCraftError(BlockPos pos, ItemStack stack, String reason, String detail)` — the reason a craft from the terminal failed; it lands in the tooltip of **that item** (the action bar was not visible in the GUI). The screen compares the terminal position, so a packet from another terminal is ignored

> **The "how many can be made" counts — two different things, two different measures.**
> The number next to an item answers the question "how many of these **can I have** from what
> is lying in the network", so for furnace recipes what counts is the **raw material**, not how many
> the furnace currently has in its buffer: no powered furnace = furnace recipes disabled,
> any heat reserve = the recipes work, and the number follows the sand
> (64 sand → 64 glass). The **execution** plan still uses the real heat
> budget (`VeloceHeatSources.totalOperations` + `consumeFrom`) — otherwise it would
> promise smelts that the furnace cannot fit into a single plan.
>
> **Rate in the controller: one line, steady trend only.** The server computes samples
> every 5 s (a one-minute window, 13 samples) and every 60 s (a one-hour window, 61 samples), and the rate
> is the difference between the extreme samples divided by the **real** time from the tick
> ring. **Only** items with a one-directional trend reach the GUI
> (≥80% of the movement in one direction and at least two moves in that direction) — hence a single
> `+2.0/min, +120/h` line (one number on two scales) and **no** line for
> items that are standing still or oscillating. The history lives **only in the controller's
> memory** (the controller does not save state to NBT), so after a world restart
> it counts from scratch; a gap in ticking > 20 s wipes the history. The cost: for
> every item, first a cheap test (the difference between the extreme samples, O(1)), and a full
> walk over the samples only for the items that are moving — that is why 100 thousand
> item types in the network do not bog the GUI down.
>
> A counting batch has a shared time budget (25 ms) and is sometimes interrupted
> (`complete=false` in the log). That is why the server starts the next batch where
> it finished the previous one (rotation — otherwise the tail of the list would never get
> recomputed and would keep stale numbers), and the client re-requests until the
> response is complete. Every item in a batch gets an **equal share** of the time:
> one overly complex item is skipped (and goes to the next batch) instead of
> killing the whole batch — previously it ended the batch, so the player's log showed
> `45 item(s) -> 0 result(s)`, that is, a GUI without a single fresh number.
> An item that is produced **exclusively** in the furnace from raw materials without recipes of their own
> (e.g. glass from sand) is counted on a separate, cheap path — without the planner.
- `openControllerScreen(BlockPos pos, Map<Item, Long> stock, Set<Item> craftingEnabled, Set<Item> furnaceCraftable, boolean furnacePowered, Set<Item> furnacePreferred)`

> Watch out for `disabledItems`: the crafter works in an **opt-out** model, so it is a set of
> EXCEPTIONS (disabled items), not a list of enabled ones. Inverting that meaning was
> already a source of a bug once.

---

## 📊 VelocePipeNetworkManager

A `SavedData` held in the `ServerLevel` (`level.getDataStorage()`), so every
dimension has its own instance and there is no static state here at all.

**A flat structure, not network objects.**
The source of truth about connections is `VelocePipeWorld`: a map "pipe position ->
real neighbourhood". The `VelocePipeNetwork` objects are only the RESULT of querying for the
component of that structure — there is no merging or splitting of networks any more.
Thanks to that, cutting a network in two does not require guessing the division: the components
split by themselves, because they follow directly from the connections.

There are TWO network building paths and **both must respect the same rules**:
`scanAndBuildNetwork()` (a world scan) and `toNetwork()` (a rebuild from a
remembered component — this one is what works in practice). Drift between them has been
a source of bugs several times, which is why the rules (Pull mode, disconnected sides,
node kinds) live in shared helpers instead of in both loops separately.

**Node types — the single source of truth is `VeloceNodeBlocks.isNode()`.**
Do not write that list out a second time (that is exactly how two stale
copies in `/cv debug` came about):
`VeloceTomTerminalBlock`, `VeloceControllerBlock`, `VeloceCraftingTableBlock`,
`VeloceExtractorBlock`, `VeloceVelocityFurnaceBlock`,
`VeloceElectricFurnaceBlock`, `VeloceThresholdSensorBlock`.

An **Endpoint** is an ordinary storage connected to a pipe: a chest / NeoForge inventory,
a Refined Storage network or the crafter's buffer. Endpoints remember the
last known contents, so that the terminal sees a chest even when its
chunk is not in the simulation.

**Key methods:**
- `get(ServerLevel)` — the instance for a dimension
- `getNetworkForTerminal(ServerLevel, BlockPos)` — the network for a terminal
- `onTerminalPlaced/Removed(ServerLevel, BlockPos)` — a node joined/left
- `onPipeBroken(ServerLevel, BlockPos)` — a rebuild queue (it does NOT run a BFS immediately)
- `refreshInsertModes(ServerLevel, net, pipes)` — one shared rule for the insertion
  modes for both building paths

**VelocePipeNetwork:**
- `getAllItemCounts(ServerLevel)` → `Map<Item, Long>` — an aggregate from the cache (TTL 10 ticks)
- `capacityFor(ServerLevel, Item)` — how much more will fit (0 = proof that it will not fit;
  `-1` = we do not know — those two must NOT be treated interchangeably)
- `extractItem(ServerLevel, Item, int)` — pulls an item from the first storage that has it

---

## 🕹️ GUI Patterns

**Two families of screens.**
* "Creative-like": `VeloceTerminalScreen`, `VeloceControllerScreen`,
  `VeloceCraftingTableScreen`, `VeloceFilterPickerScreen` — they extend
  `VeloceCreativeScreen`, which extends `CreativeModeInventoryScreen`. They need an item
  grid.
* Machines: `VeloceExtractorScreen`, both furnaces, the sensor — a plain
  `AbstractContainerScreen`. They have no use for an item grid at all.

**The shared base `VeloceCreativeScreen`:**
1. `init()` sets `GameType.CREATIVE` if the player is not in creative
2. `suppressPlayerSlots()` replaces the player slots with inactive slots off
   screen. The `keepPlayerHotbar()` hook decides whether the hotbar stays — the terminal
   overrides it to `true`, because the player drops the pulled items there
3. `renderSlot()` is overridden in each of the four subclasses — it skips the player slots
4. `render()` paints over the hotbar strip (`drawHotbarCover(..., 0xFFC6C6C6)`)
5. `containerTick()` is **not empty**: the base keeps the list filter and
   the hiding of the administrative tabs there, and the terminal, controller and picker
   request the "how many can be made" counts live. An empty version in a subclass
   once meant that after switching a tab the list stopped being filtered
6. `removed()` saves the view, gives creative back its tab, restores the
   `GameType` **and hands back the stack held on the cursor** (into the inventory, and onto
   the ground when it does not fit)

**Number overlays — the shared `VeloceSlotOverlay`** (the terminal, controller, picker and
crafter use the same code):
- scale `0.6f`, with a shadow
- stock: white `0xFFFFFF`, bottom right corner of the slot (`drawStock`)
- "craftable": orange `0xFFA500`, top left corner (`drawCraftable`)
- `formatCount(long)` shortens numbers to "1K", "2.5M" etc.
  (`VeloceTerminalScreen.formatCount` is now only a compatibility delegate)

**Auto-crafting state overlay (`VeloceCraftingTableScreen`):**
- `0x7700AA00` green — the recipe exists and auto-crafting is ON
- `0x77AA0000` red — the recipe exists, but auto-crafting is OFF
- `0xAA000000` dark — the crafter will not make this item
- The `craftableItems` cache is keyed by the `RecipeManager`: a new entry into a world
  creates a new manager, so the cache invalidates itself

---

## 🔍 Transparency / render_type — DISABLED

**Block transparency has been deliberately disabled.** All Veloce blocks
(pipe, extractor, crafting table, terminal) render as `solid`.

**Both** mechanisms were removed:
1. `"render_type": "minecraft:translucent"` from all models in `assets/.../models/block/`
2. the `ItemBlockRenderTypes.setRenderLayer(...)` registration from `CraftingVeloceMod`

### ⚠️ If you want to bring transparency back

You need **both** elements above. On top of that, **the texture must be prepared for it**:

Without `render_type`, pixels with `alpha == 0` render as **black**, they do not disappear.
The pipe texture (`veloce_pipe.png`) is designed so that **no area used
by the UV contains transparent pixels** — that is why the pipe looks correct
as a solid. Check this before changing the UV:

```python
# for every face: count the empty pixels in the UV rectangle
n = sum(1 for y in range(v0, v1) for x in range(u0, u1) if alpha(x, y) == 0)
# n must be 0, otherwise the solid render will show black stripes
```

### History (why we are going back to solid)

A transparent pipe required `translucent`, but successive attempts to fix the UV
(continuity at bends, no black stripes at the joints) did not give a satisfactory
visual result. It was decided to go back to a solid 6×6 pipe with the original
texture. Earlier versions are in the git history (commit `8553ae5` and earlier).

## 🍺 Brewing Stand and special tables

**The Brewing Stand** (`veloce_brewing_stand`) is a fully-fledged Veloce machine:
a block + `VeloceBrewingStandBlockEntity` + menu + screen + Integrale casing +
data (blockstate, item model, loot table). The block entity **extends the
vanilla `BrewingStandBlockEntity`**, so brewing (5 slots: 3 bottles,
an ingredient, blaze powder; `brewTime`, fuel, mixing through `PotionBrewing`
and saving to NBT) is not copied — we get it from vanilla. The menu shows
the machine's 5 slots + the player inventory (x=26, y=84/142), and the screen is the furnace panel
**without** the electric part (brewing has no accumulator).

### What the network can do with special tables

| Table | How vanilla does it | Will the Veloce network make it |
|------|--------------------|------------------------------|
| Fletching table | regular 3×3 crafting | **yes** — the `CRAFTING` family |
| Smithing table | `RecipeType.SMITHING` | **yes** — it is in the `FREE` family |
| Cartography table | logic hard-coded in the menu, **no `RecipeType`** | **no** — it needs its own path |
| Brewing stand | `PotionBrewing`, **no `RecipeType`** | **not yet** — see below |

**A limitation you need to know:** vanilla has no recipe type for either
cartography or brewing — that logic is hard-coded in the blocks. That is why they cannot be added
to `VeloceRecipeFamilies` (which is a set of `RecipeType`s). Brewing in the network
requires **its own path in the planner**: the queries `PotionBrewing.hasMix(bottle,
ingredient)` / `mix(...)`, its own blaze powder accounting and a limit of 3 bottles.
The block brews normally already (manually); network automation is a separate stage.

## 🚧 What is still to do / Known Issues

### 🔴 To do later: preview the crafter buffer in the inventory tab

**Current state:** the "Survival Inventory" tab is **hidden** in the crafter
(`VeloceCraftingTableScreen.acceptTab` rejects `Type.INVENTORY`).

**Why:** two failed attempts, described here so they are not repeated:

1. **Replacing the slots in the vanilla screen** - we overwrote the contents of the player
   inventory slots in `CreativeModeInventoryScreen`. The result: items in the slot
   and a broken layout. The problem: we fought the vanilla layout instead of controlling it.

2. **A dedicated container screen** (`VeloceCrafterStorageMenu/Screen`) - a separate
   screen with a scrollbar. The result: it opened **by itself** on entering the crafter
   (tab detection in `containerTick`, not only on click) and it was impossible
   to leave it. Removed.

**The right way (TODO):** copy `CreativeModeInventoryScreen` into the mod
as our own class and modify it directly. Then the inventory tab can
show the crafter buffer without fighting vanilla, because we have full control over the slot
layout. **Do NOT** do this by replacing slots or by adding a second screen.

- **Crafting Table** — for now only the UI and the state toggle. Auto-crafting (actually crafting items from the network based on the enabled recipes) is not implemented yet
- **Loot tables** — blocks do not drop when destroyed (no `data/craftingveloce/loot_table/blocks/`)
- **Crafting recipes** — no crafting recipes for the blocks (creative only)
- **Textures** — `veloce_crafting_table.png` is a recoloured version of the extractor (placeholder)
- **Pipe: current state** — geometry `5..11` (6×6), `solid` render, texture
  `veloce_pipe.png` = a copy of `veloce_pipe-kopia.png` (purple + greys, fully filled).
  All areas used by the UV are filled, so there are **no black stripes**.
  ⚠️ Do not swap this texture for a version with transparent pixels while the
  render is `solid` — alpha 0 renders as black then.
- **Pipe UV: `[10,0,16,6]` (the core + the arm faces) and `[0,0,5,6]` (the arm sides)** —
  these are the original UVs. They have the drawback that the core and the arm use different regions,
  so the pattern can drift at bends. The repair attempts (commits `f3feac1`–`8553ae5`)
  were rolled back together with the transparency.
- **`crafting/VeloceRecipeGraph.java` — DEAD CODE (212 lines, zero usages)** —
  a reverse recipe index (item ingredient -> what can be made from it), written
  as the foundation for incrementally recomputing craftability. It was never
  wired up: `VeloceCraftingCache` went a different way (a periodic full recomputation
  in the background). The class compiles into the JAR and does nothing. To be decided: either wire
  it up (incremental refresh instead of a full scan), or delete it.
  Verified: no other file in the mod refers to it.

- **src/moze_intel/** — patched classes from the InventoryExchange mod, the logic that disables the EMC tooltips — we need to decide how to solve this cleanly (a separate patch JAR?)
- **Crafter: toggling a WHOLE category (right click on a tab icon)** —
  **REMOVED FROM THE CODE on request** (2026-09-15). It worked as a "round robin":
  a right click on a tab icon enabled or disabled every item in that
  category at once. Cut out together with the tooltip hint, because for categories
  like "Building Blocks" (hundreds of items) one gesture changed the state of half the network
  and could not be undone with a single click.

  The removed elements, in case someone wants to bring it back:
  - `VeloceCreativeScreen.mouseClicked` (the `button == 1` branch)
  - `VeloceCreativeScreen.tabUnderMouse`
  - `VeloceCreativeScreen.renderTabTooltip` (it existed SOLELY for that hint)
  - `VeloceCreativeScreen.toggleWholeTab`
  - the lang keys `category.hint`, `category.willDisable`, `category.willEnable`,
    `crafter.category.hint`

  **What stays** is toggling a single item with a left click
  (`isToggleable` / `isToggledOn` / `applyToggle` in `VeloceCraftingTableScreen`)
  — that works and we are not touching it.

  If we ever come back to this, a more sensible variant: toggling only the
  **visible page** instead of the whole tab. "The whole category" on its own, with no
  safeguard, is too broad.
