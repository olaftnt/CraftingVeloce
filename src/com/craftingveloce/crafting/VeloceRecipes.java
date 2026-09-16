package com.craftingveloce.crafting;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Receptury wlasne Veloce (serializery).
 *
 * <p>Typ receptury to waniliowy {@code crafting} - nasza receptura rozklada
 * tylko obudowy, wiec nie potrzebuje wlasnej kategorii. Rejestrujemy sam
 * serializer, a sama recepture dostarczamy jako JSON w
 * {@code data/craftingveloce/recipe/} (dzieki temu dziala w kazdym swiecie bez
 * zadnego kodu przy ladowaniu).
 */
public final class VeloceRecipes {

    private VeloceRecipes() {
    }

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CraftingVeloceMod.MODID);

    /** Rozkladanie obudowy: maszyna -&gt; pusta obudowa + klocki bazowe. */
    public static final DeferredHolder<RecipeSerializer<?>, VeloceCaseDisassemblySerializer>
            CASE_DISASSEMBLY = SERIALIZERS.register("case_disassembly",
            VeloceCaseDisassemblySerializer::new);

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
