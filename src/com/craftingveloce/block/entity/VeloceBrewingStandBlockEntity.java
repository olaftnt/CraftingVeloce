package com.craftingveloce.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Nasz brewing stand - WLASNY block entity.
 *
 * <p><b>Dlaczego nie dziedziczymy po waniliowym.</b> Probowałem tak zrobic, ale
 * waniliowy konstruktor ustawia TYP {@code minecraft:brewing_stand}, a nasz blok
 * jest zarejestrowany pod wlasnym typem - przy stawianiu gra wywalala sie
 * (log: {@code Invalid block entity minecraft:brewing_stand // ... got Block
 * craftingveloce:brewing_stand}). Dziedziczenie jest tu wiec niemozliwe i logike
 * warzenia trzeba miec u siebie (nastepny krok: {@code PotionBrewing}).
 *
 * <p>Na razie: 5 slotow jak w wanilii (0-2 butelki, 3 skladnik, 4 blaze powder),
 * zapis w NBT, menu dziala. Bez logiki mieszania - to dalszy etap.
 */
public class VeloceBrewingStandBlockEntity extends BlockEntity
        implements net.minecraft.world.Container, net.minecraft.world.MenuProvider {

    /** 0-2 butelki, 3 skladnik, 4 blaze powder - dokladnie jak w wanilii. */
    private final SimpleContainer items = new SimpleContainer(5);

    public VeloceBrewingStandBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.init.VeloceRegistry.BREWING_STAND_BE.get(), pos, state);
        items.addListener(c -> setChanged());
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.craftingveloce.brewing_stand");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceBrewingStandMenu(id, inv, getBlockPos());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Items", items.createTag(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.fromTag(tag.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND), registries);
    }

    // --- Container (5 slotow) -------------------------------------------------
    @Override
    public int getContainerSize() {
        return items.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return items.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return items.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.setItem(slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clearContent();
    }
}
