package com.craftingveloce.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Nasz brewing stand - cienka warstwa na waniliowym block entity.
 *
 * <p>Dziedziczymy, bo waniliowy ma juz CALA logike warzenia: 5 slotow (0-2
 * butelki, 3 skladnik, 4 blaze powder), brewTime, paliwo i mieszanie mikstur
 * ({@code PotionBrewing}) razem z zapisem w NBT. Kopiowanie tego znaczyloby
 * przepisywanie wanilli.
 *
 * <p>{@link #serverTick()} (bez argumentow) jest po to, zeby ticker bloku
 * (skopiowany z pieca) mial co wolywac - tam jest {@code furnace.serverTick()}.
 */
public class VeloceBrewingStandBlockEntity
        extends net.minecraft.world.level.block.entity.BrewingStandBlockEntity {

    public VeloceBrewingStandBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    /** Waniliowa logika warzenia dla tickera bloku (paliwo, brewTime, mieszanie). */
    public void serverTick() {
        if (level == null) {
            return;
        }
        net.minecraft.world.level.block.entity.BrewingStandBlockEntity.serverTick(
                level, worldPosition, getBlockState(), this);
    }
}
