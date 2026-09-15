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
                        output.accept(VeloceRegistry.VELOCE_PIPE_ITEM.get());
                        output.accept(VeloceRegistry.VELOCE_WRENCH.get());
                    })
                    .build()
    );

    public CraftingVeloceMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("CraftingVeloce initializing...");
        VeloceRegistry.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        VelocePacketHandler.register(modEventBus);

        modEventBus.addListener(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent.class, event -> {
            event.register(VeloceRegistry.VELOCE_EXTRACTOR_MENU.get(), com.craftingveloce.client.gui.VeloceExtractorScreen::new);
        });

        modEventBus.addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class, event -> {
            event.enqueueWork(() -> {
                // All Veloce blocks must support transparency. The primary mechanism is the
                // "render_type" field in each block model JSON; registering here as well keeps the
                // chunk render type set correct even for models that inherit an opaque parent.
                // TRANSLUCENT (not CUTOUT) is required so that animated items rendered inside the
                // blocks blend smoothly instead of being reduced to 1-bit alpha.
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        VeloceRegistry.VELOCE_PIPE.get(),
                        net.minecraft.client.renderer.RenderType.translucent()
                );
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        VeloceRegistry.VELOCE_EXTRACTOR.get(),
                        net.minecraft.client.renderer.RenderType.translucent()
                );
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        VeloceRegistry.VELOCE_CRAFTING_TABLE.get(),
                        net.minecraft.client.renderer.RenderType.translucent()
                );
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        VeloceRegistry.VELOCE_TOM_TERMINAL.get(),
                        net.minecraft.client.renderer.RenderType.translucent()
                );
            });
        });

        modEventBus.addListener(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class, event -> {
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    VeloceRegistry.VELOCE_EXTRACTOR_BE.get(),
                    (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be.getOutputInventory())
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
