package com.craftingveloce.client;

import com.craftingveloce.client.gui.VeloceTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Map;

public class ClientTerminalHelper {

    public static void openTerminalScreen(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new VeloceTerminalScreen(mc.player, mc.player.connection.enabledFeatures(), true, pos));
        }
    }

    public static void handleSyncCounts(Map<Item, Long> itemCounts) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VeloceTerminalScreen screen) {
            screen.updateNetworkCounts(itemCounts);
        }
    }
}
