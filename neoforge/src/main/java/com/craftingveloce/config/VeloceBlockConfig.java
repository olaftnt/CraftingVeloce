package com.craftingveloce.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-block FE settings: how big a battery each machine has, and how much one operation costs.
 *
 * <p><b>Why this is a SERVER config.</b> NeoForge SYNCS server configs to the client, so a
 * player who joins a server receives that server's numbers automatically - which is the only
 * behaviour that makes sense for a gameplay value: a battery size that differs between the
 * two sides is a disagreement about the game, not a preference. Server configs live per world
 * ({@code saves/<world>/serverconfig/craftingveloce-server.toml}), so a pack can also set
 * them differently per world.
 *
 * <p>A COMMON or STARTUP config would NOT have worked here: neither is synced, so every client
 * would keep its own copy of the file and nothing would tell them they disagree.
 *
 * <p><b>A SERVER config does not exist until a world does</b>, and that is the one thing this
 * class has to handle. Before the world loads - in a menu, in JEI, on the main screen - a read
 * would throw "Cannot get config value before config is loaded". Every accessor below answers
 * with the machine's compiled value in that window instead, which is also the value the file
 * starts with, so nothing observable happens before the real numbers arrive.
 *
 * <p><b>This is not the place for a "disable this machine" switch.</b> Removing a block from
 * the game has to happen at REGISTRATION time, and a SERVER config is read far too late for
 * that. Such an option would need a STARTUP config, and would then genuinely require the same
 * file on both sides - a server cannot send a player a block they are missing.
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
                "This is a SERVER config, so NeoForge sends these values to every player who",
                "joins - nobody has to copy this file. It lives per world, under that world's",
                "serverconfig folder, so different worlds can differ.",
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

    /**
     * Battery size for a machine, or {@code fallback} when the config does not know it.
     *
     * <p>The {@code catch} is not defensive padding: a SERVER config is only loaded once a
     * world is, and these are read from block entities whose screens, JEI entries and menus can
     * exist before that. NeoForge's own check throws "Cannot get config value before config is
     * loaded" there, and taking that at face value would crash a client on a menu. Before the
     * world the answer is the machine's compiled value - the same number the file starts with -
     * so nothing observable happens; after it, the real one, including the server's when this
     * client joined one.
     */
    public static int capacity(String key, int fallback) {
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            return entry.capacity().get();
        } catch (IllegalStateException beforeTheWorldIsLoaded) {
            return fallback;
        }
    }

    /** Cost of one operation for a machine - see {@link #capacity} for the {@code catch}. */
    public static int fePerOperation(String key, int fallback) {
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            return entry.fePerOperation().get();
        } catch (IllegalStateException beforeTheWorldIsLoaded) {
            return fallback;
        }
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
