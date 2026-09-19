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
                        // WHICH PAGE the probe will report.
                        //
                        // Without this the terminal opens on the creative inventory's
                        // default tab and the probe measures oak logs - a page that cannot
                        // contain the item under test, so the assertion proves nothing.
                        .then(Commands.literal("search")
                                .then(Commands.argument("phrase",
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .greedyString())
                                        .executes(ctx -> search(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "phrase")))))
                        .then(Commands.literal("result").executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(com.craftingveloce.network.GuiCountsProbePKT.last()),
                                    false);
                            return 1;
                        }))
                        // "why is this item not countable" - the module and machine answers,
                        // so a zero can be attributed instead of guessed at.
                        .then(Commands.literal("why")
                                .then(Commands.argument("item",
                                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> why(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument
                                                        .getId(ctx, "item").toString()))))));
    }

    /**
     * Reports why an item is or is not countable in this rig's network.
     *
     * <p><b>Why this exists.</b> The casing came out as {@code 0} computed in {@code 0 ms} -
     * an answer from the "not in the countable set" branch, not from the planner - and
     * nothing in the log said WHICH of the module requirements failed. The node list, the
     * module's {@code available}/{@code powered} answers and the per-family powered check are
     * all separate questions with the same visible result, and the report needs to know which
     * one it is.
     */
    private static int why(CommandSourceStack source, String itemId) {
        ServerLevel level = source.getLevel();
        if (terminalPos == null) {
            source.sendFailure(Component.literal("§crun /cv guitest setup first"));
            return 0;
        }
        var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        net.minecraft.world.item.Item item = id == null
                ? net.minecraft.world.item.Items.AIR
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);

        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);
        var net = manager.getNetworkForTerminal(level, terminalPos);
        if (net == null) {
            source.sendFailure(Component.literal("§cthe terminal is in no network"));
            return 0;
        }
        java.util.Set<net.minecraft.world.item.Item> countable =
                com.craftingveloce.crafting.VeloceCraftingRegistry.getAllEnabledItems(level, net);
        // ON THE LOG AS WELL. Command feedback goes to the player's chat and never reaches
        // the server log, so a diagnostic that only uses sendSuccess is invisible to anyone
        // reading the run afterwards - which is exactly how the previous attempt at this
        // produced a green test and no information.
        com.craftingveloce.util.VeloceLog.Block.success(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "guitest why %s: inCountable=%s countableSize=%d",
                itemId, countable.contains(item), countable.size());
        source.sendSuccess(() -> Component.literal("§6[guitest] why " + itemId
                + ": inCountable=" + countable.contains(item)
                + " countableSize=" + countable.size()), false);
        // The nodes, so a machine that never joined the network is visible as ABSENT rather
        // than inferred from a number being zero.
        StringBuilder nodes = new StringBuilder();
        for (BlockPos p : net.getTerminals()) {
            nodes.append(level.getBlockState(p).getBlock()).append('@').append(p).append(' ');
        }
        com.craftingveloce.util.VeloceLog.Block.success(
                com.craftingveloce.util.VeloceLog.Side.SERVER, "guitest why nodes: %s", nodes);
        source.sendSuccess(() -> Component.literal("§7nodes: " + nodes), false);
        // Every module's own answer, which is the decision the countable set is built from.
        for (com.craftingveloce.crafting.VeloceProcessingModule module
                : com.craftingveloce.crafting.VeloceProcessingRegistry.all()) {
            boolean avail = module.available(level, net);
            boolean powered = module.powered(level, net);
            int recipes = module.recipesAnywhere(level, item).size();
            com.craftingveloce.util.VeloceLog.Block.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "guitest why module %s available=%s powered=%s recipesAnywhere(%s)=%d",
                    module.id(), avail, powered, itemId, recipes);
            source.sendSuccess(() -> Component.literal("  §7" + module.id()
                    + " available=" + avail + " powered=" + powered
                    + " recipesAnywhere(" + itemId + ")=" + recipes), false);
        }
        // The per-TYPE powered check, which is what a module uses to decide whether to offer
        // a family at all: a Deployer that exists but has no rotation is the classic case.
        for (com.craftingveloce.block.entity.VeloceProcessingSource src
                : com.craftingveloce.crafting.VeloceProcessingSources
                        .allIn(level, net)) {
            com.craftingveloce.util.VeloceLog.Block.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "guitest why machine %s powered=%s types=%s",
                    src.sourceName(), src.isPowered(), src.recipeTypes());
            source.sendSuccess(() -> Component.literal("  §7machine " + src.sourceName()
                    + " powered=" + src.isPowered()
                    + " types=" + src.recipeTypes()), false);
        }
        return 1;
    }

    /** Points the terminal at a page whose contents we know - see the registration note. */
    private static int search(CommandSourceStack source, String phrase) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/cv guitest needs a player"));
            return 0;
        }
        PacketDistributor.sendToPlayer(player,
                new com.craftingveloce.network.SetTerminalSearchPKT(phrase));
        source.sendSuccess(() -> Component.literal("§6[guitest] §7search phrase -> §f" + phrase), false);
        return 1;
    }

    /**
     * Puts Create's creative motor east of a machine, turning it, if Create is installed.
     *
     * <p>Looked up by ID for the isolation reason above. The motor is a CreativeMotor set to
     * the machine's required speed by {@code VeloceRotationSources}, the same tuning the
     * module tests use - the default 16 RPM leaves a kinetic machine spinning AND unpowered
     * at the same time, which is how three rounds were once spent hunting a missing drive
     * that was there all along.
     *
     * @return whether a motor was placed
     */
    private static boolean placeMotorIfPresent(ServerLevel level, BlockPos machinePos) {
        var motorId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "create", "creative_motor");
        net.minecraft.world.level.block.Block motor =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(motorId);
        if (motor == net.minecraft.world.level.block.Blocks.AIR) {
            return false;
        }
        // EAST of the machine, facing WEST: Create answers hasShaftTowards with side ==
        // FACING, so this is what puts the shaft against the machine.
        BlockPos motorPos = machinePos.east();
        level.setBlock(motorPos,
                setByName(motor.defaultBlockState(), "facing", net.minecraft.core.Direction.WEST),
                Block.UPDATE_ALL);
        com.craftingveloce.block.entity.VeloceRotationSources.tune(level, motorPos);
        return true;
    }

    /**
     * Sets a block state property BY NAME.
     *
     * <p>The property instance has to come from the block itself: Create's FACING is its own
     * DirectionProperty and not vanilla's, and setting a property the block does not own
     * throws.
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

    /**
     * Puts Create's andesite alloy in the barrel, if Create is installed.
     *
     * <p>Looked up BY ID and not by a class reference, because this command lives in the core
     * and the core must load with Create absent - the same isolation rule the module gates
     * follow (a foreign type in a signature is a NoClassDefFoundError on load).
     *
     * @return whether the item was found and placed
     */
    private static boolean createAndesiteAlloyIfPresent(BarrelBlockEntity barrel, int slot) {
        var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "create", "andesite_alloy");
        net.minecraft.world.item.Item alloy =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (alloy == net.minecraft.world.item.Items.AIR) {
            return false;   // Create is not installed - the casing simply stays uncraftable
        }
        barrel.setItem(slot, new ItemStack(alloy, 64));
        return true;
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
        // A CRAFTER, and without it this rig cannot answer the question it exists for.
        //
        // Every "+N" number is gated on the item being in the auto-craftable set, and that
        // set comes from the CRAFTERS in the network (VeloceCraftingRegistry). A rig of
        // terminal + pipe + brewing stand therefore has an EMPTY countable set, so every
        // item honestly counts as 0 - and a probe run against it reports "withCounts=0"
        // whether the counting works or not. That is a rig that cannot fail, which is worse
        // than no rig: the first version of this command proved nothing about the numbers.
        BlockPos crafterPos = base.offset(1, 0, -1);
        level.setBlock(crafterPos, VeloceRegistry.VELOCE_CRAFTING_TABLE.get().defaultBlockState(),
                Block.UPDATE_ALL);

        // THE DEPLOYER, and this is the machine every casing comes from.
        //
        // create:andesite_casing is a create:item_application recipe, which the Deployer
        // module owns. Without a Deployer in the network the casing is not in the countable
        // set at all, so the count answers 0 from the "not craftable here" branch - honest,
        // and useless for the report, which is about whether the NUMBER REACHES THE ICON for
        // an item the network really can make.
        //
        // Referenced by ID in OUR namespace (craftingveloce:veloce_create_deployer_module),
        // so the core still loads when Create is absent - the block simply does not exist and
        // the casing stays uncraftable. Same rule the module gates follow.
        // DIRECTLY ABOVE THE PIPE - and the geometry is the whole story here.
        //
        // A machine joins the network only by TOUCHING a pipe. Around the pipe at (+1,0,0) the
        // rig already has: west (+0,0,0) terminal, east (+2,0,0) brewing stand, south (+1,0,+1)
        // barrel, north (+1,0,-1) crafter. The first two attempts put the Deployer at (+0,-1)
        // and then (+2,+1), both DIAGONAL to the pipe, so it never joined the network at all:
        // the node list came back without it, `create available=false`, and the casing was
        // answered 0 in 0 ms from the "not in the countable set" branch rather than by the
        // planner. Measured with `/cv guitest why create:andesite_casing`:
        //
        //   inCountable=false countableSize=2019
        //   module create available=false powered=false recipesAnywhere(...)=2
        //   nodes: brewing_stand@(41,-60,-48) air@(40,-61,-48) crafter@(40,-60,-49)
        //          terminal@(39,-60,-48)
        //
        // UP (+1 in Y) is free and orthogonal, so that is where it goes.
        BlockPos deployerPos = base.offset(1, 1, 0);
        var deployerId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "craftingveloce", "veloce_create_deployer_module");
        net.minecraft.world.level.block.Block deployer =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(deployerId);
        if (deployer != net.minecraft.world.level.block.Blocks.AIR) {
            // Placed already turning about x, the way the kinetic rig does it: our kinetic
            // block takes its axis from a drive that is ALREADY there, so the motor goes down
            // first and the machine last.
            boolean motor = placeMotorIfPresent(level, deployerPos);
            level.setBlock(deployerPos, setByName(deployer.defaultBlockState(),
                    "axis", net.minecraft.core.Direction.Axis.X), Block.UPDATE_ALL);
            placeLikePlayer(level, deployerPos);
            com.craftingveloce.util.VeloceLog.Block.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "guitest: deployer module at %s (motor=%s), state=%s",
                    deployerPos, motor, level.getBlockState(deployerPos));
        } else {
            // Silent absence is what hid this for a whole run: the casing simply stayed at 0
            // and nothing anywhere said the machine was missing.
            com.craftingveloce.util.VeloceLog.Block.failure(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "guitest: %s is not registered - the casing will be uncraftable in this rig",
                    deployerId);
        }

        level.setBlock(base, VeloceRegistry.VELOCE_TERMINAL.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pipePos, VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(machinePos, VeloceRegistry.BREWING_STAND.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);

        // The hooks the game runs on a player's placement. Without them the blocks stand
        // there but are not part of any network.
        placeLikePlayer(level, pipePos);
        placeLikePlayer(level, base);
        placeLikePlayer(level, machinePos);
        placeLikePlayer(level, crafterPos);

        if (level.getBlockEntity(machinePos) instanceof com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity stand) {
            stand.energy = com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity.energyCapacity();
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

        // RAW MATERIALS FOR A CRAFTABLE PAGE, and this is not decoration.
        //
        // A "+N" number only exists when the item is BOTH on screen and craftable from what
        // the network holds. This rig used to carry brewing ingredients only, so a probe run
        // against any page reported "0 with a value, 45 with ZERO" - true, and completely
        // uninformative: it could not distinguish "counting is broken" from "the rig owns
        // nothing to craft with". These four stacks make the vanilla stone page genuinely
        // craftable, so a zero there means the COUNTING failed and nothing else.
        barrel.setItem(3, new ItemStack(Items.ANDESITE, 64));                // andesite -> slabs, stairs, walls
        barrel.setItem(4, new ItemStack(Items.COBBLESTONE, 64));             // + andesite -> polished andesite
        barrel.setItem(5, new ItemStack(Items.OAK_PLANKS, 64));              // planks -> slabs, stairs, fences
        barrel.setItem(6, new ItemStack(Items.OAK_LOG, 64));                 // logs -> planks (a chain)

        // INGREDIENTS FOR create:andesite_casing - the item this whole investigation is about.
        //
        // Its recipe (create:item_application/andesite_casing_from_log) is a stripped log plus
        // andesite alloy, so without these two the casing is computed and correctly answered
        // ZERO - which is a valid answer that proves nothing about whether the "+N" reaches
        // the icon. With them the same item must come out NON-ZERO, and that is the assertion
        // the original report needs.
        barrel.setItem(7, new ItemStack(Items.STRIPPED_OAK_LOG, 64));
        createAndesiteAlloyIfPresent(barrel, 8);
        // andesite alloy -> casing also needs a Deployer, which this rig does not place: the
        // assertion is about the COUNTING reaching the icon, and a zero here would be honest.
        // The Create module is present in the dev run, so we give the alloy when we can.

        // ONE INGREDIENT GOES INTO THE PLAYER'S POCKETS AND NOWHERE ELSE.
        //
        // The count must treat what the player carries as a source, and no test covered that:
        // every rig put its materials in the network, so a count that ignored the inventory
        // entirely would still have passed. Diortite is deliberately NOT in the barrel - the
        // only place it exists is the backpack, so a non-zero count for diorite items can
        // only come from reading the player.
        player.getInventory().setItem(0, new ItemStack(Items.DIORITE, 64));

        // FINISHED ITEMS IN THE POCKETS, WHICH MUST NOT BE REPORTED AS CRAFTABLE.
        //
        // The report: "with 10 doors in my inventory it shows I can craft +10 doors, even
        // though I cannot". A plan that consumes the stock it finds will happily "satisfy" a
        // request for 10 doors from those 10 doors without crafting anything, and the number
        // then reads as though the network made them.
        //
        // Wooden doors are deliberately an item this rig CANNOT produce: the barrel has no
        // planks-to-door path enabled here and no crafting table recipe for them, so the only
        // way they could appear craftable is by reading the player's stack. Whatever number
        // the counter reports for `oak_door` must therefore come from crafting, not from the
        // pocket.
        player.getInventory().setItem(1, new ItemStack(Items.OAK_DOOR, 10));

        terminalPos = base;
        com.craftingveloce.network.GuiCountsProbePKT.reset();
        source.sendSuccess(() -> Component.literal("§6[guitest] §7rig at §f" + base
                + "§7, terminal §f" + base + "§7, barrel: brewing + andesite/cobblestone/oak "
                + "so the stone and wood pages are genuinely craftable"), false);
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
