package com.craftingveloce.crafting;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Serializer receptury rozkladajacej obudowe.
 *
 * <p>Receptura nie ma zadnych pol (dziala na samym skladzie siatki), wiec
 * codec jest "unit" - tak samo jak waniliowe receptury specjalne. Wersja dla
 * sieci tez jest jednostkowa: klient nie musi nic znac ponad typ.
 */
public class VeloceCaseDisassemblySerializer implements RecipeSerializer<VeloceCaseDisassemblyRecipe> {

    private static final MapCodec<VeloceCaseDisassemblyRecipe> CODEC =
            MapCodec.unit(new VeloceCaseDisassemblyRecipe());

    private static final StreamCodec<RegistryFriendlyByteBuf, VeloceCaseDisassemblyRecipe> STREAM_CODEC =
            StreamCodec.unit(new VeloceCaseDisassemblyRecipe());

    @Override
    public MapCodec<VeloceCaseDisassemblyRecipe> codec() {
        return CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, VeloceCaseDisassemblyRecipe> streamCodec() {
        return STREAM_CODEC;
    }
}
