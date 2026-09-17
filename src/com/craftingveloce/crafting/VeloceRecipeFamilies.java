package com.craftingveloce.crafting;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The ONE source of truth about Veloce recipe families.
 *
 * <p><b>Why a separate class.</b> The list of recipe types used to be kept
 * in two places ({@code VeloceRecipeRegistry.FREE_TYPES} and a copy
 * in {@code VeloceRecipeGraph.FREE_TYPES}). Two copies of the same rule always
 * drift apart sooner or later - in this project that has already happened
 * several times (the network node list, the packet list, the GUI layout).
 *
 * <p><b>Mod families are REGISTERED dynamically.</b> Recipe types from other
 * mods (Create, Alchemistry, Mekanism) do not exist without those mods, so they
 * cannot be a constant in the core - they are registered by the {@code compat/*}
 * module after checking that the mod is present (see {@link #registerModFamily}).
 */
public final class VeloceRecipeFamilies {

    private VeloceRecipeFamilies() {
    }

    /** Without infrastructure: crafting table, stonecutter, smithing table. */
    public static final Set<RecipeType<?>> FREE = Set.of(
            RecipeType.CRAFTING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
    );

    /**
     * Requires a POWERED furnace in the network.
     *
     * <p><b>DELIBERATELY A SEPARATE FAMILY.</b> These recipes require fuel, so they
     * must NOT be in {@link #FREE}: the auto-crafter would then consider that it can make
     * an iron ingot from ore for free - without a furnace and without fuel.
     */
    public static final Set<RecipeType<?>> FURNACE = Set.of(
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING
    );

    /** Families from other mods: mod id -> its recipe types. */
    private static final Map<String, Set<RecipeType<?>>> MOD_FAMILIES = new LinkedHashMap<>();

    /** Registers a recipe family from another mod (called from the {@code compat/*} module). */
    public static synchronized void registerModFamily(String id, Set<RecipeType<?>> types) {
        MOD_FAMILIES.put(id, Set.copyOf(types));
    }

    /** Ids of mods that registered their families - for diagnostics. */
    public static synchronized Set<String> modFamilies() {
        return Set.copyOf(MOD_FAMILIES.keySet());
    }

    /**
     * Recipe types that do not need a furnace: {@link #FREE} plus families
     * registered by modules from other mods.
     *
     * <p>This is used by the crafter GUI (the list of recipes to show), which does not know
     * heat. Thanks to that a module registered by {@code compat/*} appears
     * in the GUI without a change in the client code.
     */
    public static synchronized Set<RecipeType<?>> withoutHeat() {
        Set<RecipeType<?>> out = new java.util.LinkedHashSet<>(FREE);
        for (Set<RecipeType<?>> types : MOD_FAMILIES.values()) {
            out.addAll(types);
        }
        return Set.copyOf(out);
    }

    /** All known recipe types: without furnace, furnace and from mods. */
    public static synchronized Set<RecipeType<?>> all() {
        Set<RecipeType<?>> out = new java.util.LinkedHashSet<>(withoutHeat());
        out.addAll(FURNACE);
        return Set.copyOf(out);
    }

    public static boolean isFree(RecipeType<?> type) {
        return FREE.contains(type);
    }

    public static boolean isFurnace(RecipeType<?> type) {
        return FURNACE.contains(type);
    }

    public static synchronized boolean isModFamily(RecipeType<?> type) {
        for (Set<RecipeType<?>> types : MOD_FAMILIES.values()) {
            if (types.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether we know this recipe type - that is, whether some Veloce module handles it.
     *
     * <p>Used for the log when building the index: a type outside the known families
     * is skipped, but we want to know about it.
     */
    public static boolean isKnown(RecipeType<?> type) {
        return isFree(type) || isFurnace(type) || isModFamily(type);
    }
}
