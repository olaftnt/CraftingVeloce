package com.craftingveloce.crafting;

import com.craftingveloce.util.VeloceLog;
import com.craftingveloce.util.VeloceProfiler;
import net.minecraft.server.level.ServerLevel;

/**
 * Builds the recipe indexes BEFORE the player's first terminal page needs them.
 *
 * <p><b>The problem it removes.</b> Every recipe index in this mod is built lazily, on the first
 * query - and the first query of a session is made by the terminal, on the SERVER thread, with
 * the player waiting for the page. Measured in a 339-mod pack, on the first page after the world
 * loaded:
 *
 * <pre>
 *   server.collectMissingTooltips      369 ms   (one modsThatCanMake call asking every module)
 *   server.captureSnapshot             175 ms   (of which the vanilla crafting index: 105 ms)
 *   load.recipeIndex.crafting          105 ms
 *   load.recipeIndex.create             20 ms x 3 types
 * </pre>
 *
 * <p>Roughly 650 ms of index building on the tick thread, caused by nothing more than "nobody
 * had asked yet". Every later page was fast because the indexes existed by then.
 *
 * <p><b>Why this is safe to do off-thread.</b> The indexes are built from the
 * {@code RecipeManager}, which is immutable once the world has loaded and is replaced wholesale
 * on a data reload - so reading it from another thread cannot observe a half-updated recipe list.
 * The caches themselves are the part that had to be made thread-safe, and they were: the vanilla
 * registry builds under a lock, and the Create, Mekanism and Alchemistry harvests build outside
 * their map and publish under a lock on a miss. That work was done for the counting worker and
 * this class reuses it rather than inventing a second path.
 *
 * <p><b>What it deliberately does NOT do.</b> It does not touch the network and does not ask
 * which machines are placed or powered. Those questions read block entities and therefore belong
 * on the server thread - they are cheap anyway, because the answers are cached per tick.
 *
 * <p><b>Failure.</b> A warm-up is an optimisation and nothing depends on it: the lazy build still
 * exists and still runs if this never happens. So one module failing must not take the others
 * down, and it must not be silent either - see {@link #warm}.
 */
public final class VeloceRecipeWarmup {

    private VeloceRecipeWarmup() {
    }

    /**
     * Builds every recipe index this mod owns. <b>Runs on the counting worker.</b>
     *
     * @param level the level whose recipe manager is the source; only its immutable recipe data
     *              is read
     */
    public static void warm(ServerLevel level) {
        if (level == null) {
            return;
        }
        try (var ignored = VeloceProfiler.section("worker.OFF-THREAD.warmIndexes")) {
            long start = System.nanoTime();
            int warmed = 0;
            int failed = 0;

            // 1. THE VANILLA INDEXES. Two calls, because they are separate caches: the crafting
            //    index and the furnace index. `getAllCraftableItems` is the public entry that
            //    builds the first one.
            try {
                VeloceRecipeRegistry.getAllCraftableItems(level);
                VeloceRecipeRegistry.getAllFurnaceCraftableItems(level);
                warmed++;
            } catch (Throwable t) {
                failed++;
                VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                        "could not warm the vanilla recipe indexes: %s", t);
            }

            // 2. EVERY MODULE. One at a time so that a broken integration costs only itself -
            //    a module is other mods' code, and a warm-up that aborted on the first failure
            //    would leave the rest cold while reporting a single error.
            for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
                try {
                    module.warmRecipeIndex(level);
                    warmed++;
                } catch (Throwable t) {
                    failed++;
                    VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                            "could not warm the %s recipe indexes: %s", module.id(), t);
                }
            }

            long millis = (System.nanoTime() - start) / 1_000_000L;
            // NORMAL level, always: this line is the answer to "why was the first page slow, and
            // is that fixed?" - and it is the number that proves the work happened off the tick
            // thread, because it is printed by the worker.
            VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                    "recipe indexes warmed in %d ms (%d source(s), %d failed) - the first "
                            + "terminal page no longer pays for them",
                    millis, warmed, failed);
        }
    }
}
