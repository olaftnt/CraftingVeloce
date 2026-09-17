package com.craftingveloce.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * A machine that can describe itself to the GUI (speed, SU, network).
 *
 * <p>The core does not know the Create types, so the module window asks for the
 * data through this interface - and it is filled in by whoever actually has
 * those numbers (the kinetic machine block entity in {@code compat/create}).
 */
public interface VeloceModuleInfoSource {

    /** Module window fields (speed, demand, network) - computed on the server. */
    CompoundTag moduleInfo(ServerLevel level);
}
