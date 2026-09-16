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
        implements net.minecraft.world.Container, net.minecraft.world.MenuProvider, net.neoforged.neoforge.energy.IEnergyStorage {

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


    public int brewTime = 0;
    private boolean[] lastPotionCount;
    private net.minecraft.world.item.Item ingredient;
    public int energy = 0;
    public static final int ENERGY_CAPACITY = 25_000_000;
    public static final int FE_PER_BREW = 200_000;
    public static final int MAX_PULL_PER_TICK = 1_000_000;

    private com.craftingveloce.network.pipe.VelocePipeNetwork networkFor(net.minecraft.server.level.ServerLevel sl) {
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos side = worldPosition.relative(dir);
            if (sl.isLoaded(side) && sl.getBlockState(side).getBlock() instanceof com.craftingveloce.block.VelocePipeBlock) {
                var net = manager.getNetworkForPipe(sl, side);
                if (net != null) return net;
            }
        }
        return manager.getNetworkForTerminal(sl, worldPosition);
    }


protected final net.minecraft.world.inventory.ContainerData dataAccess = new net.minecraft.world.inventory.ContainerData() {
        public int get(int index) {
            switch (index) {
                case 0: return VeloceBrewingStandBlockEntity.this.brewTime;
                case 1: return VeloceBrewingStandBlockEntity.this.energy;
                default: return 0;
            }
        }
        public void set(int index, int value) {
            switch (index) {
                case 0: VeloceBrewingStandBlockEntity.this.brewTime = value; break;
                case 1: VeloceBrewingStandBlockEntity.this.energy = value; break;
            }
        }
        public int getCount() { return 2; }
    };

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceBrewingStandMenu(id, inv, getBlockPos(), this.dataAccess);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putShort("BrewTime", (short)this.brewTime);
        tag.putInt("Energy", this.energy);
        tag.put("Items", items.createTag(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.brewTime = tag.getShort("BrewTime");
        this.energy = tag.getInt("Energy");
        items.fromTag(tag.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND), registries);
    }

public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, VeloceBrewingStandBlockEntity be) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;

        be.chargeFromItem();
        com.craftingveloce.network.pipe.VeloceEnergyPull.pull(sl, be.networkFor(sl), be, MAX_PULL_PER_TICK);

        boolean canBrew = isBrewable(level.potionBrewing(), be.items);
        boolean isBrewing = be.brewTime > 0;
        ItemStack ingredient = be.items.getItem(3);

        if (isBrewing) {
            if (be.energy >= 500) {
                be.energy -= 500;
                be.brewTime--;
                boolean done = be.brewTime == 0;
                if (done && canBrew) {
                    doBrew(level, pos, be.items);
                    setChanged(level, pos, state);
                } else if (!canBrew || !ingredient.is(be.ingredient)) {
                    be.brewTime = 0;
                    setChanged(level, pos, state);
                }
            }
        } else if (canBrew && be.energy >= 500) {
            be.brewTime = 400; // standard brew time
            be.ingredient = ingredient.getItem();
            setChanged(level, pos, state);
        }
        
        boolean[] currentCount = be.getPotionBits();
        if (!java.util.Arrays.equals(currentCount, be.lastPotionCount)) {
            be.lastPotionCount = currentCount;
            BlockState newState = state;
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HAS_BOTTLE_0)) {
                newState = newState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HAS_BOTTLE_0, currentCount[0]);
                newState = newState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HAS_BOTTLE_1, currentCount[1]);
                newState = newState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HAS_BOTTLE_2, currentCount[2]);
            }
            if (newState != state) {
                level.setBlock(pos, newState, 2);
            }
        }
    }

    private void chargeFromItem() {
        ItemStack battery = items.getItem(4);
        if (battery.isEmpty()) return;
        var itemEnergy = battery.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (itemEnergy == null || !itemEnergy.canExtract()) return;

        int space = ENERGY_CAPACITY - energy;
        if (space <= 0) return;

        int taken = itemEnergy.extractEnergy(space, false);
        if (taken <= 0) return;

        int accepted = receiveEnergy(taken, false);
        if (accepted < taken) {
            itemEnergy.receiveEnergy(taken - accepted, false);
        }
        if (accepted > 0) setChanged();
    }

    private boolean[] getPotionBits() {
        boolean[] bits = new boolean[3];
        for (int i = 0; i < 3; ++i) {
            if (!this.items.getItem(i).isEmpty()) {
                bits[i] = true;
            }
        }
        return bits;
    }

    private static boolean isBrewable(net.minecraft.world.item.alchemy.PotionBrewing brewing, net.minecraft.world.SimpleContainer container) {
        ItemStack ingredient = container.getItem(3);
        if (ingredient.isEmpty()) return false;
        if (!brewing.isIngredient(ingredient)) return false;
        for (int i = 0; i < 3; i++) {
            ItemStack bottle = container.getItem(i);
            if (!bottle.isEmpty() && brewing.hasMix(bottle, ingredient)) {
                return true;
            }
        }
        return false;
    }

    private static void doBrew(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.SimpleContainer container) {
        ItemStack ingredient = container.getItem(3);
        net.minecraft.world.item.alchemy.PotionBrewing brewing = level.potionBrewing();
        for (int i = 0; i < 3; i++) {
            ItemStack bottle = container.getItem(i);
            if (!bottle.isEmpty() && brewing.hasMix(bottle, ingredient)) {
                container.setItem(i, brewing.mix(bottle, ingredient));
            }
        }
        ingredient.shrink(1);
        if (ingredient.getItem().hasCraftingRemainingItem()) {
            ItemStack remainder = new ItemStack(ingredient.getItem().getCraftingRemainingItem());
            if (ingredient.isEmpty()) {
                container.setItem(3, remainder);
            } else {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remainder);
            }
        }
        level.levelEvent(1035, pos, 0);
    }


    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        int space = ENERGY_CAPACITY - energy;
        int accepted = Math.min(space, toReceive);
        if (!simulate && accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) { return 0; }

    @Override
    public int getEnergyStored() { return energy; }

    @Override
    public int getMaxEnergyStored() { return ENERGY_CAPACITY; }

    @Override
    public boolean canExtract() { return false; }

    @Override
    public boolean canReceive() { return true; }

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
