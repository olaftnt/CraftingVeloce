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
 */
public enum VeloceModuleComponentProvider implements IComponentProvider<BlockAccessor> {

    INSTANCE;

    @Override
    public ResourceLocation getUid() {
        return VeloceJadePlugin.MODULE_INFO_UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
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
}
