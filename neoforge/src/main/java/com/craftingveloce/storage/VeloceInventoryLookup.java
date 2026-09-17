package com.craftingveloce.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.function.BooleanSupplier;

/**
 * One adjacent block's inventory, read through NeoForge's standard item handler.
 *
 * <p><b>Why this class exists.</b> The pipes used to read neighbouring inventories
 * through Tom's Simple Storage ({@code PlatformInventoryAccess.BlockInventoryAccess}).
 * Underneath, that class is only a {@link BlockCapabilityCache} over
 * {@link Capabilities#ItemHandler} - so using it meant depending on Tom for
 * something the loader already provides. This is the same thing without Tom.
 *
 * <p><b>This is also the whole "integration" with Tom.</b> Tom exposes its own
 * Inventory Connector block through the standard item handler capability:
 *
 * <pre>
 *   event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.connectorBE.get(),
 *                             (be, side) -&gt; new PlatformItemHandler(be));
 * </pre>
 *
 * So a pipe that reads this capability sees Tom's connector as an ordinary
 * inventory - and because that handler is a view over Tom's entire network, the
 * pipe ends up reading Tom's whole storage. No Tom class is referenced anywhere
 * for this to work, and it keeps working when Tom is absent (there is simply no
 * such block then).
 *
 * <p>A cache is used rather than a plain {@code getCapability} call because the
 * pipe re-reads its six sides periodically; resolving the capability fresh every
 * time walks the neighbour's block entity on every scan.
 */
public final class VeloceInventoryLookup {

    private boolean valid;
    private BlockCapabilityCache<IItemHandler, Direction> cache;

    /**
     * Starts tracking the block at {@code pos}, seen from {@code side}.
     *
     * @param stillValid lets the cache drop itself when the owning block entity
     *                   goes away; without it a removed pipe would keep handing
     *                   out a handler for a neighbour it no longer touches
     */
    public void onLoad(Level level, BlockPos pos, Direction side, BooleanSupplier stillValid) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        valid = true;
        cache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, serverLevel, pos, side,
                () -> valid && stillValid.getAsBoolean(), this::onInvalid);
    }

    protected void onInvalid() {
        // Nothing to release: the handler belongs to the neighbour, not to us.
    }

    /** The neighbour's inventory, or {@code null} when there is none. */
    public IItemHandler get() {
        return cache == null || !valid ? null : cache.getCapability();
    }

    /** Whether a usable inventory is currently attached on this side. */
    public boolean exists() {
        return valid && cache != null && cache.getCapability() != null;
    }

    /** Stops tracking; the next {@link #onLoad} starts again. */
    public void markInvalid() {
        valid = false;
    }

    /**
     * Whether the block at {@code pos} exposes an inventory towards
     * {@code direction} - that is, whether a pipe may connect to it.
     *
     * <p>Deliberately a plain capability probe rather than a block-type list: it
     * answers "yes" for chests, for other mods' machines, for our own blocks and
     * for Tom's Inventory Connector, with no foreign type in sight.
     */
    public static boolean hasInventoryAt(Level level, BlockPos pos, BlockState state, Direction direction) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, null, direction) != null;
    }
}
