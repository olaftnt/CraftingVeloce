package com.craftingveloce.compat.jade;

import com.craftingveloce.block.entity.VeloceModuleInfoSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Lines in the Jade tooltip: speed/SU or energy + network + status.
 *
 * <p><b>The same texts as the right-click window</b> - both sides take them
 * from {@link VeloceModuleInfoLines}, so they cannot drift apart. The data comes
 * from the server ({@code accessor.getServerData()}), because only it knows the
 * required speed, the SU draw and the network state.
 *
 * <p>We filter by the core {@link VeloceModuleInfoSource}: the provider is
 * registered for all blocks (we do not know - and do not want to know - the
 * block classes from the integration modules), and a foreign block simply does
 * not pass the test.
 *
 * <p><b>Why the FUEL furnace is a second, separate case.</b> The player asked why
 * Jade shows no charge for it, and the answer is structural: Jade's own energy bar is
 * driven by the NeoForge <b>energy capability</b>, and this furnace deliberately
 * registers none, so that no cable or item from another mod can move its heat at all.
 * A block with no capability is invisible to that provider - not broken, just not
 * something Jade is able to introspect.
 *
 * <p>So the readout is written here instead, as text, out of the client block entity
 * (which the furnace keeps in step through its block update packet). It shows the same
 * two numbers the window's battery tooltip shows, from the same translation keys, so
 * the two places cannot disagree.
 */
public enum VeloceModuleComponentProvider implements IComponentProvider<BlockAccessor> {

    INSTANCE;

    @Override
    public ResourceLocation getUid() {
        return VeloceJadePlugin.MODULE_INFO_UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        // The fuel furnace first - it is not a module at all, and its heat comes from the
        // client block entity rather than from Jade's server data.
        if (accessor.getBlockEntity()
                instanceof com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity furnace) {
            appendFuelFurnaceHeat(tooltip, furnace);
            return;
        }
        if (!(accessor.getBlockEntity() instanceof VeloceModuleInfoSource)) {
            return;
        }
        CompoundTag info = accessor.getServerData();
        if (info == null || info.isEmpty()) {
            return;
        }
        // ONE line: working, or short of power. The player does not want
        // anything more here (no rates, no load figures, no network details).
        tooltip.add(com.craftingveloce.crafting.VeloceModuleStatus.message(info));
    }

    /**
     * The fuel furnace's accumulator, in the words the window's battery tooltip uses.
     *
     * <p>In a method of its own, and not inlined above: build.py enforces that the module
     * case adds exactly ONE line, and these lines are about a different machine
     * altogether.
     *
     * <p>The temperature rather than the FE count, because that is what the player reads
     * off a furnace and what they asked the window to show - and the warning in red when
     * there is not yet a whole smelt's worth banked.
     */
    private static void appendFuelFurnaceHeat(ITooltip tooltip,
            com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity furnace) {
        tooltip.add(net.minecraft.network.chat.Component.translatable(
                "gui.craftingveloce.furnace.temperature",
                furnace.getTemperatureC(), furnace.getMaxTemperatureC()));
        tooltip.add(net.minecraft.network.chat.Component.translatable(
                        "gui.craftingveloce.furnace.cycles", furnace.getAffordableSmelts())
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        if (!furnace.isHotEnough()) {
            tooltip.add(net.minecraft.network.chat.Component.translatable(
                            "gui.craftingveloce.furnace.notHotEnough")
                    .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }
}
