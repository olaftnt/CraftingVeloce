package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.SyncTerminalCountsPKT;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.AbstractStorageTerminalBlock.TerminalPos;
import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.NetworkInventory;
import com.tom.storagemod.inventory.StoredItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VeloceTomTerminalBlockEntity extends StorageTerminalBlockEntity {

    private static Field itemCacheField;
    private final List<WeakReference<ServerPlayer>> activeWatchingPlayers = new ArrayList<>();

    static {
        try {
            itemCacheField = StorageTerminalBlockEntity.class.getDeclaredField("itemCache");
            itemCacheField.setAccessible(true);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public VeloceTomTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_TOM_TERMINAL_BE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            InventoryCableNetwork.getNetwork(level).markNodeInvalid(worldPosition);
        }
    }

    public Direction getConnectionDirection() {
        BlockState st = getBlockState();
        Direction facing = st.getValue(AbstractStorageTerminalBlock.FACING);
        TerminalPos p = st.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
        if (p == TerminalPos.UP) return Direction.UP;
        if (p == TerminalPos.DOWN) return Direction.DOWN;
        return facing;
    }

    public void onPlayerOpenTerminal(ServerPlayer player) {
        activeWatchingPlayers.removeIf(ref -> ref.get() == null || ref.get() == player);
        activeWatchingPlayers.add(new WeakReference<>(player));
        syncCountsToPlayer(player);
    }

    public void syncCountsToAllWatchers() {
        if (level == null || level.isClientSide || activeWatchingPlayers.isEmpty()) return;
        Map<Item, Long> counts = getAllStoredItemCounts();
        Iterator<WeakReference<ServerPlayer>> it = activeWatchingPlayers.iterator();
        while (it.hasNext()) {
            ServerPlayer sp = it.next().get();
            if (sp == null || sp.hasDisconnected() || sp.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) > 64.0) {
                it.remove();
            } else {
                PacketDistributor.sendToPlayer(sp, new SyncTerminalCountsPKT(counts));
            }
        }
    }

    public void syncCountsToPlayer(ServerPlayer player) {
        if (level == null || level.isClientSide) return;
        Map<Item, Long> counts = getAllStoredItemCounts();
        PacketDistributor.sendToPlayer(player, new SyncTerminalCountsPKT(counts));
    }

    /**
     * Collects all stored item counts from Refined Storage + Tom's Storage / Chests.
     */
    public Map<Item, Long> getAllStoredItemCounts() {
        Map<Item, Long> counts = new HashMap<>();
        if (level == null || level.isClientSide) return counts;

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        // 1. RS counts
        if (RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite())) {
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, targetPos, connDir.getOpposite());
            for (Map.Entry<Item, Long> entry : rsCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    counts.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }

        // 2. Tom's Storage counts
        getStacks();
        IInventoryAccess access = getTomAccess();
        if (access != null) {
            IInventoryChangeTracker tracker = access.tracker();
            if (tracker != null) {
                tracker.getChangeTracker(level);
                try {
                    tracker.streamWrappedStacks(false).forEach(storedStack -> {
                        if (storedStack != null && !storedStack.getStack().isEmpty() && storedStack.getQuantity() > 0) {
                            counts.merge(storedStack.getStack().getItem(), storedStack.getQuantity(), Long::sum);
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        return counts;
    }

    private IInventoryAccess getTomAccess() {
        if (itemCacheField != null) {
            try {
                NetworkInventory cache = (NetworkInventory) itemCacheField.get(this);
                if (cache != null) {
                    return cache.getAccess(level, worldPosition);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @Override
    public void updateServer() {
        // Keep the item cache alive and actively polling network
        getStacks();
        super.updateServer();

        // Periodically refresh active viewers
        if (level != null && !activeWatchingPlayers.isEmpty() && level.getGameTime() % 20 == 0) {
            syncCountsToAllWatchers();
        }
    }

    /**
     * Prints full debug information about the connected network and resources to the player.
     */
    public void printDebugInfo(ServerPlayer player) {
        if (level == null || level.isClientSide) return;

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Debug] ==="));
        player.sendSystemMessage(Component.literal("§7Terminal Pos: §f[" + worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ() + "]"));
        player.sendSystemMessage(Component.literal("§7Facing: §f" + connDir + " -> Target Pos: §f[" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + "]"));

        // 1. Check Refined Storage
        boolean hasRS = RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite());
        player.sendSystemMessage(Component.literal("§7Refined Storage Network Detected: " + (hasRS ? "§aYES" : "§cNO")));
        if (hasRS) {
            Map<String, Long> rsItems = RefinedStorageHelper.queryRSNetwork(level, targetPos, connDir.getOpposite());
            player.sendSystemMessage(Component.literal("§b--- Refined Storage Contents (" + rsItems.size() + " types) ---"));
            if (rsItems.isEmpty()) {
                player.sendSystemMessage(Component.literal("  §7(empty network)"));
            } else {
                for (Map.Entry<String, Long> entry : rsItems.entrySet()) {
                    player.sendSystemMessage(Component.literal("  §e" + entry.getKey() + " §7x§a" + entry.getValue()));
                }
            }
        }

        // 2. Check Tom's Storage / Connected inventory
        player.sendSystemMessage(Component.literal("§b--- Tom's Storage / Inventory Access ---"));
        getStacks();

        IInventoryAccess access = getTomAccess();
        if (access == null) {
            player.sendSystemMessage(Component.literal("§cNo inventory access found via Tom's Storage!"));
        } else {
            IInventoryChangeTracker tracker = access.tracker();
            if (tracker == null) {
                player.sendSystemMessage(Component.literal("§cTracker is null!"));
            } else {
                tracker.getChangeTracker(level);
                Map<String, Long> tomItems = new LinkedHashMap<>();
                try {
                    tracker.streamWrappedStacks(false).forEach(storedStack -> {
                        if (storedStack != null && !storedStack.getStack().isEmpty()) {
                            String name = storedStack.getStack().getHoverName().getString();
                            long count = storedStack.getQuantity();
                            tomItems.merge(name, count, Long::sum);
                        }
                    });
                } catch (Throwable t) {
                    player.sendSystemMessage(Component.literal("§cError streaming items: " + t.getMessage()));
                }

                player.sendSystemMessage(Component.literal("§7Tom's Storage Items (" + tomItems.size() + " types, Slots: " + access.getSlotCount() + ", Free: " + access.getFreeSlotCount() + "):"));
                if (tomItems.isEmpty()) {
                    player.sendSystemMessage(Component.literal("  §7(empty inventory)"));
                } else {
                    for (Map.Entry<String, Long> entry : tomItems.entrySet()) {
                        player.sendSystemMessage(Component.literal("  §e" + entry.getKey() + " §7x§a" + entry.getValue()));
                    }
                }
            }
        }
        player.sendSystemMessage(Component.literal("§6=============================="));
    }

    /**
     * Extracts an item from the connected network (Refined Storage or Tom's Storage / chests).
     */
    public ItemStack extractItemFromConnectedNetwork(ItemStack requested, int count) {
        if (level == null || level.isClientSide || requested.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        // 1. Try Refined Storage first
        if (RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite())) {
            ItemStack rsExtracted = RefinedStorageHelper.extractItem(level, targetPos, connDir.getOpposite(), requested, count);
            if (!rsExtracted.isEmpty()) {
                syncCountsToAllWatchers();
                return rsExtracted;
            }
        }

        // 2. Try Tom's Storage / connected chests
        try {
            StoredItemStack pulled = pullStack(new StoredItemStack(requested), count);
            if (pulled != null && !pulled.getActualStack().isEmpty()) {
                syncCountsToAllWatchers();
                return pulled.getActualStack();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return ItemStack.EMPTY;
    }
}
