package com.craftingveloce.commands;

import com.craftingveloce.init.VeloceRegistry;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives the TERMINAL GUI from a test script: build a network, open the screen for the
 * player, and report what that screen is showing.
 *
 * <p><b>Why a separate command.</b> {@code /cv testmodule} proves the network can make a
 * potion - it asks the terminal's crafting path directly, on the server. That says nothing
 * about the GUI: whether the potion is in the visible grid at all, and whether the number
 * the server computed finds the item it belongs to on screen. Both of those are client
 * questions, and both fail silently - an absent item and an unmatched number look exactly
 * like a network with nothing to offer.
 *
 * <p><b>No walking.</b> The screen is opened by sending the same packet the block sends
 * when a player right-clicks it, so the whole test is commands and ticks.
 */
public final class CVGuiTestCommand {


    private static BlockPos terminalPos;

    private CVGuiTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("guitest")
                        .then(Commands.literal("setup").executes(ctx -> setup(ctx.getSource())))
                        .then(Commands.literal("open").executes(ctx -> open(ctx.getSource())))
                        .then(Commands.literal("result").executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(com.craftingveloce.network.GuiCountsProbePKT.last()),
                                    false);
                            return 1;
                        }))));
    }

    /**
     * Builds terminal | pipe | brewing stand, plus a barrel holding the ingredients for
     * Night Vision - and a REAL water bottle, not a proxy.
     *
     * <p>The real bottle matters. The module tests put proxies straight into the barrel,
     * which is the network's own currency; a player has bottles and wart. If the two
     * behave differently, this is the rig that shows it.
     */
    private static int setup(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/cv guitest needs a player"));
            return 0;
        }

        BlockPos base = player.blockPosition().offset(2, 0, 0);
        clearArea(level, base);

        BlockPos pipePos = base.offset(1, 0, 0);
        BlockPos machinePos = base.offset(2, 0, 0);
        BlockPos barrelPos = base.offset(1, 0, 1);

        level.setBlock(base, VeloceRegistry.VELOCE_TOM_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pipePos, VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(machinePos, VeloceRegistry.BREWING_STAND.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        // The hooks the game runs on a player's placement. Without them the blocks stand
        // there but are not part of any network.
        placeLikePlayer(level, pipePos);
        placeLikePlayer(level, base);
        placeLikePlayer(level, machinePos);

        if (level.getBlockEntity(machinePos) instanceof com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity stand) {
            stand.energy = com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity.ENERGY_CAPACITY;
        } else {
            source.sendFailure(Component.literal("§cno brewing stand block entity at " + machinePos));
            return 0;
        }

        if (!(level.getBlockEntity(barrelPos) instanceof BarrelBlockEntity barrel)) {
            source.sendFailure(Component.literal("§cno barrel at " + barrelPos));
            return 0;
        }
        // Potions.WATER is already a Holder - wrapping it again is a compile error, and
        // the barrel wants a REAL bottle, which is the point of this rig.
        ItemStack waterBottle = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        barrel.setItem(0, waterBottle);                                     // water -> awkward
        barrel.setItem(1, new ItemStack(Items.NETHER_WART, 32));             // awkward -> ...
        barrel.setItem(2, new ItemStack(Items.GOLDEN_CARROT, 32));           // ... -> night vision

        terminalPos = base;
        com.craftingveloce.network.GuiCountsProbePKT.reset();
        source.sendSuccess(() -> Component.literal("§6[guitest] §7rig at §f" + base
                + "§7, terminal §f" + base + "§7, barrel: water bottle + nether wart + golden carrot"), false);
        return 1;
    }

    /** Opens the terminal screen the way the block does - by packet, without walking. */
    private static int open(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/cv guitest needs a player"));
            return 0;
        }
        if (terminalPos == null) {
            source.sendFailure(Component.literal("§crun /cv guitest setup first"));
            return 0;
        }
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce] guitest open: sending OpenTerminalScreenPKT to {} at {}",
                player.getGameProfile().getName(), terminalPos);
        PacketDistributor.sendToPlayer(player,
                new com.craftingveloce.network.OpenTerminalScreenPKT(terminalPos));
        source.sendSuccess(() -> Component.literal("§6[guitest] §7terminal screen opened at §f" + terminalPos), false);
        return 1;
    }

    private static void placeLikePlayer(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    /** Clears our blocks and barrels in the build area, so a leftover rig cannot answer. */
    private static void clearArea(ServerLevel level, BlockPos base) {
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-4, -1, -3), base.offset(4, 1, 3))) {
            BlockState state = level.getBlockState(p);
            var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            boolean ours = id != null && "craftingveloce".equals(id.getNamespace());
            if (ours || state.is(Blocks.BARREL)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
