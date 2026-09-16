package com.craftingveloce.commands;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.VeloceCaseBuildable;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /cv showcase} - stawia WSZYSTKIE nasze bloki obok siebie, do testow.
 *
 * <p><b>Po co.</b> Zeby obejrzec render obudow (mlyn, pila, kruszarka, moduly
 * Mekanism/Alchemistry, waniliowe klocki, puste Integrale i rure) trzeba by je
 * wszystkie postawic recznie - kilkadziesiat blokow i pare minut klikania.
 * Ta komenda stawia je w siatce przed graczem, kazdy z wlasciwym stanem
 * (os napedu, blachy od rury), a maszyny budowane z elementow od razu
 * wypelnia elementami, zeby bylo widac ich pelny wyglad.

 * <p>Uzycie:
 * <ul>
 *   <li>{@code /cv showcase} - same bloki,</li>
 *   <li>{@code /cv showcase pipes} - dodatkowo rura Veloce przy kazdym bloku
 *       (widac zamykanie boku obudowy blacha),</li>
 *   <li>{@code /cv showcase clear} - sprzata to, co postawila ta komenda.</li>
 * </ul>
 *
 * <p><b>Lista blokow pochodzi z REJESTRU</b> ({@code craftingveloce:*}), a nie
 * z recznie pisanej listy - inaczej nowy blok nigdy nie trafialby na wystawe
 * (dokladnie ten rodzaj rozjazdu dwoch spisow, ktory w tym projekcie wracal).
 */
public final class CVShowcaseCommand {

    /** Ile blokow w rzedzie. */
    private static final int COLUMNS = 6;

    /** Odstep miedzy blokami (jeden klocek powietrza, zeby nic sie nie stykalo). */
    private static final int SPACING = 2;

    /** Ile elementow wypelnic w maszynach budowanych (kola: 2, crafter: 9 = 3x3). */
    private static final int FILL_PARTS = 9;

    private CVShowcaseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("showcase")
                        .executes(context -> place(context, false))
                        .then(Commands.literal("pipes").executes(context -> place(context, true)))
                        .then(Commands.literal("clear").executes(CVShowcaseCommand::clear))));
    }

    /** Stawia wszystkie nasze bloki w siatce przed graczem. */
    private static int place(CommandContext<CommandSourceStack> context, boolean withPipes) {
        ServerPlayer player = context.getSource().getPlayer();
        ServerLevel level = context.getSource().getLevel();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/cv showcase tylko dla gracza"));
            return 0;
        }
        List<Block> blocks = ourBlocks();
        int placed = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = slot(player, i);
            if (withPipes) {
                // Rura PRZED maszyna: postawienie maszyny powiadamia sasiada,
                // wiec rura sama przeliczy sobie ksztalt i polaczenia.
                level.setBlock(pos.north(), VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(),
                        Block.UPDATE_ALL);
            }
            BlockState state = placementState(player, level, pos, blocks.get(i));
            level.setBlock(pos, state, Block.UPDATE_ALL);
            fillParts(level, pos);
            placed++;
        }
        int columns = Math.min(COLUMNS, Math.max(1, blocks.size()));
        int total = placed;
        context.getSource().sendSuccess(() -> Component.literal(
                "Veloce showcase: " + total + " blokow (" + columns + " w rzedzie, odstep "
                        + SPACING + ")" + (withPipes ? " + rury" : "")), true);
        CraftingVeloceMod.LOGGER.info("[Veloce][SHOWCASE] postawiono {} blokow przy {}",
                placed, player.blockPosition());
        return placed;
    }

    /** Sprzata siatke postawiona przez showcase (tylko nasze bloki i rury). */
    private static int clear(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        ServerLevel level = context.getSource().getLevel();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/cv showcase tylko dla gracza"));
            return 0;
        }
        int removed = 0;
        for (int i = 0; i < ourBlocks().size(); i++) {
            BlockPos pos = slot(player, i);
            if (isOurs(level.getBlockState(pos))) {
                level.removeBlock(pos, false);
                removed++;
            }
            if (isOurs(level.getBlockState(pos.north()))) {
                level.removeBlock(pos.north(), false);
                removed++;
            }
        }
        int total = removed;
        context.getSource().sendSuccess(() -> Component.literal(
                "Veloce showcase: usunieto " + total + " blokow"), true);
        return removed;
    }

    /** Stan bloku z jego wlasna logika postawienia (os napedu, blachy, polaczenia rury). */
    private static BlockState placementState(ServerPlayer player, ServerLevel level, BlockPos pos,
                                             Block block) {
        BlockPlaceContext placeContext = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                ItemStack.EMPTY, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        BlockState state = block.getStateForPlacement(placeContext);
        return state == null ? block.defaultBlockState() : state;
    }

    /** Wypelnia maszyny budowane elementami, zeby bylo widac ich pelny wyglad. */
    private static void fillParts(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof VeloceCaseBuildable buildable)) {
            return;
        }
        for (int i = 0; i < FILL_PARTS; i++) {
            if (!buildable.addPart()) {
                break;   // maszyna pelna (kola: 2) albo nie przyjmuje elementow
            }
        }
    }

    /** Miejsce i-tego bloku w siatce przed graczem (wiersze ida od gracza). */
    private static BlockPos slot(ServerPlayer player, int index) {
        Direction facing = player.getDirection();
        Direction right = facing.getClockWise();
        int row = index / COLUMNS;
        int column = index % COLUMNS;
        return player.blockPosition()
                .relative(facing, 3 + row * SPACING)
                .relative(right, column * SPACING);
    }

    private static boolean isOurs(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && CraftingVeloceMod.MODID.equals(id.getNamespace());
    }

    /** Wszystkie nasze bloki z rejestru, w stabilnej kolejnosci. */
    private static List<Block> ourBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            if (CraftingVeloceMod.MODID.equals(id.getNamespace())) {
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    blocks.add(block);
                }
            }
        }
        blocks.sort(Comparator.comparing(block -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id == null ? "" : id.getPath();
        }));
        return blocks;
    }
}
