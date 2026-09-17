package com.craftingveloce.crafting;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Serializer of the casing disassembly recipe.
 *
 * <p>The recipe has no fields (it works on the grid contents alone), so the
 * codec is a "unit" - exactly like vanilla special recipes. The network version
 * is a unit one too: the client does not need to know anything beyond the type.
 */
public class VeloceCaseDisassemblySerializer implements RecipeSerializer<VeloceCaseDisassemblyRecipe> {

    private static final MapCodec<VeloceCaseDisassemblyRecipe> CODEC =
            MapCodec.unit(new VeloceCaseDisassemblyRecipe());

    /**
     * NOTE: not {@code StreamCodec.unit(...)} - that one encodes ONLY the
     * instance it received in the constructor, while the recipe from the
     * datapack is a different instance. This ended with the error
     * "Can't encode ... expected ..." on the
     * clientbound/minecraft:update_recipes packet and the player being
     * disconnected on join. The recipe has no fields, so we write nothing, and
     * on read we create a new instance.
     */
    private static final StreamCodec<RegistryFriendlyByteBuf, VeloceCaseDisassemblyRecipe> STREAM_CODEC =
            StreamCodec.of((buffer, recipe) -> {
            }, buffer -> new VeloceCaseDisassemblyRecipe());

    @Override
    public MapCodec<VeloceCaseDisassemblyRecipe> codec() {
        return CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, VeloceCaseDisassemblyRecipe> streamCodec() {
        return STREAM_CODEC;
    }
}
