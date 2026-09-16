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

    /**
     * UWAGA: nie {@code StreamCodec.unit(...)} - ten koduje WYLACZNIE instancje,
     * ktora dostal w konstruktorze, a receptura z datapacku jest inna instancja.
     * Skończylo sie to bledem "Can't encode ... expected ..." przy pakiecie
     * clientbound/minecraft:update_recipes i rozlaczeniem gracza przy wejsciu.
     * Receptura nie ma pol, wiec nic nie zapisujemy, a przy odczycie tworzymy
     * nowa instancje.
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
