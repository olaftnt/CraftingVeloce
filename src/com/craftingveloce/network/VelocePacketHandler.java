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
            registrar.playToServer(TerminalPullItemPKT.TYPE, TerminalPullItemPKT.STREAM_CODEC, TerminalPullItemPKT::handle);
            registrar.playToServer(ExtractorSetFilterPKT.TYPE, ExtractorSetFilterPKT.STREAM_CODEC, ExtractorSetFilterPKT::handle);
            registrar.playToServer(ExtractorOpenFilterPKT.TYPE, ExtractorOpenFilterPKT.STREAM_CODEC, ExtractorOpenFilterPKT::handle);
        });
    }
}
