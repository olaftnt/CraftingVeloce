package com.craftingveloce.block.entity;

import net.minecraft.nbt.CompoundTag;

/**
 * Machine values for the window - on BOTH sides (server and client).
 *
 * <p>The window is an ordinary container (like a furnace), so the menu reads these values
 * from the block entity on the client. That is why this interface returns only what the
 * client really has: kinetic speed is synchronized by Create, and energy
 * by our block entity. The pipe network state (computed on the server) does NOT enter
 * here - the window shows only speed, SU and energy.
 *
 * <p>The variant is recognized by the presence of the {@code energy} key (an FE machine) or
 * its absence (a kinetic machine).
 */
public interface VeloceModuleDisplay {

    /** Window fields: energy/energyCapacity/fePerOperation/operations/powered
     *  or speed/requiredSpeed/suDraw/parts/enoughSpeed. */
    CompoundTag moduleDisplay();
}
