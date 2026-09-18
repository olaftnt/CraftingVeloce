package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.VeloceRecipeFamilies;
import mekanism.api.recipes.MekanismRecipeTypes;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The Mekanism recipe family that Veloce can handle.
 *
 * <p>Mekanism recipe types do not exist without that mod, so they live here.
 *
 * <p><b>Why in {@code FMLCommonSetupEvent}.</b> {@code TYPE_*} are
 * DeferredHolders bound only after the registration events - reading them in
 * the mod constructor would throw an exception.
 *
 * <p><b>What is DELIBERATELY not here.</b> Mekanism's {@code TYPE_SMELTING}
 * appends ALL vanilla smelting recipes to itself, so adding it to this family
 * would duplicate the furnace family ({@link VeloceRecipeFamilies#FURNACE}) -
 * the same item would have two competing paths and the numbers in the GUI would
 * be counted twice. The Energized Smelter will get its own, separate family
 * together with its module.
 *
 * <p><b>v1 scope.</b> Item machines: crusher, enrichment chamber, combiner,
 * precision sawmill. Chemicals and fluids come in the chemical layer stage.
 */
public final class MekanismRecipeFamily {

    /** Family identifier in {@link VeloceRecipeFamilies}. */
    public static final String ID = "mekanism";

    private static Set<RecipeType<?>> resolved;

    private MekanismRecipeFamily() {
    }

    /** Registers the Mekanism recipe family (after block registration). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            Set<RecipeType<?>> types = types();
            VeloceRecipeFamilies.registerModFamily(ID, types);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: registered {} recipe types",
                    ID, types.size());
        });
    }

    /** Crusher: ore/ingot -> dust (deterministic, 1 -> 1). */
    public static RecipeType<?> crushing() {
        return MekanismRecipeTypes.TYPE_CRUSHING.get();
    }

    /** Enrichment: ore -> 3 ingots (deterministic, 1 -> 1). */
    public static RecipeType<?> enriching() {
        return MekanismRecipeTypes.TYPE_ENRICHING.get();
    }

    /** Combining: two items -> one (Combiner). */
    public static RecipeType<?> combining() {
        return MekanismRecipeTypes.TYPE_COMBINING.get();
    }

    /** Sawing: item -> planks + RANDOM sawdust (Precision Sawmill). */
    public static RecipeType<?> sawing() {
        return MekanismRecipeTypes.TYPE_SAWING.get();
    }

    public static RecipeType<?> compressing() {
        return MekanismRecipeTypes.TYPE_COMPRESSING.get();
    }

    public static RecipeType<?> metallurgic_infusing() {
        return MekanismRecipeTypes.TYPE_METALLURGIC_INFUSING.get();
    }

    public static RecipeType<?> purifying() {
        return MekanismRecipeTypes.TYPE_PURIFYING.get();
    }

    public static RecipeType<?> injecting() {
        return MekanismRecipeTypes.TYPE_INJECTING.get();
    }

    public static RecipeType<?> crystallizing() {
        return MekanismRecipeTypes.TYPE_CRYSTALLIZING.get();
    }

    public static RecipeType<?> dissolution() {
        return MekanismRecipeTypes.TYPE_DISSOLUTION.get();
    }

    public static RecipeType<?> washing() {
        return MekanismRecipeTypes.TYPE_WASHING.get();
    }

    public static RecipeType<?> separating() {
        return MekanismRecipeTypes.TYPE_SEPARATING.get();
    }

    public static RecipeType<?> reaction() {
        return MekanismRecipeTypes.TYPE_REACTION.get();
    }

    public static RecipeType<?> rotary() {
        return MekanismRecipeTypes.TYPE_ROTARY.get();
    }

    public static RecipeType<?> activating() {
        return MekanismRecipeTypes.TYPE_ACTIVATING.get();
    }

    public static RecipeType<?> centrifuging() {
        return MekanismRecipeTypes.TYPE_CENTRIFUGING.get();
    }

    public static RecipeType<?> nucleosynthesizing() {
        return MekanismRecipeTypes.TYPE_NUCLEOSYNTHESIZING.get();
    }

    public static RecipeType<?> pigment_extracting() {
        return MekanismRecipeTypes.TYPE_PIGMENT_EXTRACTING.get();
    }

    public static RecipeType<?> pigment_mixing() {
        return MekanismRecipeTypes.TYPE_PIGMENT_MIXING.get();
    }

    public static RecipeType<?> painting() {
        return MekanismRecipeTypes.TYPE_PAINTING.get();
    }

    public static RecipeType<?> oxidizing() {
        return MekanismRecipeTypes.TYPE_OXIDIZING.get();
    }

    public static RecipeType<?> chemical_infusing() {
        return MekanismRecipeTypes.TYPE_CHEMICAL_INFUSING.get();
    }


    /**
     * Mekanism recipe types, taken from the ENABLED modules.
     *
     * <p>It used to be a hand-written list of twenty-three calls, which meant a machine
     * switched off in {@code MekanismFeModules.DISABLED} still had its recipe type
     * claimed here - and the module then offered to handle recipes no machine of ours
     * could run. Reading the list from {@code ALL} instead keeps the two in step by
     * construction, and the Energized Smelter's type went with its module.
     *
     * <p>May only be called when the mod is present: the suppliers resolve Deferred
     * holders of the foreign mod.
     */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            for (com.craftingveloce.crafting.FeModule module : MekanismFeModules.ALL) {
                RecipeType<?> type = module.recipeType().get();
                if (type != null) {
                    out.add(type);
                }
            }
            resolved = out;
        }
        return resolved;
    }
}
