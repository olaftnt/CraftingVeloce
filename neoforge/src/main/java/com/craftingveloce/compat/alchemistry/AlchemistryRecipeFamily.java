package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.VeloceRecipeFamilies;
import com.smashingmods.alchemistry.registry.RecipeRegistry;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The Alchemistry recipe family that Veloce can handle.
 *
 * <p>Alchemistry recipe types do not exist without that mod, so they live here -
 * in {@code compat/alchemistry} - and not in the core.
 *
 * <p><b>Why in {@code FMLCommonSetupEvent}.</b> The DeferredHolders
 * {@code RecipeRegistry.X_TYPE} are bound only after the registration events;
 * reading them in the mod constructor would throw an exception.
 *
 * <p><b>v1 scope.</b> Item machines (compactor, combiner, fission, fusion).
 * The dissolver is probabilistic and requires a probability model, while the
 * liquifier/atomizer work on chemical fluid - they come in later stages (the
 * fluid layer).
 */
public final class AlchemistryRecipeFamily {

    /** Family identifier in {@link VeloceRecipeFamilies}. */
    public static final String ID = "alchemistry";

    private static Set<RecipeType<?>> resolved;

    private AlchemistryRecipeFamily() {
    }

    /** Registers the Alchemistry recipe family (after block registration). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            Set<RecipeType<?>> types = types();
            VeloceRecipeFamilies.registerModFamily(ID, types);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: registered {} recipe types",
                    ID, types.size());
        });
    }

    /** Compactor: 1 item (x count) -> 1 item. */
    public static RecipeType<?> compactor() {
        return RecipeRegistry.COMPACTOR_TYPE.get();
    }

    /** Combiner: N items (each with a count) -> 1 item. */
    public static RecipeType<?> combiner() {
        return RecipeRegistry.COMBINER_TYPE.get();
    }

    /** Fission: 1 item -> 2 items (deterministic). */
    public static RecipeType<?> fission() {
        return RecipeRegistry.FISSION_TYPE.get();
    }

    /** Fusion: 2 items -> 1 item (deterministic). */
    public static RecipeType<?> fusion() {
        return RecipeRegistry.FUSION_TYPE.get();
    }

    public static RecipeType<?> dissolver() {
        return RecipeRegistry.DISSOLVER_TYPE.get();
    }

    public static RecipeType<?> liquifier() {
        return RecipeRegistry.LIQUIFIER_TYPE.get();
    }

    public static RecipeType<?> atomizer() {
        return RecipeRegistry.ATOMIZER_TYPE.get();
    }

    /** Alchemistry recipe types. May be called only when the mod is present. */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            out.add(compactor());
            out.add(combiner());
            out.add(fission());
            out.add(fusion());
            out.add(dissolver());
            out.add(liquifier());
            out.add(atomizer());
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
