package com.craftingveloce.rs;

import com.craftingveloce.util.VeloceLog;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class RefinedStorageHelper {

    /**
     * Checks if there is an active Refined Storage network at targetPos.
     */
    public static boolean hasRSNetwork(Level level, BlockPos targetPos, Direction side) {
        try {
            BlockState state = level.getBlockState(targetPos);
            BlockEntity be = level.getBlockEntity(targetPos);
            NetworkNodeContainerProvider provider = RefinedStorageNeoForgeApi.INSTANCE
                    .getNetworkNodeContainerProviderCapability()
                    .getCapability(level, targetPos, state, be, side);
            if (provider == null) return false;
            for (InWorldNetworkNodeContainer container : provider.getContainers()) {
                if (container != null && container.getNode() != null && container.getNode().getNetwork() != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Collects all stored items and counts in the RS network mapped by Item.
     */
    public static Map<Item, Long> getRSItemCounts(Level level, BlockPos targetPos, Direction side) {
        Map<Item, Long> map = new HashMap<>();
        try {
            BlockState state = level.getBlockState(targetPos);
            BlockEntity be = level.getBlockEntity(targetPos);

            NetworkNodeContainerProvider provider = RefinedStorageNeoForgeApi.INSTANCE
                    .getNetworkNodeContainerProviderCapability()
                    .getCapability(level, targetPos, state, be, side);

            if (provider == null) return map;

            for (InWorldNetworkNodeContainer container : provider.getContainers()) {
                if (container == null || container.getNode() == null) continue;
                Network network = container.getNode().getNetwork();
                if (network == null) continue;

                StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
                if (storage == null) continue;

                for (ResourceAmount ra : storage.getAll()) {
                    if (ra.resource() instanceof ItemResource itemRes) {
                        Item item = itemRes.item();
                        if (item != null && ra.amount() > 0) {
                            map.merge(item, ra.amount(), Long::sum);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            VeloceLog.Network.error(VeloceLog.Side.SERVER, t,
                    "reading RS item counts at %s failed", targetPos);
        }
        return map;
    }

    /**
     * Queries the Refined Storage network connected to the block at targetPos.
     * Returns a map of item/fluid names and quantities.
     */
    public static Map<String, Long> queryRSNetwork(Level level, BlockPos targetPos, Direction side) {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            BlockState state = level.getBlockState(targetPos);
            BlockEntity be = level.getBlockEntity(targetPos);

            NetworkNodeContainerProvider provider = RefinedStorageNeoForgeApi.INSTANCE
                    .getNetworkNodeContainerProviderCapability()
                    .getCapability(level, targetPos, state, be, side);

            if (provider == null) {
                return map;
            }

            for (InWorldNetworkNodeContainer container : provider.getContainers()) {
                if (container == null || container.getNode() == null) continue;
                Network network = container.getNode().getNetwork();
                if (network == null) continue;

                StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
                if (storage == null) continue;

                for (ResourceAmount ra : storage.getAll()) {
                    if (ra.resource() instanceof ItemResource itemRes) {
                        ItemStack stack = itemRes.toItemStack();
                        if (!stack.isEmpty()) {
                            String name = stack.getHoverName().getString();
                            map.merge(name, ra.amount(), Long::sum);
                        }
                    } else if (ra.resource() instanceof FluidResource fluidRes) {
                        String fluidName = BuiltInRegistries.FLUID.getKey(fluidRes.fluid()).toString();
                        map.merge("[Fluid] " + fluidName, ra.amount(), Long::sum);
                    }
                }
            }
        } catch (Throwable t) {
            VeloceLog.Network.error(VeloceLog.Side.SERVER, t,
                    "querying RS network at %s failed", targetPos);
        }
        return map;
    }

    /**
     * Extracts items from the Refined Storage network connected to targetPos.
     */
    public static ItemStack extractItem(Level level, BlockPos targetPos, Direction side, ItemStack requested, int count) {
        try {
            BlockState state = level.getBlockState(targetPos);
            BlockEntity be = level.getBlockEntity(targetPos);

            NetworkNodeContainerProvider provider = RefinedStorageNeoForgeApi.INSTANCE
                    .getNetworkNodeContainerProviderCapability()
                    .getCapability(level, targetPos, state, be, side);

            if (provider == null) return ItemStack.EMPTY;

            for (InWorldNetworkNodeContainer container : provider.getContainers()) {
                if (container == null || container.getNode() == null) continue;
                Network network = container.getNode().getNetwork();
                if (network == null) continue;

                StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
                if (storage == null) continue;

                ItemResource targetResource = ItemResource.ofItemStack(requested);
                long extracted = storage.extract(targetResource, count, Action.EXECUTE, Actor.EMPTY);
                if (extracted > 0) {
                    return requested.copyWithCount((int) extracted);
                }
            }
        } catch (Throwable t) {
            VeloceLog.Network.error(VeloceLog.Side.SERVER, t,
                    "extracting from RS at %s failed", targetPos);
        }
        return ItemStack.EMPTY;
    }

    /** Zgodnosc: true gdy wszystko przyjete. */
    public static boolean insertItem(Level level, BlockPos targetPos, Direction side, ItemStack stack) {
        return insertItemLeftover(level, targetPos, side, stack).isEmpty();
    }

    /**
     * Wklada ile sie da i zwraca RESZTE.
     *
     * <p>Ten sam blad co w {@code ConnectedEndpointInfo}: stara wersja zwracala
     * sam boolean, wiec czesciowe przyjecie bylo raportowane jako porazka -
     * a itemy juz byly w magazynie. Wolajacy nie zabieral ich wtedy graczowi,
     * co konczylo sie duplikacja.
     */
    public static ItemStack insertItemLeftover(Level level, BlockPos targetPos, Direction side, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            BlockState state = level.getBlockState(targetPos);
            BlockEntity be = level.getBlockEntity(targetPos);

            NetworkNodeContainerProvider provider = RefinedStorageNeoForgeApi.INSTANCE
                    .getNetworkNodeContainerProviderCapability()
                    .getCapability(level, targetPos, state, be, side);

            if (provider == null) {
                return stack;
            }

            for (InWorldNetworkNodeContainer container : provider.getContainers()) {
                if (container == null || container.getNode() == null) {
                    continue;
                }
                Network network = container.getNode().getNetwork();
                if (network == null) {
                    continue;
                }

                StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
                if (storage == null) {
                    continue;
                }

                ItemResource resource = ItemResource.ofItemStack(stack);
                long inserted = storage.insert(resource, stack.getCount(), Action.EXECUTE, Actor.EMPTY);
                if (inserted >= stack.getCount()) {
                    return ItemStack.EMPTY;
                }
                // Czesciowo przyjeto - zmniejsz i probuj dalej w kolejnych kontenerach.
                stack = stack.copyWithCount(stack.getCount() - (int) inserted);
                if (stack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        } catch (Throwable t) {
            VeloceLog.Network.error(VeloceLog.Side.SERVER, t,
                    "inserting into RS at %s failed", targetPos);
        }
        return stack;
    }
}
