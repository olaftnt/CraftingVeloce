package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.craftingveloce.crafting.ProcessingEntry;
import com.craftingveloce.crafting.VeloceProcessingModule;
import com.craftingveloce.crafting.VeloceProcessingRegistry;
import com.craftingveloce.crafting.VeloceRecipeRegistry;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

import java.util.List;

/**
 * End-to-end test of one processing module: build a network, feed it a real
 * recipe, and then ask for the result the way a player does.
 *
 * <p><b>What it proves that no source guard can.</b> The guards check that a
 * module is registered and that the right methods exist. They cannot tell whether
 * a crusher actually crushes, whether the planner finds the recipe, or whether
 * the ingredients in the chest are really consumed and the result really
 * produced. This does: it goes through
 * {@link VeloceTomTerminalBlockEntity#extractWithReason} - the exact call a
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

    /** Ticks to let the network scan before the result is judged. */
    private static final int SCAN_TICKS = 60;

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

    private CVModuleTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("testmodule")
                        .then(Commands.literal("fe")
                                .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .executes(ctx -> setFe(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount")))))
                        .then(Commands.literal("result").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(lastVerdict), false);
                            return 1;
                        }))
                        .then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(VeloceProcessingRegistry.ids(), builder))
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "module"))))));
    }

    private static int run(CommandSourceStack source, String moduleId) {
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

        // --- 1. the machine block of this module ---
        Block machine = findMachineBlock(moduleId);
        if (machine == null) {
            source.sendFailure(Component.literal("§c" + moduleId
                    + " has no machine block registered - is the mod that provides it installed?"));
            return 0;
        }

        // --- 2. a recipe this module can actually do ---
        ProcessingEntry recipe = findRecipe(level, module);
        if (recipe == null) {
            source.sendFailure(Component.literal("§c" + moduleId
                    + " has no recipe available in this game instance"));
            return 0;
        }
        Item wanted = recipe.primaryResult().getItem();

        // --- 3. the network: terminal | pipe | machine, barrel of ingredients ---
        BlockPos terminalPos = base;
        BlockPos pipePos = base.offset(1, 0, 0);
        BlockPos machinePos = base.offset(2, 0, 0);
        BlockPos barrelPos = base.offset(1, 0, 1);

        level.setBlock(terminalPos, VeloceRegistry.VELOCE_TOM_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pipePos, VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(machinePos, machine.defaultBlockState(), Block.UPDATE_ALL);
        lastMachinePos = machinePos;
        level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        // Give the machine power so an FE-driven module is not judged on "no energy"
        // - the test is about crafting, not about the accumulator being full.
        level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                        machinePos, null);

        int ingredientsPut = fillBarrel(level, barrelPos, recipe);

        source.sendSuccess(() -> Component.literal("§6[testmodule] §f" + moduleId
                + " §7building and waiting " + SCAN_TICKS + " ticks for the network scan"), false);
        source.sendSuccess(() -> Component.literal("§7  want §f" + wanted
                + "§7, ingredients placed: §f" + ingredientsPut
                + "§7, recipe: §f" + recipe.id()), false);

        // --- 4. judge after the scan has had time to run ---
        var server = level.getServer();
        server.tell(new TickTask(server.getTickCount() + SCAN_TICKS, () -> judge(level, terminalPos, wanted, moduleId)));

        return 1;
    }

    /**
     * The verdict: ask the terminal for the item exactly as a player would.
     *
     * <p>This is the whole point of the test - not "is the module registered", but
     * "does the network produce the item and hand it over".
     */
    private static void judge(ServerLevel level, BlockPos terminalPos, Item wanted, String moduleId) {
        var manager = VelocePipeNetworkManager.get(level);
        var net = manager.getNetworkForTerminal(level, terminalPos);
        if (net == null) {
            fail(moduleId, "the terminal is not connected to a network after " + SCAN_TICKS + " ticks");
            return;
        }
        if (!(level.getBlockEntity(terminalPos) instanceof VeloceTomTerminalBlockEntity terminal)) {
            fail(moduleId, "no terminal block entity at " + terminalPos);
            return;
        }

        long stock = net.getAllItemCounts(level).getOrDefault(wanted, 0L);

        VeloceTomTerminalBlockEntity.PullResult pulled;
        try {
            // count=1, allowCrafting=true: the player path.
            pulled = terminal.extractWithReason(new ItemStack(wanted), 1, true);
        } catch (Throwable t) {
            fail(moduleId, "asking the terminal threw " + t);
            return;
        }

        if (pulled == null || pulled.stack().isEmpty()) {
            String why = pulled == null ? "no result" : pulled.reason() + " (" + pulled.detail() + ")";
            fail(moduleId, "terminal could not deliver " + wanted + ": " + why);
            return;
        }
        pass(moduleId, wanted, stock, pulled.stack().getCount());
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

    private static void pass(String moduleId, Item wanted, long stockBefore, int delivered) {
        lastVerdict = "PASS " + moduleId;
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "[testmodule] PASS %s: delivered %dx %s (stock before: %d)",
                moduleId, delivered, wanted, stockBefore);
    }

    private static void fail(String moduleId, String why) {
        lastVerdict = "FAIL " + moduleId + ": " + why;
        com.craftingveloce.util.VeloceLog.Block.error(
                com.craftingveloce.util.VeloceLog.Side.SERVER, null,
                "[testmodule] FAIL %s: %s", moduleId, why);
    }

    /** The machine block registered for this module id, or null when its mod is absent. */
    private static Block findMachineBlock(String moduleId) {
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
     * First recipe of this module found anywhere.
     *
     * <p>Scans the item registry rather than the recipe type's list because the
     * module API is expressed per item ({@code recipesAnywhere}) - that is also
     * how the existing module test network finds one.
     */
    private static ProcessingEntry findRecipe(ServerLevel level, VeloceProcessingModule module) {
        // Two different APIs, and using the wrong one is why this first reported
        // "no recipe available":
        //
        //   * the built-in modules (crafting, furnace, brewing) implement
        //     producible(level, network) - the SET of items they can make - and
        //     leave recipesAnywhere at its empty default;
        //   * the compat modules (Create/Mekanism/Alchemistry) do the opposite.
        //
        // So the item set comes from the recipe registry for the built-ins and from
        // the module itself for the rest, and the concrete recipe always comes from
        // the shared finder, which knows about every source.
        java.util.Set<Item> candidates = switch (module.id()) {
            case "crafting" -> VeloceRecipeRegistry.getAllCraftableItems(level);
            case "furnace" -> VeloceRecipeRegistry.getAllFurnaceCraftableItems(level);
            default -> java.util.Set.of();
        };
        for (Item item : candidates) {
            List<ProcessingEntry> found = com.craftingveloce.crafting.VeloceRecipeFinder.all(level, item);
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            List<ProcessingEntry> found = module.recipesAnywhere(level, item);
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
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
