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
                    .icon(() -> new ItemStack(VeloceRegistry.VELOCE_TOM_TERMINAL_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(VeloceRegistry.VELOCE_TOM_TERMINAL_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_EXTRACTOR_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_CRAFTING_TABLE_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_CONTROLLER_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_PIPE_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_WRENCH.get());
                    })
                    .build()
    );

    public CraftingVeloceMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("CraftingVeloce initializing...");

        // Rejestracja configu (config/craftingveloce-common.toml).
        // UWAGA: wartosci configu mozna czytac DOPIERO po jego wczytaniu.
        // Odczyt w konstruktorze rzuca "Cannot get config value before config
        // is loaded" i wywala caly mod - dlatego logujemy dopiero w zdarzeniu
        // ModConfigEvent.Loading, ktore odpala sie po wczytaniu.
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
        CREATIVE_TABS.register(modEventBus);
        VelocePacketHandler.register(modEventBus);

        modEventBus.addListener(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent.class, event -> {
            event.register(VeloceRegistry.VELOCE_EXTRACTOR_MENU.get(), com.craftingveloce.client.gui.VeloceExtractorScreen::new);
        });

        // UWAGA: przezroczystosc blokow zostala CELOWO WYLACZONA.
        // Usunieto zarowno "render_type" z modeli w assets/.../models/block/,
        // jak i rejestracje render layeru ponizej. Wszystkie bloki Veloce
        // renderuja sie teraz jako solid (nieprzezroczyste).
        //
        // Jesli kiedys bedziesz chcial wrocic do przezroczystosci, potrzebne sa OBA:
        //   1. "render_type": "minecraft:translucent" w kazdym modelu bloku
        //   2. rejestracja ponizej (ItemBlockRenderTypes.setRenderLayer)
        // Sam render_type w JSON wystarcza dla wiekszosci przypadkow, ale
        // rejestracja w kodzie gwarantuje poprawny chunk render type set.

        modEventBus.addListener(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class, event -> {
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    VeloceRegistry.VELOCE_EXTRACTOR_BE.get(),
                    (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be.getOutputInventory())
            );
            // Bufor auto-craftera - dzieki temu nadwyzka produkcji jest normalnie
            // widoczna dla sieci (rury, terminal, extractor).
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(),
                    (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be.getBuffer())
            );
        });

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            CVDebugCommand.register(event.getDispatcher());
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

        // --- Reakcja na zmiany w swiecie ---------------------------------
        // Chunki z blokami sieci moga sie zaladowac/rozladowac w dowolnym
        // momencie (gracz podchodzi, odchodzi, chunk zostaje wyciagniety przez
        // inny mod). Stock sieci zmienia sie wtedy gwaltownie, wiec cache
        // craftowalnosci musi o tym wiedziec - inaczej GUI pokazuje stare liczby.
        // Dodatkowe zabezpieczenie: zwolnij force-loady przy rozladowaniu
        // wymiaru (zmiana swiata, powrot do menu glownego).
        // Odroczone przebudowy sieci rur. Bez tego kazdy neighborChanged
        // (a jest ich duzo, gdy obok pracuje maszyna) robil pelny BFS sieci
        // natychmiast - kilka razy na tick.
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, event -> {
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                        com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl).tick(sl);
                    }
                });

        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.level.LevelEvent.Unload.class, event -> {
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                        com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl)
                                .clearPendingRebuilds();
                        com.craftingveloce.crafting.VeloceCraftingCache.releaseAll(sl);
                    }
                });

        // Zwalniamy force-loady chunkow PRZED zapisem swiata.
        // Bez tego Minecraft probuje rozladowac chunki, ktore my trzymamy,
        // w kolko - i zapis sie zawiesza.
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
                com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl)
                        .onChunkChanged(sl, event.getChunk().getPos(), false);
            }
        });

        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent.class, event -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                net.minecraft.core.BlockPos pos = event.getPos();
                com.craftingveloce.network.pipe.VelocePipeNetworkManager manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
                if (manager.getNetworkForPipe(pos) != null) {
                    manager.onPipeBroken(sl, pos);
                }
            }
        });

        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate.class, event -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.craftingveloce.network.pipe.VelocePipeNetworkManager manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
                for (net.minecraft.core.BlockPos pos : event.getAffectedBlocks()) {
                    if (manager.getNetworkForPipe(pos) != null) {
                        manager.onPipeBroken(sl, pos);
                    }
                }
            }
        });
    }
}
