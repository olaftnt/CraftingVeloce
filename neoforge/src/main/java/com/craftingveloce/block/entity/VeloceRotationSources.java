package com.craftingveloce.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * How a rotation source is CONFIGURED, without the core knowing whose source it is.
 *
 * <p><b>Why a registry keyed by block id.</b> A test rig has to be able to say "spin this
 * source at 256 RPM", and only the mod that owns the source knows how. Writing that in the
 * core would put a foreign type in a core class, which {@code validate_jar_isolation}
 * rejects - and rightly, because the core has to work with none of those mods installed.
 *
 * <p>So the core keeps a table of little setters, and each integration fills in its own.
 * The lookup is by the block's REGISTRY ID at use time, the same shape
 * {@code VeloceIntegraleConversions} uses, so nothing has to exist when the entry is
 * registered - and a block from a mod that is not installed simply has no entry.
 */
public final class VeloceRotationSources {

    private VeloceRotationSources() {
    }

    private static final Map<ResourceLocation, BiConsumer<ServerLevel, BlockPos>> TUNERS =
            new ConcurrentHashMap<>();

    /** Registers how a source is configured (called from the owning integration). */
    public static void register(ResourceLocation blockId, BiConsumer<ServerLevel, BlockPos> tuner) {
        TUNERS.put(blockId, tuner);
    }

    /** Configures the source standing at {@code pos}, if anything knows that block. */
    public static void tune(ServerLevel level, BlockPos pos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        BiConsumer<ServerLevel, BlockPos> tuner = id == null ? null : TUNERS.get(id);
        if (tuner != null) {
            tuner.accept(level, pos);
        }
    }
}
