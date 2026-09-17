package com.craftingveloce.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Base block entity for the blocks of this mod.
 *
 * <p>Veloce used to extend {@code com.tom.storagemod.platform.PlatformBlockEntity}
 * from Tom's Simple Storage for this. That one class was the whole reason the mod
 * could not run without Tom: a class cannot be loaded when its superclass is
 * missing, so a missing Tom meant {@code NoClassDefFoundError} during block
 * registration - an unreadable crash instead of a readable "missing dependency".
 *
 * <p>Tom's class only provided "am I still valid" and "invalidate my capabilities".
 * Both live here now, so the mod owns its own hierarchy and Tom is free to be
 * absent.
 */
public class VeloceBlockEntity extends BlockEntity {

    public VeloceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Whether this block entity is still a valid target for cached capabilities. */
    public boolean isObjectValid() {
        return !isRemoved();
    }

    /** Drops every capability cached for this position; called when the block changes. */
    protected void markCapsInvalid() {
        if (level != null) {
            level.invalidateCapabilities(worldPosition);
        }
    }
}
