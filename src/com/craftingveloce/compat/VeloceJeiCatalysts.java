package com.craftingveloce.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

/**
 * Registry of recipe categories that OUR blocks handle - data for JEI.
 *
 * <p><b>Why a separate registry, and not registration in the JEI plugin.</b> The
 * JEI plugin is a class with foreign types ({@code mezz.jei}), so it can be
 * loaded ONLY when JEI is present. The tables "which of our blocks does which
 * recipe" are known, however, to the integration modules (Create, Mekanism,
 * Alchemistry) - and it is they that fill it in, with no contact with JEI at
 * all. Thanks to that:
 * <ul>
 *   <li>the JEI plugin does not import a single Create/Mekanism/Alchemistry
 *       class - it works even when there is NOTHING of those mods (and then
 *       there is simply nothing to look for in {@link #all()}),</li>
 *   <li>the absence of JEI breaks nothing: the registry just sits there, nobody
 *       reads it.</li>
 * </ul>
 *
 * <p><b>A category UID is the JEI CATEGORY UID, and not our recipe type.</b>
 * Those are two different identifiers and the easiest mistake in this place: the
 * Create saw has the JEI category {@code create:sawing}, although the recipe type
 * is called {@code create:cutting}. The UIDs were established from the mods'
 * bytecode (Create: {@code Create.asResource(name)} from
 * {@code build("sawing", ...)}, Mekanism:
 * {@code RecipeTypeRegistryObject.getId()}, Alchemistry:
 * {@code RecipeType.create("alchemistry", ...)}), and not guessed from names.
 *
 * <p><b>This class must not contain foreign mod types</b> - it is ALWAYS loaded
 * (the core writes into it, as do the integration modules), also without JEI.
 */
public final class VeloceJeiCatalysts {

    /**
     * One registry entry: a recipe category + our block that handles it.
     *
     * <p>The block is a {@link Supplier}, and not a ready item, because the item
     * registry is not ready yet at the moment this registry is filled in.
     */
    public record Catalyst(ResourceLocation category, Supplier<? extends ItemLike> item) {
    }

    private static final List<Catalyst> CATALYSTS = new ArrayList<>();

    private VeloceJeiCatalysts() {
    }

    /** Adds a block to the category with the given UID, e.g. {@code "create:crushing"}. */
    public static void register(String category, Supplier<? extends ItemLike> item) {
        register(ResourceLocation.parse(category), item);
    }

    /** Adds a block to the category (the version for a ready UID). */
    public static void register(ResourceLocation category, Supplier<? extends ItemLike> item) {
        Catalyst catalyst = new Catalyst(category, item);
        if (!CATALYSTS.contains(catalyst)) {
            CATALYSTS.add(catalyst);
        }
    }

    /**
     * Categories handled by the core itself - without any foreign mod.
     *
     * <p>The Veloce table is a fully fledged crafting table, so in JEI it is
     * supposed to show up on the "this can be done in" list next to the
     * Minecraft table.
     */
    public static void registerDefaults() {
        register("minecraft:crafting",
                () -> com.craftingveloce.init.VeloceRegistry.VELOCE_CRAFTING_TABLE_ITEM.get());
    }

    /** A copy of the registry - the JEI plugin reads it only once JEI has loaded. */
    public static List<Catalyst> all() {
        return List.copyOf(CATALYSTS);
    }
}
