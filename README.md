![example](https://cdn.modrinth.com/data/cached_images/0f0bb04a0bfa7e368c184d1c4d1f8c2d96afd8e3.png)
## How to use

**Place the terminal** and connect it with pipes to the storage you want in the
   network — chests, barrels, another mod's storage.
   
**Place a Veloce Integrale and right-click it with the block you want it to
   become** — a crafting table, a furnace, a crusher from Mekanism. The frame turns
   into that machine and joins the network.
   
**Right-click the terminal.** Every item in the network is listed with its
   quantity, and a + number shows how many of each you could craft from the
   resources you have.
   
**Add more machines** and the list grows — each one brings its own recipes.

**Some modules need energy to run**, and they consume a lot of it — instant
   processing has to be paid for, and the price is set so that running this as a farm
   is not worthwhile.

---

# Blocks
![BLOCKS](https://cdn.modrinth.com/data/cached_images/673cbd352c9f692d1ed6154896d8ae509bff5987.png)
## The network

**Veloce Terminal** — your gateway to accessing the Veloce network. Shows the quantity
of each item and how many of each can be crafted.

**Veloce Pipe** — connects everything. Each side is set independently to `Push`,
`Pull` or `Disconnected`, cycled with the wrench. Push and Pull can only be set on
ordinary containers — other network blocks simply connect.

**Veloce Integrale** — the heart of the mod. The casing every machine sits in, and the
block pipes connect to.

**Veloce Wrench** — the tool for configuring pipe sides.

**Veloce Controller** — shows which items in the network can be crafted and which
cannot.


## Moving items

**Veloce Extractor** — continuously pulls matching items out of the network into its
own inventory. It reacts to redstone: while it is powered, it stops pulling, and it
resumes the moment the signal drops.

**Veloce Threshold Sensor** — watches how many of one item are physically in the
network and emits a redstone signal. Two modes: *when at least* and *when below*.
Polls once a second.

## Crafting machines

**Veloce Crafting Table** — decides what the network is allowed to auto-craft.
Click an item to toggle it on or off.

**Velocity Furnace** — the fuel-powered heat source for the auto-crafter. It does not
physically smelt anything itself: it burns fuel continuously and banks the burn as heat,
and the auto-crafter spends that heat to make furnace recipes instantly. You can set
filters for what it is allowed to burn in its GUI; by default it burns everything
burnable.

**Velocity Electric Furnace** — works like an ordinary furnace, except that it smelts with
FE instead of fuel: it burns nothing, and the power comes from its built-in accumulator.

**Veloce Brewing Stand** — the brewing stand for the Veloce network.

## Integration modules

**Mekanism — 6 machines** (all of them need FE to work): crusher, enrichment chamber,
precision sawmill, combiner, osmium compressor, metallurgic infuser.

**Alchemistry — 7 machines** (all of them need FE to work): compactor, combiner,
dissolver, liquifier, atomizer, fission chamber, fusion chamber.

**Create — 7 machines:** millstone, mechanical saw, crushing wheels, mechanical press,
mechanical mixer, deployer, mechanical crafter. Each machine needs a constant
**256 RPM** and **1 024 SU**.

**Crushing Wheels** only work in pairs — a single wheel does nothing.

**Mechanical Crafters** form an array: right-click to add another crafter to it, and
the array can grow up to 9×9.

---

## Integrations

CraftingVeloce can connect with networks from other mods — **Tom's Simple Storage** and
**Refined Storage**.

It also connects with **Create**, **Mekanism** and **Alchemistry**: their machines join
the Veloce network, and everything those machines can produce becomes something you can
ask the terminal for.

---

# For developers

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 |
| Java | 21 |
| Gradle | 8.14.3 (wrapper included) |

Every integration is optional. The mod starts and works with none of Create, Mekanism,
Alchemistry, Tom's Simple Storage, Refined Storage, JEI or Jade installed, because each one
sits behind a gate class that is loaded only once its mod is present — see
`com/craftingveloce/compat/` and the `validate_compat_gates` guard.

## Build and verify

```bash
./gradlew :neoforge:build verifyGuards
```

`verifyGuards` runs the guard suite in `scripts/build.py` against the source and the built
jar. **It must end with `47 passed, 0 failed, 0 skipped`.** The guards are auto-discovered
by `scripts/guards.py` from every `validate_*` function that takes no required arguments,
so a new check cannot be forgotten from a hand-maintained list.

The guards exist because the expensive bugs here are the silent ones: a drop that returns
the wrong item, a config default that drifts from the compiled value, a JEI category that
no longer matches the table the block actually consults. Each guard carries a comment
saying what it protects and, where it applies, the player report that produced it.

## Running the game

```bash
./gradlew :neoforge:runClient
./gradlew :neoforge:runTestClient -PveloceTest=module-create
```

The test client has a game directory of its own (`run-tests/`), so it cannot fight a client
you have open. Test scripts live in `neoforge/veloce-tests/` and are driven by
`VeloceTestDriver`; the verdict is written to `neoforge/run-tests/veloce-test-result.txt`,
whose **first line is `PASS`** on success. Do not read the process exit code — Minecraft
exits non-zero even on a green run.

**Do not rebuild while a client is open.** A running client reads classes lazily, so a
build underneath it leaves it in an inconsistent state.

## Project layout

```
assets/, data/     resources for the mod, pulled into the jar by processResources
src_meta/          META-INF/neoforge.mods.toml, injected the same way
neoforge/          the mod itself - the module that is built
neoforge-26.1.2/   an experimental second target, not part of the release
scripts/           build guards, texture and model generators, isolation test
libs/              local compileOnly jars for mods with no public maven
```

## Architecture in one paragraph

A network is a flat set of pipes and nodes rather than a graph that merges and splits, and
the pipes carry **items only**, never FE. Crafting is planned on numbers rather than on
items: the planner walks recipe trees against a stock snapshot and decides what it will
make before anything moves, which is why a craft can be refused with a reason instead of
failing halfway through. Every machine joins the network through a small interface rather
than a shared base class, and the core never names a foreign mod's type — that isolation is
what `validate_core_isolation` and `validate_compat_gates` are for.

## Configuration

Two files, and the difference between them is the point:

| file | type | holds |
|---|---|---|
| `config/craftingveloce-common.toml` | COMMON | debug logging |
| `config/craftingveloce-server.toml` | SERVER | per-machine FE battery size and cost per operation |

The FE values are a **SERVER** config deliberately: NeoForge syncs server configs to the
client, so a player joining a server receives that server's numbers instead of keeping
their own. A COMMON or STARTUP config would leave every client on different values, which
for a battery size is a disagreement about the game rather than a preference.

Every machine has a section of its own:

```toml
[mekanism_crusher]
    capacity = 25000000
    fePerOperation = 200000
```

The defaults are the values the machines were built with, so a pack that never opens the
file gets exactly the behaviour described above. `validate_block_config_defaults` compares
every default against the machine's compiled value, so the two copies cannot drift apart
unnoticed.

## Diagnostics

Most of what is hard to see has a command: `/cv debug`, `/cv debug perf`,
`/cv debug chunk status`, and `/cv kinetic place|read`, which builds a machine against a
rotating source and reports its speed and the stress it applies. `/cv test run <script>`
replays a test script in game.

`/cv block` — **stats for the block you are looking at**: speed and **how many RPM are
missing**, SU draw (and how much the module is asking for), the stress and capacity of the
kinetic network, the clicked-in elements, and for FE machines the energy, the cycle cost and
the number of cycles. The data comes from the same `VeloceModuleInfoSource.moduleInfo` that
the machine window and Jade use, so the command works for Create, Mekanism and Alchemistry.

Crafting failures carry a reason computed where the failure happened rather than a generic
"item not in network": the missing ingredient, and — when another machine could have made it
— which mods have a recipe for it, shown on the red items in the Veloce Controller tooltip.

## Network packets

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
| `GuiCountsProbePKT` | C→S | `BlockPos pos, int slotsWithItems, int distinctItems, int withCounts, boolean potionCounted, String potionReport, String withoutCounts` |
| `SensorConfigPKT` | C→S | `BlockPos pos, long threshold, boolean highMode` |
| `SetTerminalSearchPKT` | S→C | `String phrase` |
| `ControllerPreferKindPKT` | C→S | `BlockPos pos, Item item, boolean preferFurnace` |
| `SetFilterPKT` | C→S | `BlockPos pos, int filterIndex, ItemStack filterItem` |
| `SyncControllerFlowPKT` | S→C | `BlockPos pos, Map<Item, Long> changed, Set<Item> removed, Map<Item, Float> rates, boolean full` |
| `SyncCraftableCountsPKT` | S→C | `BlockPos pos, Map<Item, Long> counts, Map<Item, String> madeBy, boolean complete` |
| `SyncCraftingTableStatePKT` | S→C | `BlockPos pos, Set<Item> enabledItems, Map<Item, ResourceLocation> preferredRecipes` |
| `SyncExtractorFiltersPKT` | S→C | `BlockPos pos, List<ItemStack> filters, List<Boolean> allowCrafting` |
| `SyncTerminalCountsPKT` | S→C | `Map<Item, Long> itemCounts, Map<Item, Long> craftableCounts` |
| `TerminalCraftErrorPKT` | S→C | `BlockPos terminalPos, ItemStack itemStack, String reason, String detail, String hint` |
| `TerminalPullItemPKT` | C→S | `BlockPos terminalPos, ItemStack itemStack, int count` |
| `TerminalStoreItemPKT` | C→S | `BlockPos terminalPos, int mode` |
| `TerminalWatcherPKT` | C→S | `BlockPos pos, boolean watching` |

> This table is generated from `VelocePacketHandler.java`. It used to list
> packets that no longer exist (`ExtractorSetFilterPKT`, `ExtractorOpenFilterPKT`)
> and was unaware of eight new ones — exactly the same drift as with the other
> hand-written lists in this project. The build (`scripts/build.py`, step 4)
> now checks that **every registered packet is listed here**.

---

## License

MIT — see `LICENSE`.
