package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VeloceFeModuleBlockEntity;
import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
import com.craftingveloce.crafting.ProcessingEntry;
import com.craftingveloce.crafting.VeloceProcessingModule;
import com.craftingveloce.crafting.VeloceProcessingRegistry;
import com.craftingveloce.crafting.VeloceRecipeRegistry;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * End-to-end test of one processing module: build a network, feed it a real
 * recipe, and then ask for the result the way a player does.
 *
 * <p><b>What it proves that no source guard can.</b> The guards check that a
 * module is registered and that the right methods exist. They cannot tell whether
 * a crusher actually crushes, whether the planner finds the recipe, or whether
 * the ingredients in the chest are really consumed and the result really
 * produced. This does: it goes through
 * {@link VeloceTerminalBlockEntity#extractWithReason} - the exact call a
 * player makes by clicking an item in the terminal - so the whole chain runs:
 * network scan, planning, heat/energy, crafting, delivery.
 *
 * <p><b>How it waits.</b> A command runs inside one tick, and a network needs a
 * few ticks to scan its surroundings before it can plan anything. The verdict is
 * therefore scheduled with {@link TickTask} rather than checked immediately -
 * checking straight away would report a failure for a network that is merely
 * still starting up.
 *
 * <p><b>Modules from other mods.</b> Create / Mekanism / Alchemistry modules
 * only exist when those mods are loaded, so their test can only run in a game
 * that has them. The command says so plainly instead of reporting a false pass.
 */
public final class CVModuleTestCommand {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-testmodule");

    /** Ticks to let the network scan before the result is judged. */
    private static final int SCAN_TICKS = 60;

    /**
     * Modules whose recipes cannot be planned yet, so only their rig is built.
     *
     * <p><b>EMPTY, and that is a result.</b> {@code create} used to be here because its
     * machines take ROTATIONAL power and nothing in this mod drove it. The rig now places
     * Create's own creative motor for any machine whose state carries an "axis"
     * (placeRotationSource), so a Create case selects a recipe, fills the barrel and
     * reaches a verdict like every other module.
     *
     * <p>What is left is a POWER problem, not a testing problem, and the verdict says so
     * in one word: {@code craftingveloce.craft.error.moduleUnpowered}. The motor is down
     * and the recipe is right; the rotation is simply not reaching the module yet.
     */
    private static final java.util.Set<String> UNIMPLEMENTED = java.util.Set.of();

    /**
     * The verdict of the last run, readable with {@code /cv testmodule result}.
     *
     * <p>The judgement happens {@link #SCAN_TICKS} ticks after the command
     * returns, so it cannot be the command's own output. A script therefore waits
     * and then asks for the verdict - see {@code veloce-tests/modules.txt}.
     */
    private static String lastVerdict = "none";

    /** Where the last built machine stands, so a later step can set its power. */
    private static BlockPos lastMachinePos;

    /**
     * The barrel of the last build - the network's storage, which is where the fuel
     * furnace pulls its fuel from.
     */
    private static BlockPos lastBarrelPos;

    /** Diagnostic of the last run, appended to the verdict so a test can read it. */
    private static String diag = "none";

    private CVModuleTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("testmodule")
                        .then(Commands.literal("fe")
                                .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .executes(ctx -> setFe(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount")))))
                        // The list of recipes a module offers, so a script can pin every
                        // one of them. The random draw proves a module works for SOME of
                        // its items; only naming all of them proves it works for all.
                        .then(Commands.literal("list")
                                .then(Commands.argument("module", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(VeloceProcessingRegistry.ids(), builder))
                                        .executes(ctx -> list(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "module")))))
                        // Which machines exist, what each one handles, and whether that
                        // type has any recipes at all. Answers "did we add a block that
                        // makes nothing" and "is there a recipe nothing can make".
                        .then(Commands.literal("audit")
                                .then(Commands.argument("module", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(VeloceProcessingRegistry.ids(), builder))
                                        .executes(ctx -> audit(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "module")))))
                        .then(Commands.literal("result").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(lastVerdict), false);
                            return 1;
                        }))
                        // The FUEL furnace's one behaviour that no other command can
                        // reach: burning fills the battery. Everything else here treats
                        // power as something handed to a machine; this one has to watch
                        // the machine earn it, one FE per tick of flame.
                        .then(Commands.literal("chargecheck").executes(ctx -> chargeCheck(ctx.getSource())))
                        .then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(VeloceProcessingRegistry.ids(), builder))
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "module"), null, null))
                                .then(Commands.argument("block", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(machineBlockPaths(), builder))
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "module"),
                                                StringArgumentType.getString(ctx, "block"), null))
                                        // A named recipe makes a failure reproducible:
                                        // the verdict prints which recipe was drawn, so
                                        // a red run can be replayed verbatim. Without a
                                        // name the recipe is drawn at random, which is
                                        // what turns one script into a sweep over the
                                        // module's whole set when run repeatedly.
                                        // string(), not word(): a recipe id is namespaced
                                        // (craftingveloce:brewing_mundane) and Brigadier's
                                        // word() rejects ':' outright - the command then fails
                                        // to parse, never runs, and a script watching for the
                                        // recipe name sees the parse error echo instead.
                                        .then(Commands.argument("recipe", StringArgumentType.string())
                                                .executes(ctx -> run(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "module"),
                                                        StringArgumentType.getString(ctx, "block"),
                                                        StringArgumentType.getString(ctx, "recipe"))))))));
    }

    /** Block paths of our machines, for the optional explicit choice. */
    private static java.util.List<String> machineBlockPaths() {
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && "craftingveloce".equals(id.getNamespace())) {
                paths.add(id.getPath());
            }
        }
        return paths;
    }

    /** Whether this module can make anything at all in this game instance. */
    private static boolean moduleHasAnyRecipe(ServerLevel level, VeloceProcessingModule module) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (!module.recipesAnywhere(level, item).isEmpty()) {
                return true;
            }
        }
        return switch (module.id()) {
            case "crafting" -> !VeloceRecipeRegistry.getAllCraftableItems(level).isEmpty();
            case "furnace" -> !VeloceRecipeRegistry.getAllFurnaceCraftableItems(level).isEmpty();
            default -> false;
        };
    }

    /**
     * Reports, per machine block, the recipe type it handles and how many recipes
     * that type actually has in this instance.
     *
     * <p>Two opposite mistakes hide behind a green test run, and neither is visible
     * from "the module works":
     *
     * <ul>
     *   <li>a machine whose type has NO recipes - a block that was added and makes
     *       nothing, which the rig-only SKIP path would happily report as fine;</li>
     *   <li>a recipe type of the module that NO machine handles - recipes the player
     *       can see in JEI and can never make.</li>
     * </ul>
     *
     * <p>Counted per type through the recipe manager rather than through the module,
     * so the number is what the GAME has, not what our own index believes.
     */
    private static int audit(CommandSourceStack source, String moduleId) {
        ServerLevel level = source.getLevel();
        VeloceProcessingModule module = null;
        for (VeloceProcessingModule candidate : VeloceProcessingRegistry.all()) {
            if (candidate.id().equals(moduleId)) {
                module = candidate;
                break;
            }
        }
        if (module == null) {
            source.sendFailure(Component.literal("§cNo such module: " + moduleId));
            return 0;
        }

        int blocks = 0;
        int emptyBlocks = 0;
        java.util.Set<net.minecraft.world.item.crafting.RecipeType<?>> covered = new java.util.HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !"craftingveloce".equals(id.getNamespace())
                    || !id.getPath().contains(moduleId)
                    || !(block instanceof com.craftingveloce.block.VeloceFeModuleBlock fe)) {
                continue;
            }
            blocks++;
            var type = fe.module().recipeType().get();
            covered.add(type);
            int total = recipeCount(level, type);
            int own = ownRecipes(level, type, moduleId);
            if (total == 0) {
                emptyBlocks++;
            }
            LOG.info("[audit] MACHINE {} | id={} | type={} | recipes={} | ownNamespace={}",
                    id.getPath(), fe.module().id(), BuiltInRegistries.RECIPE_TYPE.getKey(type), total, own);
        }

        int uncovered = 0;
        for (var type : module.recipeTypes()) {
            if (!covered.contains(type)) {
                uncovered++;
                LOG.info("[audit] UNCOVERED {} | type={} | recipes={}",
                        moduleId, BuiltInRegistries.RECIPE_TYPE.getKey(type), recipeCount(level, type));
            }
        }
        // Every recipe type the MOD actually registers, not just the ones our family
        // lists. Comparing against our own list could never reveal a machine we simply
        // forgot to add - the list would agree with itself.
        for (var type : BuiltInRegistries.RECIPE_TYPE) {
            var typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (typeId == null || !moduleId.equals(typeId.getNamespace())) {
                continue;
            }
            int count = recipeCount(level, type);
            boolean ours = covered.contains(type);
            LOG.info("[audit] NAMESPACE_TYPE {} | recipes={} | {}", typeId, count,
                    ours ? "MACHINE_PRESENT" : (count > 0 ? "*** NO_MACHINE ***" : "no recipes"));
        }
        LOG.info("[audit] SUMMARY {} | machines={} | emptyMachines={} | declaredTypes={} | uncoveredTypes={}",
                moduleId, blocks, emptyBlocks, module.recipeTypes().size(), uncovered);

        final int fBlocks = blocks;
        final int fEmpty = emptyBlocks;
        final int fUncovered = uncovered;
        source.sendSuccess(() -> Component.literal("§6[audit] §f" + moduleId + "§7 machines=" + fBlocks
                + " empty=" + fEmpty + " uncovered=" + fUncovered), false);
        return blocks;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int recipeCount(ServerLevel level, net.minecraft.world.item.crafting.RecipeType<?> type) {
        return level.getRecipeManager().getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) type).size();
    }

    /** How many of those recipes produce an item of the mod's own namespace. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int ownRecipes(ServerLevel level, net.minecraft.world.item.crafting.RecipeType<?> type, String namespace) {
        int own = 0;
        for (Object holder : level.getRecipeManager().getAllRecipesFor(
                (net.minecraft.world.item.crafting.RecipeType) type)) {
            var recipe = ((net.minecraft.world.item.crafting.RecipeHolder<?>) holder).value();
            var id = BuiltInRegistries.ITEM.getKey(recipe.getResultItem(level.registryAccess()).getItem());
            if (id != null && namespace.equals(id.getNamespace())) {
                own++;
            }
        }
        return own;
    }

    /** Prints every recipe id this module offers, one per line. */
    private static int list(CommandSourceStack source, String moduleId) {
        ServerLevel level = source.getLevel();
        VeloceProcessingModule module = null;
        for (VeloceProcessingModule candidate : VeloceProcessingRegistry.all()) {
            if (candidate.id().equals(moduleId)) {
                module = candidate;
                break;
            }
        }
        if (module == null) {
            source.sendFailure(Component.literal("§cNo such module: " + moduleId));
            return 0;
        }
        java.util.Set<String> ids = new java.util.TreeSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            for (ProcessingEntry entry : module.recipesAnywhere(level, item)) {
                ids.add(entry.id().toString());
            }
        }
        // The built-in crafting/furnace modules publish a registry set instead.
        java.util.Set<Item> registryItems = switch (moduleId) {
            case "crafting" -> VeloceRecipeRegistry.getAllCraftableItems(level);
            case "furnace" -> VeloceRecipeRegistry.getAllFurnaceCraftableItems(level);
            default -> java.util.Set.of();
        };
        for (Item item : registryItems) {
            for (ProcessingEntry entry : com.craftingveloce.crafting.VeloceRecipeFinder.all(level, item)) {
                if (module.recipeTypes().contains(entry.type())) {
                    ids.add(entry.id().toString());
                }
            }
        }
        // Also on the log: command feedback goes to the player's chat and does NOT reach
        // the server log, so without this the list is invisible to a test harness that
        // can only read the log.
        LOG.info("[testmodule] {} offers {} recipe(s)", moduleId, ids.size());
        // One id per line: a single joined line runs into the log's line limit once a
        // module offers a few dozen recipes, and the tail is dropped SILENTLY - which
        // is how a sweep generated from this list came out one recipe short.
        for (String id : ids) {
            LOG.info("[testmodule] RECIPE {}", id);
        }
        source.sendSuccess(() -> Component.literal("§6[testmodule]§f " + moduleId
                + " offers " + ids.size() + " recipe(s)"), false);
        for (String id : ids) {
            source.sendSuccess(() -> Component.literal("RECIPE " + id), false);
        }
        return ids.size();
    }

    private static int run(CommandSourceStack source, String moduleId, String wantedBlock, String wantedRecipe) {
        ServerLevel level = source.getLevel();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/cv testmodule needs a player (the network is built next to one)"));
            return 0;
        }

        VeloceProcessingModule module = null;
        for (VeloceProcessingModule candidate : VeloceProcessingRegistry.all()) {
            if (candidate.id().equals(moduleId)) {
                module = candidate;
                break;
            }
        }
        if (module == null) {
            source.sendFailure(Component.literal("§cNo such module: " + moduleId
                    + " §7(known: " + String.join(", ", VeloceProcessingRegistry.ids()) + ")"));
            return 0;
        }

        BlockPos base = player.blockPosition().offset(2, 0, 0);

        // Clear the build area first. The dev world persists between runs, so a
        // machine left by an EARLIER case stays in the network and answers for the
        // one under test: a velocity_furnace left from a previous script made the
        // electric furnace look as if it smelted on no charge, because the heat
        // came from the leftover. Each case has to start from nothing for its
        // result to mean anything.
        clearBuildArea(level, base);

        // --- 1. the machine block of this module ---
        Block machine = findMachineBlock(moduleId, wantedBlock);
        if (machine == null) {
            source.sendFailure(Component.literal("§c" + moduleId
                    + " has no machine block registered - is the mod that provides it installed?"));
            return 0;
        }

        // --- 2. the network: terminal | pipe | machine, barrel of ingredients ---
        BlockPos terminalPos = base;
        BlockPos pipePos = base.offset(1, 0, 0);
        BlockPos machinePos = base.offset(2, 0, 0);
        BlockPos barrelPos = base.offset(1, 0, 1);

        level.setBlock(terminalPos, VeloceRegistry.VELOCE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pipePos, VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(machinePos, machine.defaultBlockState(), Block.UPDATE_ALL);
        lastMachinePos = machinePos;

        // Register the machine as a network node, which is what a player's
        // placement does: setBlock() does NOT run setPlacedBy, so without this the
        // machine stands there connected to a pipe but is not in the network's node
        // list. Modules that look the machine up through that list then report
        // "needs a machine" while one is plainly standing there - which is exactly
        // how the brewing stand first failed here.

        level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
        lastBarrelPos = barrelPos;

        // Give the machine power so an FE-driven module is not judged on "no energy"
        // - the test is about crafting, not about the accumulator being full.
        level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                        machinePos, null);

        // A player's placement runs two different hooks, and setBlock runs neither:
        //   * the PIPE registers itself through onPipePlaced, which is what creates
        //     the network at all;
        //   * the terminal and the machines register through onNodePlaced.
        // Doing only the node half left the machines attached to a pipe that was
        // never part of any network - so a module looking its machine up through
        // the network reported "needs a machine" with the machine right there.
        // Run the SAME hook the game runs when a player places a block, in the same
        // order, instead of calling individual registration methods by hand.
        //
        // Trying to pick the right hook myself went wrong twice: setBlock runs
        // neither onPipePlaced (which creates the network) nor onNodePlaced (which
        // registers a machine), and calling them by hand still left the brewing
        // stand invisible to its own module. setPlacedBy is what the game calls, so
        // whatever a hand-placed block ends up registered as, this is too.
        placeLikePlayer(level, pipePos);
        placeLikePlayer(level, terminalPos);
        // THE MOTOR GOES DOWN FIRST, and that is the same lesson this project already
        // learned with pipes: placing the MACHINE notifies its neighbours, so a source
        // that is already standing there is seen and the machine recomputes its rotation
        // on the spot. Placing it afterwards leaves the machine holding the rotation it
        // computed for an empty neighbour - and it reports itself unpowered forever.
        placeRotationSource(level, machinePos, machine);
        // THE MACHINE GOES DOWN ALREADY CARRYING axis=x, exactly as /cv kinetic place
        // does it, and only THEN does the placement hook run.
        //
        // This is not tidiness. A kinetic block entity takes its rotation axis when it is
        // CREATED; placing the block first (which takes the axis from the player's look)
        // and rewriting the state afterwards changes the block STATE while the block
        // entity still turns about the old axis - and the machine then reports itself
        // unpowered forever while a motor spins right next to it. That is precisely what
        // the verdict said: "moduleUnpowered".
        level.setBlock(machinePos,
                setByName(machine.defaultBlockState(), "axis", Direction.Axis.X),
                Block.UPDATE_ALL);
        placeLikePlayer(level, machinePos);

        if (UNIMPLEMENTED.contains(moduleId)) {
            String notice = "testing for " + moduleId + " not implemented";
            // Both channels on purpose: the script asserts on the chat line, and the
            // log is what a human reads afterwards to see what a green run covered.
            LOG.info("[testmodule] {} - rig built at {}, no recipe was tested", notice, machinePos);
            source.sendSuccess(() -> Component.literal("\u00a7e[testmodule] " + notice
                    + "\u00a77 (rig built: terminal, pipe, " + moduleId
                    + " machine and a barrel - nothing was crafted)"), false);
            lastVerdict = "SKIP " + moduleId + ": " + notice;
            return 1;
        }

        // --- 3. a recipe this module can actually do ---
        // Picked AFTER the blocks are placed, because the built-in crafting module
        // derives its item set from the crafting tables it can see in the network -
        // asking before the network exists answers "no recipe" for a machine that is
        // about to be perfectly capable of crafting.
        ProcessingEntry recipe = findRecipe(level, module, wantedRecipe, machine);
        if (recipe == null) {
            // Two different situations, and only one of them is a defect.
            //
            // A machine with NO recipes in this pack (Mekanism's nucleosynthesizer
            // with no such recipes installed) has nothing to test: reporting FAIL
            // would blame the machine for the pack, and 97 such lines would bury the
            // real failures. A module that HAS recipes but cannot produce one is a
            // defect and still fails.
            if (wantedRecipe == null && moduleHasAnyRecipe(level, module)) {
                String notice = moduleId + " has no recipe for this machine in this game instance";
                LOG.info("[testmodule] SKIP {} - {}", moduleId, notice);
                source.sendSuccess(() -> Component.literal("§e[testmodule] no recipe for "
                        + moduleId + " on this machine - skipping"), false);
                lastVerdict = "SKIP " + moduleId + ": no recipe for this machine in this game instance";
                return 1;
            }
            source.sendFailure(Component.literal("§c" + moduleId
                    + (wantedRecipe == null
                            ? " has no recipe available in this game instance"
                            : " cannot do recipe '" + wantedRecipe + "'")));
            return 0;
        }
        // The full result stack, NOT just its item. A potion's identity lives in a
        // data component, so every potion shares one item - asking the terminal for
        // "minecraft:potion" asks for a plain water bottle and would let the test
        // pass while the recipe's actual potion was never made.
        ItemStack wanted = recipe.primaryResult();

        int ingredientsPut = fillBarrel(level, barrelPos, recipe);

        source.sendSuccess(() -> Component.literal("§6[testmodule] §f" + moduleId
                + " §7building and waiting " + SCAN_TICKS + " ticks for the network scan"), false);
        source.sendSuccess(() -> Component.literal("§7  want §f" + wanted.getHoverName().getString()
                + "§7 (" + wanted.getItem() + "), recipe: §f" + recipe.id()
                + "§7, ingredients placed: §f" + ingredientsPut), false);

        // --- 4. judge after the scan has had time to run ---
        var server = level.getServer();
        // Through the script runner's wait pump, NOT server.tell(TickTask): see
        // VeloceTestScript for why that ran the judgement immediately, leaving the network
        // judged in the same tick it was built in.
        com.craftingveloce.test.VeloceTestScript.scheduleWait(server, SCAN_TICKS,
                () -> judge(level, terminalPos, wanted, moduleId, recipe.id().toString()));

        return 1;
    }

    /**
     * The verdict: ask the terminal for the item exactly as a player would.
     *
     * <p>This is the whole point of the test - not "is the module registered", but
     * "does the network produce the item and hand it over".
     */
    private static void judge(ServerLevel level, BlockPos terminalPos, ItemStack wanted, String moduleId, String recipeId) {
        var manager = VelocePipeNetworkManager.get(level);
        var net = manager.getNetworkForTerminal(level, terminalPos);
        if (net == null) {
            fail(moduleId, recipeId, "the terminal is not connected to a network after " + SCAN_TICKS + " ticks");
            return;
        }
        if (!(level.getBlockEntity(terminalPos) instanceof VeloceTerminalBlockEntity terminal)) {
            fail(moduleId, recipeId, "no terminal block entity at " + terminalPos);
            return;
        }

        // Stock is counted under the item the network actually stores. A potion is
        // filed under its proxy item, so counting "minecraft:potion" would report an
        // empty stock for a potion the network is holding.
        Item stockKey = com.craftingveloce.util.VelocePotionMapper.getProxy(wanted);
        long stock = net.getAllItemCounts(level).getOrDefault(stockKey, 0L);

        // MEASURE, do not guess. Two faults look identical from the outside:
        //   (a) the machine never made it into the network, or
        //   (b) the network the terminal resolved to is not the one holding the
        //       machine.
        // Printing the node list and the machine's own view of the network tells
        // them apart immediately.
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "[testmodule] DIAG net=%s terminals=%d machineAt=%s machineIsTerminal=%s",
                net.getId(),
                net.getTerminals().size(),
                lastMachinePos,
                lastMachinePos != null && net.getTerminals().contains(lastMachinePos));
        // Goes into the verdict text, not the log: the VERBOSE channel is filtered
        // out of the game log, so a diagnostic written there is invisible - which is
        // why the first attempt at this produced no output at all.
        StringBuilder nodes = new StringBuilder();
        for (BlockPos node : net.getTerminals()) {
            nodes.append(node).append('=').append(level.getBlockState(node).getBlock()).append(' ');
        }
        diag = "net=" + net.getId()
                + " terminals=" + net.getTerminals().size()
                + " machineAt=" + lastMachinePos
                + " machineIsTerminal=" + (lastMachinePos != null && net.getTerminals().contains(lastMachinePos))
                + " nodes=[" + nodes.toString().trim() + "]";

        VeloceTerminalBlockEntity.PullResult pulled;
        try {
            // count=1, allowCrafting=true: the player path. The result stack is sent
            // whole - components included - so the terminal resolves the same proxy a
            // player clicking that entry in the terminal would resolve.
            pulled = terminal.extractWithReason(wanted.copyWithCount(1), 1, true);
        } catch (Throwable t) {
            fail(moduleId, recipeId, "asking the terminal threw " + t);
            return;
        }

        if (pulled == null || pulled.stack().isEmpty()) {
            String why = pulled == null ? "no result" : pulled.reason() + " (" + pulled.detail() + ")";
            fail(moduleId, recipeId, "terminal could not deliver " + wanted.getItem()
                    + " from recipe " + recipeId + ": " + why);
            return;
        }
        // A potion's identity is a data component, so "a potion came back" is NOT the
        // question - every potion is minecraft:potion. Compare the contents, or a
        // delivery of plain water would be scored as a successful brew.
        if (!samePotion(wanted, pulled.stack())) {
            fail(moduleId, recipeId, "terminal delivered " + describePotion(pulled.stack())
                    + " for recipe " + recipeId + ", which makes " + describePotion(wanted));
            return;
        }
        // What the TERMINAL would show for this item - the same call its "+N" overlay
        // comes from. Delivery working and the number working are two different things:
        // the first is the crafter, the second is the count path, and this is the only
        // place that says out loud which of them a network has.
        long shown = terminal.computeCraftableCounts(java.util.List.of(wanted.getItem()))
                .counts().getOrDefault(wanted.getItem(), 0L);
        pass(moduleId, wanted, recipeId, stock, pulled.stack().getCount(), shown);
    }

    /**
     * Which potion STATE a stack stands for, container included - our proxy key format.
     *
     * <p>Container matters and the ITEM does not: a splash and a lingering potion of the
     * same kind are different states, and every drinkable potion is {@code
     * minecraft:potion}. An earlier version read only {@code POTION}, so a delivered
     * splash potion reported as the bare item {@code minecraft:splash_potion} and a
     * correct delivery was scored a mismatch.
     */
    private static String potionIdentity(ItemStack stack) {
        String fromStack = com.craftingveloce.util.VelocePotionMapper.proxyKey(stack);
        if (fromStack != null) {
            return fromStack;
        }
        return com.craftingveloce.util.VelocePotionMapper.proxyKeyOf(stack.getItem());
    }

    /** Do these two stacks stand for the same potion state? */
    private static boolean samePotion(ItemStack a, ItemStack b) {
        String ia = potionIdentity(a);
        String ib = potionIdentity(b);
        return ia != null || ib != null ? java.util.Objects.equals(ia, ib) : a.getItem() == b.getItem();
    }

    private static String describePotion(ItemStack stack) {
        String identity = potionIdentity(stack);
        return identity == null ? stack.getItem().toString()
                : identity + " (" + stack.getItem() + ")";
    }

    /**
     * Sets the accumulator of the machine built by the last run.
     *
     * <p>Power is a separate axis from "can this module craft at all", and the
     * cases differ in what they answer: an empty accumulator must refuse with a
     * power reason, a partly filled one must still refuse, and a full one must
     * deliver. Testing only the full case would hide a machine that ignores its
     * charge.
     *
     * <p>The amount is written through the standard energy capability, so it goes
     * in exactly the way a cable would charge the machine.
     */
    private static int setFe(CommandSourceStack source, int amount) {
        if (lastMachinePos == null) {
            source.sendFailure(Component.literal("§cNo machine from a previous run - start with /cv testmodule <module>"));
            return 0;
        }
        ServerLevel level = source.getLevel();

        // The FUEL furnace has no energy capability ON PURPOSE - its accumulator must not
        // be reachable by a cable from another mod - so the capability route below cannot
        // serve it. This is the one place that writes its accumulator from outside, and it
        // does it by reaching into the block entity, which is exactly the statement that
        // nothing else can: no cable, no item, no other mod's pipe. Without it the only way
        // to charge a fuel furnace for a test would be to burn a whole coal and wait
        // out its flame, which no script can do.
        if (level.getBlockEntity(lastMachinePos)
                instanceof com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity fuelFurnace) {
            fuelFurnace.setEnergyForTesting(amount);
            int now = fuelFurnace.getEnergy();
            source.sendSuccess(() -> Component.literal("§6[testmodule] §7heat set to §f" + now
                    + " §7(" + fuelFurnace.getAffordableSmelts() + " smelt(s), asked "
                    + amount + ")"), false);
            return now;
        }

        var storage = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, lastMachinePos, null);
        if (storage == null) {
            source.sendFailure(Component.literal("§cThe machine at " + lastMachinePos + " has no energy storage"));
            return 0;
        }
        // Drain first: the cases are absolute values, not increments, so a leftover
        // charge from the previous case must not leak into this one.
        int drained = 0;
        int guard = 0;
        while (storage.getEnergyStored() > 0 && guard++ < 10_000) {
            drained += storage.extractEnergy(storage.getEnergyStored(), false);
        }
        int accepted = storage.receiveEnergy(amount, false);
        int now = storage.getEnergyStored();
        source.sendSuccess(() -> Component.literal("§6[testmodule] §7FE set to §f" + now
                + " §7(asked " + amount + ", accepted " + accepted + ")"), false);
        return now;
    }

    /**
     * How long the charging check watches the fuel furnace, and how much coal it feeds it.
     *
     * <p>Long enough that the pull (up to PULL_INTERVAL_TICKS) and the first burn are
     * comfortably inside the window, and short enough that the check costs a few seconds.
     */
    private static final int CHARGE_CHECK_TICKS = 320;
    private static final int CHARGE_CHECK_COAL = 8;

    /**
     * How much heat {@code ticks} server ticks of burning should put into the accumulator.
     *
     * <p>Asked of the furnace rather than written down here, because it is exactly the
     * number this check exists to measure: the rate is what the player asked to change,
     * and a copy of it in the test would keep passing after the furnace changed. The rate
     * is a fraction, so the answer is rounded up - the carry can put one burn tick more
     * into the window than the exact average.
     */
    private static long chargeAfter(int ticks) {
        return (long) ticks
                * com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity.BURN_PER_TICK_NUM
                / com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity.BURN_PER_TICK_DEN
                + 1;
    }

    /**
     * Proves the fuel furnace CHARGES ITS BATTERY BY BURNING - the whole point of it.
     *
     * <p>Nothing else in this command can see that. Every other power check hands a
     * machine its charge; this one empties the accumulator, puts coal where the furnace
     * pulls from, and comes back later to see whether the number went up on its own.
     *
     * <p>Both bounds matter, and they are the reason this is not just "energy &gt; 0":
     * <ul>
     *   <li>too little - the furnace is not charging (or is charging from nothing);</li>
     *   <li>too much - more than one FE per tick got in, which would mean the burn and
     *       the charge had been double-counted somewhere.</li>
     * </ul>
     */
    private static int chargeCheck(CommandSourceStack source) {
        if (lastMachinePos == null || lastBarrelPos == null) {
            source.sendFailure(Component.literal("§cRun /cv testmodule furnace velocity_furnace first"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(lastMachinePos)
                instanceof com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity furnace)) {
            source.sendFailure(Component.literal("§cThe machine under test is not a fuel furnace - "
                    + "only it charges by burning"));
            return 0;
        }
        if (!(level.getBlockEntity(lastBarrelPos) instanceof BarrelBlockEntity barrel)) {
            source.sendFailure(Component.literal("§cNo barrel for the last build - the furnace "
                    + "would have nothing to pull fuel from"));
            return 0;
        }

        furnace.setEnergyForTesting(0);
        // Slot 0 of the barrel: the network's storage, which is exactly where the furnace
        // looks for fuel. Any ingredient left there from the build is irrelevant here -
        // this check measures charging, not crafting.
        barrel.setItem(0, new ItemStack(net.minecraft.world.item.Items.COAL, CHARGE_CHECK_COAL));

        lastVerdict = "charging: waiting " + CHARGE_CHECK_TICKS + " ticks for the burn";
        // On the command channel as well as the log: a script asserts on the reply, and
        // "the command ran" has to be distinguishable from "the command failed to parse"
        // - a parse failure echoes the input back and would satisfy a looser assertion.
        source.sendSuccess(() -> Component.literal("§6[testmodule] §7charging check started: §f"
                + CHARGE_CHECK_COAL + " coal§7 in the network, accumulator emptied, watching "
                + CHARGE_CHECK_TICKS + " ticks"), false);
        LOG.info("[testmodule] charging check started: energy=0, {} coal placed in the network",
                CHARGE_CHECK_COAL);

        var server = level.getServer();
        com.craftingveloce.test.VeloceTestScript.scheduleWait(server, CHARGE_CHECK_TICKS, () -> {
            int now = furnace.getEnergy();
            // The ceiling is what the rate allows; the floor is three quarters of it,
            // because the furnace has to pull the coal out of the network first and that
            // costs it a few ticks of flame. A quarter of slack still catches a rate
            // that is wrong by half, which is what this check is really for.
            long max = chargeAfter(CHARGE_CHECK_TICKS);
            long min = max * 3 / 4;
            boolean ok = now >= min && now <= max;
            lastVerdict = (ok ? "charging PASS" : "charging FAIL")
                    + ": " + now + " FE after " + CHARGE_CHECK_TICKS + " ticks of burning"
                    + " (expected " + min + ".." + max + "), "
                    + furnace.getAffordableSmelts() + " smelt(s) banked";
            LOG.info("[testmodule] {}", lastVerdict);
        });
        return 1;
    }

    private static void pass(String moduleId, ItemStack wanted, String recipeId, long stockBefore,
                             int delivered, long terminalShows) {
        // The recipe id goes into the verdict so a randomly drawn run that fails can
        // be replayed exactly: the test is meant to be run over and over, and a
        // failure is only useful if it can be reproduced.
        String drawn = "recipe=" + recipeId + " item=" + describePotion(wanted)
                + " terminalShows=" + terminalShows;
        lastVerdict = "PASS " + moduleId + " " + drawn + " | " + diag;
        // On the log as well: the chat line reaches the script, but a human reading the
        // log afterwards needs to see the same number without re-running the case.
        LOG.info("[testmodule] {}", lastVerdict);
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "[testmodule] PASS %s: delivered %dx %s (stock before: %d)",
                moduleId, delivered, wanted.getItem(), stockBefore);
    }

    private static void fail(String moduleId, String recipeId, String why) {
        lastVerdict = "FAIL " + moduleId + " recipe=" + recipeId + ": " + why + " | " + diag;
        // On the log as well - a failure is exactly the case a human wants to read
        // afterwards, and the chat line is gone by then.
        LOG.info("[testmodule] {}", lastVerdict);
        com.craftingveloce.util.VeloceLog.Block.error(
                com.craftingveloce.util.VeloceLog.Side.SERVER, null,
                "[testmodule] FAIL %s: %s", moduleId, why);
    }

    /**
     * Puts Create's creative motor against a kinetic machine, if the machine takes
     * rotation at all.
     *
     * <p>Generic on purpose: it does not test for the Create module by name, it tests
     * whether the placed state HAS an "axis" property, which is what makes a block
     * kinetic. A Mekanism or Alchemistry machine has no such property and is left alone.
     *
     * @return whether a motor was placed
     */
    private static boolean placeRotationSource(ServerLevel level, BlockPos machinePos, Block machineBlock) {
        Block motor = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "creative_motor"));
        if (motor == null || motor == Blocks.AIR) {
            return false;   // Create is not installed - nothing to drive it with
        }
        if (machineBlock.defaultBlockState().getProperties().stream()
                .noneMatch(p -> p.getName().equals("axis"))) {
            return false;   // not a rotational machine
        }
        // Only the motor here; the machine goes down next and its own placement sets the
        // axis. A motor standing EAST of the module faces WEST, because Create answers
        // hasShaftTowards with side == FACING.
        level.setBlock(machinePos.east(),
                setByName(motor.defaultBlockState(), "facing", Direction.WEST), Block.UPDATE_ALL);
        LOG.info("[testmodule] rotation source {} placed east of {}", motor, machinePos);
        return true;
    }

    /**
     * Sets a block state property BY NAME.
     *
     * <p>The property instance has to come from the block itself: Create's
     * {@code DirectionalKineticBlock.FACING} is its own DirectionProperty and not
     * vanilla's, and setting a property the block does not own throws.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setByName(BlockState state, String name, Comparable value) {
        for (net.minecraft.world.level.block.state.properties.Property<?> property
                : state.getProperties()) {
            if (property.getName().equals(name) && property.getPossibleValues().contains(value)) {
                return state.setValue(
                        (net.minecraft.world.level.block.state.properties.Property) property, value);
            }
        }
        return state;
    }

    /** Runs the placement hook the game runs when a player places this block. */
    private static void placeLikePlayer(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    /**
     * Removes our blocks and any barrels around the build area.
     *
     * <p>Only the region this command builds in, and only our own blocks plus the
     * barrels it places - so a leftover network from an earlier case cannot
     * contribute heat, storage or a crafter to the case being judged.
     */
    private static void clearBuildArea(ServerLevel level, BlockPos base) {
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-4, -1, -3), base.offset(4, 1, 3))) {
            BlockState state = level.getBlockState(p);
            var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            boolean ours = id != null && "craftingveloce".equals(id.getNamespace());
            boolean barrel = state.is(Blocks.BARREL);
            if (ours || barrel) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** The machine block registered for this module id, or null when its mod is absent. */
    private static Block findMachineBlock(String moduleId, String wantedBlock) {
        // An explicit block wins. This matters for the furnace: velocity_furnace
        // burns fuel while electric_furnace has a Forge Energy accumulator, and the
        // two answer different questions. Without the choice, "furnace" resolves to
        // whichever the registry happens to list first, and the FE cases would be
        // measuring a machine that has no accumulator at all.
        if (wantedBlock != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(
                    wantedBlock.contains(":") ? wantedBlock : "craftingveloce:" + wantedBlock);
            Block block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
            return block == Blocks.AIR ? null : block;
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !"craftingveloce".equals(id.getNamespace())) {
                continue;
            }
            if (id.getPath().contains(moduleId)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Draws a recipe this module can actually do.
     *
     * <p><b>Random, not first.</b> Taking the first recipe the module lists means
     * every run exercises the same item, so the test answers "does this module work
     * for ONE item" - not "for the items it offers". Drawing at random turns a
     * single script into a sweep: run it a hundred times and a hundred different
     * items are tested. A drawn failure is reproducible because the verdict prints
     * the recipe id and it can be passed back in as {@code wantedRecipe}.
     *
     * <p><b>Why the pool is a list of items, not of recipes.</b> Building every
     * recipe up front would mean walking the whole item registry through the recipe
     * finder on each run. Instead the candidate items are shuffled and the first one
     * that yields a recipe wins - one lookup on a healthy run, a full walk only when
     * the module genuinely has nothing.
     *
     * @param wantedRecipe recipe id to test, or {@code null} to draw at random
     */
    private static ProcessingEntry findRecipe(ServerLevel level, VeloceProcessingModule module,
                                               String wantedRecipe, Block machine) {
        // Two different module APIs, and mixing them is not cosmetic:
        //
        //   * the built-in crafting/furnace modules publish a SET of producible items
        //     through the recipe registry, and the concrete recipe comes from the
        //     shared finder;
        //   * brewing and the compat modules (Create/Mekanism/Alchemistry) answer per
        //     item through recipesAnywhere and publish no set.
        //
        // Using the shared finder for the second kind is wrong: it answers "how is
        // this made" across EVERY source, so a brewing test drew minecraft:oak_fence
        // and create:jungle_window - recipes the brewing stand has nothing to do
        // with - and failed a module that works.
        java.util.Set<Item> registryItems = switch (module.id()) {
            case "crafting" -> VeloceRecipeRegistry.getAllCraftableItems(level);
            case "furnace" -> VeloceRecipeRegistry.getAllFurnaceCraftableItems(level);
            default -> java.util.Set.of();
        };
        boolean fromRegistry = !registryItems.isEmpty();

        List<Item> probe;
        if (fromRegistry) {
            probe = new java.util.ArrayList<>(registryItems);
        } else {
            // The module's own list is the only honest source: collect exactly the
            // items it says it can make, so a random draw is uniform over the module.
            probe = new java.util.ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (!module.recipesAnywhere(level, item).isEmpty()) {
                    probe.add(item);
                }
            }
        }

        if (wantedRecipe != null) {
            for (Item item : probe) {
                for (ProcessingEntry entry : fromRegistry
                        ? registryRecipesFor(level, module, item)
                        : module.recipesAnywhere(level, item)) {
                    if (entry.id().toString().equals(wantedRecipe) && machineCanDo(level, machine, entry)) {
                        return entry;
                    }
                }
            }
            return null;
        }

        java.util.Collections.shuffle(probe, new java.util.Random(level.getRandom().nextLong()));
        for (Item item : probe) {
            List<ProcessingEntry> found = fromRegistry
                    ? registryRecipesFor(level, module, item)
                    : module.recipesAnywhere(level, item);
            for (ProcessingEntry entry : found) {
                if (machineCanDo(level, machine, entry)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Can the machine standing in the rig perform this recipe?
     *
     * <p>A compat module covers a whole FAMILY of machines - Mekanism registers
     * twenty-three - and each machine is its own block. Testing the family through
     * one block would draw a recipe belonging to a different machine and report a
     * failure that says nothing about either. The block that was placed has to be the
     * one that can do the recipe under test.
     *
     * <p>Unknown machines (the built-in ones, whose blocks carry no recipe-type list)
     * answer yes: this narrows nothing for them, which is the previous behaviour.
     */
    private static boolean machineCanDo(ServerLevel level, Block machine, ProcessingEntry entry) {
        if (machine == null || lastMachinePos == null) {
            return true;
        }
        if (!(level.getBlockEntity(lastMachinePos) instanceof VeloceFeModuleBlockEntity fe)) {
            return true;
        }
        return fe.recipeTypes().contains(entry.type());
    }

    /**
     * Registry-path recipes, narrowed to the types this module owns.
     *
     * <p>{@code VeloceRecipeFinder.all} answers "how is this made" and deliberately
     * mixes every source: the recipe registry, all module families and the furnace.
     * That is right for a player asking about an item, and wrong here. A furnace
     * sweep drew {@code mekanism:enriching/conversion/stone/to_cracked_bricks} for an
     * electric furnace - a recipe belonging to a module that is not even in the
     * network - and failed a furnace that works. Filtering by the module's declared
     * recipe types is the same rule the network itself uses to decide what a machine
     * may make.
     *
     * <p>An item whose only recipes belong elsewhere yields an empty list, and the
     * caller simply draws again; it never silently falls back to a foreign recipe.
     */
    private static List<ProcessingEntry> registryRecipesFor(ServerLevel level, VeloceProcessingModule module, Item item) {
        List<ProcessingEntry> all = com.craftingveloce.crafting.VeloceRecipeFinder.all(level, item);
        java.util.Set<net.minecraft.world.item.crafting.RecipeType<?>> types = module.recipeTypes();
        if (types.isEmpty()) {
            return all;
        }
        List<ProcessingEntry> owned = new java.util.ArrayList<>();
        for (ProcessingEntry entry : all) {
            if (types.contains(entry.type())) {
                owned.add(entry);
            }
        }
        return owned;
    }

    /** Puts every ingredient of the recipe into the barrel. Returns how many slots were filled. */
    private static int fillBarrel(ServerLevel level, BlockPos barrelPos, ProcessingEntry recipe) {
        if (!(level.getBlockEntity(barrelPos) instanceof BarrelBlockEntity barrel)) {
            return 0;
        }
        int filled = 0;
        for (int slot = 0; slot < recipe.ingredients().size() && slot < barrel.getContainerSize(); slot++) {
            Ingredient ingredient = recipe.ingredients().get(slot);
            if (ingredient.isEmpty() || ingredient.getItems().length == 0) {
                continue;
            }
            // Enough for several crafts: one unit would be consumed by the first
            // attempt and a retry after a hiccup would look like a real failure.
            int perCraft = Math.max(1, recipe.ingredientCount(slot));
            int count = Math.min(ingredient.getItems()[0].getMaxStackSize(), perCraft * 8);
            barrel.setItem(slot, new ItemStack(ingredient.getItems()[0].getItem(), count));
            filled++;
        }
        return filled;
    }
}
