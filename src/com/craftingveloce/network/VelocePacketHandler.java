package com.craftingveloce.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class VelocePacketHandler {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("1.0.0");
            registrar.playToClient(OpenTerminalScreenPKT.TYPE, OpenTerminalScreenPKT.STREAM_CODEC, OpenTerminalScreenPKT::handle);
            registrar.playToClient(SyncTerminalCountsPKT.TYPE, SyncTerminalCountsPKT.STREAM_CODEC, SyncTerminalCountsPKT::handle);
            registrar.playToClient(OpenFilterPickerPKT.TYPE, OpenFilterPickerPKT.STREAM_CODEC, OpenFilterPickerPKT::handle);
            registrar.playToClient(SyncExtractorFiltersPKT.TYPE, SyncExtractorFiltersPKT.STREAM_CODEC, SyncExtractorFiltersPKT::handle);
            registrar.playToClient(OpenCraftingTableScreenPKT.TYPE, OpenCraftingTableScreenPKT.STREAM_CODEC, OpenCraftingTableScreenPKT::handle);
            registrar.playToClient(SyncCraftingTableStatePKT.TYPE, SyncCraftingTableStatePKT.STREAM_CODEC, SyncCraftingTableStatePKT::handle);
            registrar.playToClient(OpenControllerScreenPKT.TYPE, OpenControllerScreenPKT.STREAM_CODEC, OpenControllerScreenPKT::handle);
            registrar.playToClient(SyncControllerFlowPKT.TYPE, SyncControllerFlowPKT.STREAM_CODEC, SyncControllerFlowPKT::handle);
            registrar.playToServer(ControllerFlowRequestPKT.TYPE, ControllerFlowRequestPKT.STREAM_CODEC, ControllerFlowRequestPKT::handle);
            registrar.playToServer(TerminalPullItemPKT.TYPE, TerminalPullItemPKT.STREAM_CODEC, TerminalPullItemPKT::handle);
            registrar.playToServer(TerminalWatcherPKT.TYPE, TerminalWatcherPKT.STREAM_CODEC, TerminalWatcherPKT::handle);
            registrar.playToServer(TerminalStoreItemPKT.TYPE, TerminalStoreItemPKT.STREAM_CODEC, TerminalStoreItemPKT::handle);
            registrar.playToServer(SetFilterPKT.TYPE, SetFilterPKT.STREAM_CODEC, SetFilterPKT::handle);
            registrar.playToServer(ExtractorToggleCraftingPKT.TYPE, ExtractorToggleCraftingPKT.STREAM_CODEC, ExtractorToggleCraftingPKT::handle);
            registrar.playToServer(OpenFilterPKT.TYPE, OpenFilterPKT.STREAM_CODEC, OpenFilterPKT::handle);
            registrar.playToServer(CraftingTableToggleItemPKT.TYPE, CraftingTableToggleItemPKT.STREAM_CODEC, CraftingTableToggleItemPKT::handle);
            registrar.playToServer(CraftingTableCycleRecipePKT.TYPE, CraftingTableCycleRecipePKT.STREAM_CODEC, CraftingTableCycleRecipePKT::handle);
            registrar.playToServer(BufferPullItemPKT.TYPE, BufferPullItemPKT.STREAM_CODEC, BufferPullItemPKT::handle);
            registrar.playToServer(RequestCraftableCountsPKT.TYPE, RequestCraftableCountsPKT.STREAM_CODEC, RequestCraftableCountsPKT::handle);
            registrar.playToClient(SyncCraftableCountsPKT.TYPE, SyncCraftableCountsPKT.STREAM_CODEC, SyncCraftableCountsPKT::handle);
        });
    }
}
