package com.craftingveloce.compat.jade;

import com.craftingveloce.block.entity.VeloceModuleInfoSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Server data for Jade: a description of our machine.
 *
 * <p><b>Why server data and not a client-side read.</b> The numbers (required
 * speed, SU draw, how many operations will come out of the accumulator, the state
 * of the pipe network) are known only to the server - and it is the server that
 * computes them for the window on right click. The client receives them as NBT
 * through Jade, exactly as it receives them in the packet for the window, so both
 * places show exactly the same values and the client guesses nothing.
 *
 * <p>We register for ALL block entities and filter by the core interface
 * {@link VeloceModuleInfoSource} - thanks to that this class does not have to
 * know a single type from Create/Mekanism/Alchemistry (and it must work when any
 * of them is absent).
 */
public enum VeloceModuleDataProvider implements IServerDataProvider<BlockAccessor> {

    INSTANCE;

    @Override
    public ResourceLocation getUid() {
        return VeloceJadePlugin.MODULE_INFO_UID;
    }

    /**
     * We ask for data ONLY about our machines.
     *
     * <p>Without this Jade would send (and compute) a description for every block
     * entity in the world that the player looks at - and computing a network plan
     * is not free.
     */
    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        return accessor.getBlockEntity() instanceof VeloceModuleInfoSource;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof VeloceModuleInfoSource source
                && accessor.getLevel() instanceof ServerLevel level) {
            data.merge(source.moduleInfo(level));
        }
    }
}
