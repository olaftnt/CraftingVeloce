package com.craftingveloce;


import com.craftingveloce.commands.CVDebugCommand;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.VelocePacketHandler;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CraftingVeloceMod.MODID)
public class CraftingVeloceMod {
    public static final String MODID = "craftingveloce";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register(
            "craftingveloce_tab",
            () -> new CreativeModeTab.Builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.literal("Crafting Veloce"))
                    .icon(() -> new ItemStack(VeloceRegistry.VELOCE_TERMINAL_ITEM.get()))
                    .displayItems((params, output) -> {
                        // THE ORDER IS THE ORDER THINGS ARE USED IN, at the player's request:
                        // the terminal is what a network is built around, the pipe joins the
                        // blocks together, the wrench configures those connections, and the
                        // frame is what every single machine is made from. Only then come the
                        // machines - first the ones built from vanilla blocks, then one group
                        // per integration, in the order a player meets them.
                        output.accept(VeloceRegistry.VELOCE_TERMINAL_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_PIPE_ITEM.get());
                        // The wrench was registered but MISSING from this tab - the same
                        // "registered, but unobtainable except by command" bug the furnaces
                        // and the brewing stand had before it: registration and the tab are
                        // two separate places, and nothing in the build compares them.
                        output.accept(VeloceRegistry.VELOCE_WRENCH.get());
                        // The frame EVERY machine is now made from. Without it in the
                        // tab there is no way to start a network and no way to test.
                        output.accept(VeloceRegistry.VELOCE_INTEGRALE_ITEM.get());

                        // --- the machines made from vanilla blocks ---
                        output.accept(VeloceRegistry.VELOCE_EXTRACTOR_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_CRAFTING_TABLE_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_CONTROLLER_ITEM.get());
                        // BUG this fixes: the furnaces were registered, but they
                        // were NOT in the tab - so there was no way to obtain them
                        // other than a command. A new block is easy to get wrong
                        // this way, because registration and the tab are two
                        // separate places.
                        output.accept(VeloceRegistry.VELOCITY_FURNACE_ITEM.get());
                        output.accept(VeloceRegistry.ELECTRIC_FURNACE_ITEM.get());
                        // The brewing stand was missing here - the same bug the comment
                        // above describes for the furnaces: registered, but unobtainable
                        // except by command.
                        output.accept(VeloceRegistry.BREWING_STAND_ITEM.get());
                        output.accept(VeloceRegistry.THRESHOLD_SENSOR_ITEM.get());

                        // --- one group per integration ---
                        //
                        // Module blocks of the integrations that are installed.
                        //
                        // BUG this fixes: every compat module had its own
                        // addCreativeItems and the tab called NONE of them, so a
                        // Mekanism, Alchemistry or Create module could not be taken in
                        // creative at all. Registration and the tab are two separate
                        // places - the same way the furnaces and the brewing stand were
                        // once registered and missing from here.
                        //
                        // Guarded exactly like the registration above: reaching into a
                        // compat class loads it, so it may only load when its mod is
                        // there.
                        if (com.craftingveloce.compat.mekanism.MekanismCompat.isPresent()) {
                            com.craftingveloce.compat.mekanism.MekanismCompat.addCreativeItems(output);
                        }
                        if (com.craftingveloce.compat.create.CreateCompat.isPresent()) {
                            com.craftingveloce.compat.create.CreateCompat.addCreativeItems(output);
                        }
                        if (com.craftingveloce.compat.alchemistry.AlchemistryCompat.isPresent()) {
                            com.craftingveloce.compat.alchemistry.AlchemistryCompat.addCreativeItems(output);
                        }
                    })
                    .build()
    );

    public CraftingVeloceMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("CraftingVeloce initializing...");

        // Config registration (config/craftingveloce-common.toml).
        // NOTE: config values can only be read AFTER the config has been loaded.
        // Reading in the constructor throws "Cannot get config value before config
        // is loaded" and takes down the whole mod - so we log only in the
        // ModConfigEvent.Loading event, which fires after loading.
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                com.craftingveloce.config.VeloceConfig.SPEC);

        modEventBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Loading.class,
                event -> {
                    if (event.getConfig().getSpec()
                            == com.craftingveloce.config.VeloceConfig.SPEC) {
                        LOGGER.info("[Veloce][INIT] debug logging: {} (level={})",
                                com.craftingveloce.config.VeloceConfig.DEBUG_ENABLED.get(),
                                com.craftingveloce.config.VeloceConfig.DEBUG_LEVEL.get());
                    }
                });

        VeloceRegistry.register(modEventBus);
        com.craftingveloce.crafting.VeloceRecipes.register(modEventBus);
        // JEI: our blocks next to the crafting table (details - VeloceJeiCatalysts).
        com.craftingveloce.compat.VeloceJeiCatalysts.registerDefaults();
        CREATIVE_TABS.register(modEventBus);
        VelocePacketHandler.register(modEventBus);

        // --- Optional integrations with other mods ------------------------
        //
        // EVERY gate is checked BEFORE the first reference to a class with a
        // foreign type. Order matters: NoClassDefFoundError is thrown when a
        // class is loaded and linked, so no try/catch would catch it - without
        // the check the mod simply does not start without that other mod.
        //
        // Gates (XCompat) have no foreign types in their fields or signatures;
        // foreign types appear only in the bodies of methods called conditionally.
        for (com.craftingveloce.compat.VeloceMods mod : com.craftingveloce.compat.VeloceMods.values()) {
            LOGGER.info("[Veloce][COMPAT] {}: {}", mod.id(),
                    mod.isLoaded() ? "present" : "missing");
        }
        if (com.craftingveloce.compat.create.CreateCompat.isPresent()) {
            com.craftingveloce.compat.create.CreateCompat.register(modEventBus);
        }
        if (com.craftingveloce.compat.alchemistry.AlchemistryCompat.isPresent()) {
            com.craftingveloce.compat.alchemistry.AlchemistryCompat.register(modEventBus);
        }
        if (com.craftingveloce.compat.mekanism.MekanismCompat.isPresent()) {
            com.craftingveloce.compat.mekanism.MekanismCompat.register(modEventBus);
        }

        // Renderer for the CONTENTS of the Veloce Integrale casing: each of our
        // blocks has a casing model (frame + glass), and inside it the base
        // block model is rendered (crafting table, dispenser, observer,
        // crafting table, furnace). The contents follow from the block TYPE, so
        // the server neither saves nor synchronises anything.
        modEventBus.addListener(
                net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
                event -> {
                    event.registerBlockEntityRenderer(VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(VeloceRegistry.VELOCE_CONTROLLER_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(VeloceRegistry.VELOCE_EXTRACTOR_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(VeloceRegistry.THRESHOLD_SENSOR_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(VeloceRegistry.VELOCITY_FURNACE_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(VeloceRegistry.ELECTRIC_FURNACE_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(VeloceRegistry.BREWING_STAND_BE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                });

        modEventBus.addListener(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent.class, event -> {
            event.register(VeloceRegistry.VELOCE_EXTRACTOR_MENU.get(), com.craftingveloce.client.gui.VeloceExtractorScreen::new);
            event.register(VeloceRegistry.VELOCE_KINETIC_MENU.get(),
                    com.craftingveloce.client.gui.VeloceKineticScreen::new);
            event.register(VeloceRegistry.VELOCE_MODULE_MENU.get(),
                    com.craftingveloce.client.gui.VeloceModuleScreen::new);
            event.register(VeloceRegistry.VELOCITY_FURNACE_MENU.get(),
                    com.craftingveloce.client.gui.VeloceVelocityFurnaceScreen::new);
            event.register(VeloceRegistry.BREWING_STAND_MENU.get(),
                    com.craftingveloce.client.gui.VeloceBrewingStandScreen::new);
            event.register(VeloceRegistry.ELECTRIC_FURNACE_MENU.get(),
                    com.craftingveloce.client.gui.VeloceElectricFurnaceScreen::new);
            event.register(VeloceRegistry.THRESHOLD_SENSOR_MENU.get(),
                    com.craftingveloce.client.gui.VeloceThresholdSensorScreen::new);
        });

        // The generated potion proxies have no model file - there is one file per item
        // and they are made at runtime - so they borrow the hand-authored proxy's baked
        // model, which is itself just `minecraft:item/potion`. One lookup, N entries.
        modEventBus.addListener(net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult.class, event -> {
            java.util.List<net.minecraft.world.item.Item> generated =
                    com.craftingveloce.init.VelocePotionProxies.created();
            if (generated.isEmpty()) {
                return;
            }
            net.minecraft.client.resources.model.ModelResourceLocation templateKey =
                    net.minecraft.client.resources.model.ModelResourceLocation.inventory(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                                    VeloceRegistry.POTION_WATER.get()));
            var template = event.getModels().get(templateKey);
            if (template == null) {
                LOGGER.warn("[Veloce] no model to copy for the {} generated potion proxies - "
                        + "they will render as the missing-texture block", generated.size());
                return;
            }
            for (net.minecraft.world.item.Item proxy : generated) {
                event.getModels().put(
                        net.minecraft.client.resources.model.ModelResourceLocation.inventory(
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(proxy)),
                        template);
            }
            LOGGER.info("[Veloce] baked {} generated potion proxy models", generated.size());
        });

        // Leaving the world clears the remembered terminal views.
        // Block positions make no sense in another world, and in a new one they
        // could accidentally point at a different terminal.
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class,
                event -> com.craftingveloce.client.ClientTerminalHelper.clearSavedTerminalViews());

        // NOTE: block transparency was DELIBERATELY DISABLED.
        // Both "render_type" was removed from the models in
        // assets/.../models/block/, and the render layer registration below.
        // All Veloce blocks now render as solid (opaque).
        //
        // If you ever want to go back to transparency, you need BOTH:
        //   1. "render_type": "minecraft:translucent" in every block model
        //   2. the registration below (ItemBlockRenderTypes.setRenderLayer)
        // render_type in the JSON alone is enough for most cases, but
        // registering in code guarantees a correct chunk render type set.

        modEventBus.addListener(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class, event -> {
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    VeloceRegistry.VELOCE_EXTRACTOR_BE.get(),
                    (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be.getOutputInventory())
            );
            // Auto-crafter buffer - thanks to this, surplus production is
            // normally visible to the network (pipes, terminal, extractor).
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(),
                    (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be.getBuffer())
            );
            // Velocity Electric Furnace accepts Forge Energy from cables.
            // The accumulator is INTERNAL, so extractEnergy returns 0 - a cable
            // cannot "suck" the furnace dry.
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                    VeloceRegistry.ELECTRIC_FURNACE_BE.get(),
                    (be, side) -> be
            );
            // Brewing Stand - Forge Energy
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                    VeloceRegistry.BREWING_STAND_BE.get(),
                    (be, side) -> be
            );

        });

        // The "not enough rotation speed" caption at the crosshair WAS REMOVED.
        //
        // Player: "get rid of that thing, do a Jade integration instead" - the
        // same information (speed, required speed, SU draw, energy and status)
        // is now shown by the Jade tooltip (compat/jade) and the right-click
        // window. Both places take their text from a single source
        // (VeloceModuleInfoLines), so there is no custom on-screen drawing
        // anymore.
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            // Automated runs must not be paused: a paused client stops ticking its
            // integrated server, and every `wait:` in a script would then measure a world
            // that is standing still. Installs nothing unless a script is configured.
            com.craftingveloce.client.VeloceTestPauseGuard.install();
        }

        // Automatic test run: with -Dveloce.test.script=<path> the script runs on
        // the first player join and the game exits with its result.
        com.craftingveloce.test.VeloceTestDriver.installAutoRun();

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            CVDebugCommand.register(event.getDispatcher());
            // /cv showcase: places all our blocks for render testing.
            com.craftingveloce.commands.CVShowcaseCommand.register(event.getDispatcher());
            com.craftingveloce.commands.CVTestNetworkCommand.register(event.getDispatcher());
            com.craftingveloce.commands.CVTraceCommand.register(event.getDispatcher());
            // getitems needs a build context - ItemArgument suggests item
            // identifiers, so the player does not have to know them by heart.
            com.craftingveloce.commands.CVGetItemsCommand.register(
                    event.getDispatcher(), event.getBuildContext());
            // /cv test list | run <script>: drives the in-game test scripts.
            com.craftingveloce.test.VeloceTestDriver.register(event.getDispatcher());
            // /cv testmodule <id>: end-to-end test of one processing module.
            com.craftingveloce.commands.CVModuleTestCommand.register(event.getDispatcher());
            // /cv kinetic place|read: builds a module against Create's creative motor and
            // reads back the speed and the stress impact it applies. The only way to tell
            // "the machine took power from the network" apart from "the machine is
            // standing next to one", which is the state a player reported.
            com.craftingveloce.commands.CVKineticTestCommand.register(event.getDispatcher());
            // The terminal GUI in combination with a brewing stand - see CVGuiTestCommand
            // for why the server cannot answer this on its own.
            com.craftingveloce.commands.CVGuiTestCommand.register(event.getDispatcher());
        });

        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class, event -> {
            ItemStack stack = event.getItemStack();
            if (!com.craftingveloce.item.VeloceWrenchItem.isWrench(stack)) {
                return;
            }
            net.minecraft.world.level.Level level = event.getLevel();
            net.minecraft.core.BlockPos pos = event.getPos();
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof com.craftingveloce.block.VelocePipeBlock pipe)) {
                return;
            }
            net.minecraft.world.entity.player.Player player = event.getEntity();
            if (player == null) return;

            net.minecraft.world.ItemInteractionResult result = pipe.onWrenchClicked(
                    state, level, pos, player, event.getHand(), event.getHitVec()
            );
            if (result.result().consumesAction()) {
                event.setUseItem(net.neoforged.neoforge.common.util.TriState.TRUE);
                event.setCancellationResult(result.result());
                event.setCanceled(true);
            }
        });

        // --- Reaction to changes in the world ----------------------------
        // Chunks with network blocks can load/unload at any moment (the player
        // walks up, walks away, the chunk gets pulled in by another mod). The
        // network stock then changes abruptly, so the craftability cache has to
        // know about it - otherwise the GUI shows stale numbers.
        // Extra safeguard: release force-loads when a dimension unloads
        // (world change, return to the main menu).
        // Deferred pipe network rebuilds. Without this, every neighborChanged
        // (and there are many when a machine works next door) did a full BFS of
        // the network immediately - several times per tick.
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, event -> {
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                        // Each of these three operations reaches into foreign mod
                        // code (Tom's block entity, Refined Storage, containers),
                        // and it runs from the LEVEL tick. Without a guard, one
                        // exception from a foreign library ends in an
                        // "Exception ticking world" crash, without any hint that
                        // it was our doing. The guard logs the full stack trace
                        // (always, regardless of config) and only suppresses the
                        // repeating of the same error.
                        long now = sl.getGameTime();
                        com.craftingveloce.util.VeloceGuard.run("pipe network step", now,
                                () -> com.craftingveloce.network.pipe.VelocePipeNetworkManager
                                        .get(sl).tick(sl));
                        // Crafter states are broadcast once per tick, not once
                        // per every item toggle.
                        com.craftingveloce.util.VeloceGuard.run("crafter state broadcast", now,
                                () -> com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity
                                        .flushPendingSyncs(sl));
                        // Network force-load upkeep. The driver is the LEVEL tick,
                        // not the terminal - otherwise a network without a
                        // terminal would not keep its chunks and automation would
                        // break when the player walks away (see
                        // VeloceCraftingCache.tickAll).
                        com.craftingveloce.util.VeloceGuard.run("force-load upkeep", now,
                                () -> com.craftingveloce.crafting.VeloceCraftingCache.tickAll(sl));
                    }
                });

        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.level.LevelEvent.Load.class, event -> {
                    // Returning to the world after leaving to the menu. releaseAll()
                    // sets the "server is shutting down" flag and someone has to
                    // clear it - otherwise chunk force-loading stays disabled for
                    // the rest of the session.
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                        com.craftingveloce.crafting.VeloceCraftingCache.onLevelLoaded();
                        // Cleanup of orphaned force-loads left by an older
                        // version of the code. Minecraft saves setChunkForced
                        // PERSISTENTLY, so without this the chunks stayed loaded
                        // forever, even though our report showed zero.
                        int orphans = com.craftingveloce.network.pipe.VelocePipeNetworkManager
                                .get(sl).releaseOrphanForceLoads(sl);
                        if (orphans > 0) {
                            com.craftingveloce.util.VeloceLog.Network.success(
                                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                                    "removed %d orphaned force-loads on world entry",
                                    orphans);
                        }
                    }
                });

        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.level.LevelEvent.Unload.class, event -> {
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                        com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl)
                                .clearPendingRebuilds();
                        // Unloading a dimension != shutting down the server. We use
                        // the lighter path so we do not disable force-loading for
                        // the remaining worlds permanently.
                        com.craftingveloce.crafting.VeloceCraftingCache.onLevelUnloaded(sl);
                    }
                });

        // We release the chunk force-loads BEFORE the world is saved.
        // Without this, Minecraft tries to unload the chunks we are keeping,
        // over and over - and the save hangs.
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.server.ServerStoppingEvent.class, event -> {
                    var server = event.getServer();
                    for (var level : server.getAllLevels()) {
                        com.craftingveloce.crafting.VeloceCraftingCache.releaseAll(level);
                    }
                });

        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl)
                        .onChunkChanged(sl, event.getChunk().getPos(), true);
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                var chunkPos = event.getChunk().getPos();
                // A message about an ACTUAL chunk unload - hooked exactly into
                // this event, and not guessed from the player's distance.
                //
                // Why: without this you cannot tell whether the area under test
                // unloads at all. The area may be kept by another mod, by
                // spawn-chunks or by our own force-loads - and then the "test"
                // proves nothing, because the chunk never falls out.
                com.craftingveloce.debug.ChunkDebugNotifier.notifyUnload(
                        sl, chunkPos, com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl));
                com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl)
                        .onChunkChanged(sl, chunkPos, false);
            }
        });

        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent.class, event -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                net.minecraft.core.BlockPos pos = event.getPos();
                com.craftingveloce.network.pipe.VelocePipeNetworkManager manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
                if (manager.isPipe(sl, pos)) {
                    manager.onPipeBroken(sl, pos);
                }
            }
        });

        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate.class, event -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.craftingveloce.network.pipe.VelocePipeNetworkManager manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
                for (net.minecraft.core.BlockPos pos : event.getAffectedBlocks()) {
                    if (manager.isPipe(sl, pos)) {
                        manager.onPipeBroken(sl, pos);
                    }
                }
            }
        });
    }
}
