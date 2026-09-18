package com.craftingveloce.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-block FE settings: how big a battery each machine has, and how much one operation costs.
 *
 * <p><b>Why this is a STARTUP config and not a COMMON one.</b> A COMMON config is read before
 * {@code FMLCommonSetupEvent}, which is AFTER the registration events - so a value from it
 * cannot influence whether a block is registered. STARTUP is the only type NeoForge reads
 * immediately on registration: {@code ConfigTracker.registerConfig} opens the file inside the
 * call itself, before returning. That is what makes it possible to add an option that
 * <em>removes</em> a block from the game, and it is also why the values here must already be
 * loaded before any block class is touched - see the registration in the mod constructor.
 *
 * <p><b>THE SAME VALUES MUST BE SET ON THE CLIENT AND THE SERVER.</b> STARTUP configs are not
 * synced across the network, and NeoForge's own documentation warns that using one to disable
 * content can desync a client from a server. The defaults here change nothing, so an install
 * that never opens the file behaves exactly as before; but a server that switches a machine
 * off must have clients that do the same.
 *
 * <p><b>Why the defaults are written out here as well as in the modules.</b> The numbers exist
 * twice: once as the compiled value on each {@code FeModule}, and once here as the default in
 * the config file. That duplication is deliberate - a config needs a literal default that a
 * player can read and edit - and it is kept honest by a guard that compares the two, so the
 * copies cannot drift apart unnoticed.
 */
public final class VeloceBlockConfig {

    public static final ModConfigSpec SPEC;

    /** What the config knows about one block: its three values. */
    private record Entry(ModConfigSpec.IntValue capacity, ModConfigSpec.IntValue fePerOperation) {
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment(
                "FE settings for every machine that runs on Forge Energy.",
                "",
                "capacity      - the size of that machine's internal battery, in FE.",
                "fePerOperation - what ONE operation costs that machine, in FE.",
                "",
                "CHANGING THESE SPLITS CLIENT AND SERVER. This file is a STARTUP config, which",
                "NeoForge does not sync, so a server and its players must agree on the values.",
                "The defaults below are the values the machines were built with, so leaving the",
                "file alone changes nothing.",
                "",
                "Each machine has its own section so one can be tuned without touching another.",
                "A value below 1 is treated as 1: a machine that costs nothing per operation",
                "would run forever, and a battery of zero would refuse every operation.");

        // The two core machines.
        declare(b, "electric_furnace", 25_000_000, 200_000);
        declare(b, "brewing_stand", 25_000_000, 200_000);

        // Mekanism modules - every one is built the same way today.
        declare(b, "mekanism_crusher", 25_000_000, 200_000);
        declare(b, "mekanism_enriching", 25_000_000, 200_000);
        declare(b, "mekanism_combining", 25_000_000, 200_000);
        declare(b, "mekanism_sawing", 25_000_000, 200_000);
        declare(b, "mekanism_compressing", 25_000_000, 200_000);
        declare(b, "mekanism_metallurgic_infusing", 25_000_000, 200_000);

        // Alchemistry modules - these differ from each other, so the numbers are not shared.
        declare(b, "alchemistry_compactor", 25_000_000, 125_000);
        declare(b, "alchemistry_combiner", 25_000_000, 500_000);
        declare(b, "alchemistry_fission", 25_000_000, 750_000);
        declare(b, "alchemistry_fusion", 25_000_000, 750_000);
        declare(b, "alchemistry_dissolver", 25_000_000, 250_000);
        declare(b, "alchemistry_liquifier", 25_000_000, 250_000);
        declare(b, "alchemistry_atomizer", 25_000_000, 250_000);

        SPEC = b.build();
    }

    private VeloceBlockConfig() {
    }

    private static void declare(ModConfigSpec.Builder b, String key, int defaultCapacity,
                                int defaultFePerOperation) {
        b.push(key);
        ModConfigSpec.IntValue capacity = b
                .comment("Battery size of this machine, in FE.")
                .defineInRange("capacity", defaultCapacity, 1, Integer.MAX_VALUE);
        ModConfigSpec.IntValue fePerOperation = b
                .comment("Cost of ONE operation of this machine, in FE.")
                .defineInRange("fePerOperation", defaultFePerOperation, 1, Integer.MAX_VALUE);
        b.pop();
        ENTRIES.put(key, new Entry(capacity, fePerOperation));
    }

    /**
     * The config key for a machine, from the id its module uses.
     *
     * <p>{@code "mekanism:crusher"} becomes {@code "mekanism_crusher"}: a config section name
     * is a path, and a colon in a path makes TOML read it as a nested table.
     */
    public static String keyOf(String moduleId) {
        return moduleId.replace(':', '_').replace('/', '_');
    }

    /** Battery size for a machine, or {@code fallback} when the config does not know it. */
    public static int capacity(String key, int fallback) {
        Entry entry = ENTRIES.get(key);
        return entry == null ? fallback : entry.capacity().get();
    }

    /** Cost of one operation for a machine, or {@code fallback} when the config knows no such machine. */
    public static int fePerOperation(String key, int fallback) {
        Entry entry = ENTRIES.get(key);
        return entry == null ? fallback : entry.fePerOperation().get();
    }

    /** Battery size for a machine named by its module id. */
    public static int capacityOf(String moduleId, int fallback) {
        return capacity(keyOf(moduleId), fallback);
    }

    /** Cost of one operation for a machine named by its module id. */
    public static int fePerOperationOf(String moduleId, int fallback) {
        return fePerOperation(keyOf(moduleId), fallback);
    }

    /** Every key this config defines - for the guard that keeps the defaults in step. */
    public static java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(ENTRIES.keySet());
    }
}
