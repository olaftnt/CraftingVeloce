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

    public static void openFilterPickerScreen(BlockPos pos, int filterIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceFilterPickerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true, pos, filterIndex));
        }
    }

    public static void handleSyncExtractorFilters(BlockPos pos, java.util.List<net.minecraft.world.item.ItemStack> filters) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.craftingveloce.client.gui.VeloceExtractorScreen screen) {
            screen.updateFilters(filters);
        }
    }

    public static net.minecraft.world.phys.HitResult getClientHitResult() {
        return Minecraft.getInstance().hitResult;
    }
}
