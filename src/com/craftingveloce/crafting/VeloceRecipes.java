package com.craftingveloce.crafting;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Veloce's own recipes (serializers).
 *
 * <p>The recipe type is the vanilla {@code crafting} - our recipe only
 * disassembles casings, so it does not need a category of its own. We register
 * only the serializer, and we deliver the recipe itself as JSON in
 * {@code data/craftingveloce/recipe/} (thanks to that it works in every world
 * with no loading-time code at all).
 */
public final class VeloceRecipes {

    private VeloceRecipes() {
    }

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CraftingVeloceMod.MODID);

    /** Casing disassembly: machine -&gt; empty casing + base blocks. */
    public static final DeferredHolder<RecipeSerializer<?>, VeloceCaseDisassemblySerializer>
            CASE_DISASSEMBLY = SERIALIZERS.register("case_disassembly",
            VeloceCaseDisassemblySerializer::new);

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
