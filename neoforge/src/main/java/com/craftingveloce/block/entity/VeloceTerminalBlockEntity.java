package com.craftingveloce.block.entity;

import com.craftingveloce.storage.VeloceBlockEntity;
import com.craftingveloce.util.VeloceLog;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.SyncTerminalCountsPKT;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.network.pipe.ConnectedEndpointInfo;
import com.craftingveloce.network.pipe.VeloceChunkLoader;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VeloceTerminalBlockEntity extends VeloceBlockEntity
        implements com.craftingveloce.block.entity.VeloceCraftCountSource {

    private static final org.slf4j.Logger TERMINAL_LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-terminal");

    private final List<WeakReference<ServerPlayer>> activeWatchingPlayers = new ArrayList<>();

    public VeloceTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_TERMINAL_BE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
    }

    public Direction getConnectionDirection() {
        return com.craftingveloce.block.VeloceTerminalBlock.facingOf(getBlockState());
    }

    /**
     * The inventory of the block the terminal faces.
     *
     * <p>Read through the standard NeoForge item handler. That is also the whole
     * Tom's Simple Storage fallback: Tom exposes his Inventory Connector through
     * this same capability, and that handler is a view over his entire network -
     * so a terminal pointing at his connector reads his whole storage without
     * referencing a single Tom class.
     */
    private net.neoforged.neoforge.items.IItemHandler adjacentHandler() {
        if (level == null) {
            return null;
        }
        Direction dir = getConnectionDirection();
        return level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                worldPosition.relative(dir), dir.getOpposite());
    }

    public void onPlayerOpenTerminal(ServerPlayer player) {
        setPlayerWatching(player, true);
        syncCountsToPlayer(player);
    }

    /**
     * Adds/removes a player from the list of refresh recipients.
     *
     * <p>Removing on GUI close matters: previously the entry disappeared only
     * on disconnect or on moving further away than 64 blocks, so a player
     * standing next to the terminal received the full network map every second
     * forever - even though they were not looking at anything.
     */
    public void setPlayerWatching(ServerPlayer player, boolean watching) {
        activeWatchingPlayers.removeIf(ref -> ref.get() == null || ref.get() == player);
        if (watching) {
            activeWatchingPlayers.add(new WeakReference<>(player));
        }
    }

    // NOTE: the dead craftableSnapshot() method along with two outdated
    // javadocs was removed from here. They described the "+N" numbers kept
    // in the background by VeloceCraftingCache - and that background NO
    // LONGER EXISTS (it was too expensive). What was left of it: a method
    // returning Map.of() and documentation describing behaviour that does not
    // exist, which is exactly what confuses people when reading the code and
    // when diagnosing.
    //
    // The "+N" numbers are produced exclusively on client request, for the
    // items visible on screen (RequestCraftableCountsPKT -> the method below).
    /**
     * Computes IMMEDIATELY "how many more can be crafted" for the given items.
     *
     * <p>Called when the client opens the terminal or changes page - the player
     * must see current numbers right away, not after a second. We compute the
     * whole batch with one shared time budget (25 ms), so ~45 visible items are
     * computed within a few milliseconds.
     *
     * <p>This is the only place where those numbers are produced. The attempt
     * to keep them "in reserve" for the whole network once choked the server
     * and was removed - that is why we compute only what the screen asks for.
     */
    @Override
    public com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult computeCraftableCounts(Collection<Item> items) {
        if (!(level instanceof ServerLevel sl) || items == null || items.isEmpty()) {
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), true);
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            // Network is not ready yet - we do NOT say "nothing can be done",
            // because the client would then erase the correct numbers.
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), false);
        }
        Set<Item> enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        if (enabled.isEmpty()) {
            // No crafter in the network = indeed nothing can be done.
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), true);
        }
        Map<Item, ResourceLocation> preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);

        // Someone is looking at the terminal - wake the background cache (see
        // point 0c in VeloceCraftingCache). Without this the cache would sleep,
        // because by default it only computes when a player has the GUI open.
        long start = System.nanoTime();
        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .countCraftableBatchResult(sl, net, items, enabled, preferred,
                        com.craftingveloce.crafting.VeloceAutoCrafter.DEFAULT_ESTIMATE_BUDGET_NS);

        com.craftingveloce.util.VeloceLog.Craft.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "instant craftable count for %d item(s) -> %d result(s) in %d ms "
                        + "(complete=%s, heat=%s)",
                items.size(), result.counts().size(),
                (System.nanoTime() - start) / 1_000_000L, result.complete(),
                result.heatAvailable() ? "furnace in network" : "no furnace");
        return result;
    }

    /** Tick of the last broadcast of counters to the watchers. */
    private long lastSyncTick = Long.MIN_VALUE;

    /**
     * A short snapshot of the network stock - without computing craftability.
     * Cheap (just reading the endpoint cache), enough for the green numbers.
     */
    public Map<Item, Long> snapshotStock() {
        if (!(level instanceof ServerLevel sl)) {
            return Map.of();
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        return net == null ? Map.of() : net.getAllItemCounts(sl);
    }

    public void syncCountsToAllWatchers() {
        if (level == null || level.isClientSide || activeWatchingPlayers.isEmpty()) return;
        // Stock only - computing craftability for all items every second choked
        // the server (see computeCraftableCounts). The yellow "+N" number
        // is added separately, on demand, only for the visible items.
        Map<Item, Long> counts = getAllStoredItemCounts();
        // WITHOUT the craftability numbers from the cache.
        //
        // There used to be an append of a ready snapshot from the background.
        // Now the background computes nothing: the "+N" numbers are produced
        // EXCLUSIVELY on client request, for the items visible on screen.
        // Appending the old snapshot only mixed fresh answers with outdated ones.
        Map<Item, Long> craftable = Map.of();
        Iterator<WeakReference<ServerPlayer>> it = activeWatchingPlayers.iterator();
        while (it.hasNext()) {
            ServerPlayer sp = it.next().get();
            if (sp == null || sp.hasDisconnected() || sp.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) > 64.0) {
                it.remove();
            } else {
                PacketDistributor.sendToPlayer(sp,
                        new SyncTerminalCountsPKT(counts, craftable));
            }
        }
    }

    public void syncCountsToPlayer(ServerPlayer player) {
        if (level == null || level.isClientSide) return;
        Map<Item, Long> counts = getAllStoredItemCounts();
        PacketDistributor.sendToPlayer(player,
                new SyncTerminalCountsPKT(counts, Map.of()));
    }

    /**
     * Collects all stored item counts from Refined Storage + Tom's Storage / Chests + Veloce Pipe Network.
     */
    public Map<Item, Long> getAllStoredItemCounts() {
        Map<Item, Long> counts = new HashMap<>();
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return counts;

        // 1. Veloce Pipe Network (authoritative source when connected to pipe network)
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net != null) {
            return net.getAllItemCounts(sl);
        }

        // 2. Fallback: Direct RS counts (if terminal is placed directly touching an RS block without pipes)
        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);
        if (com.craftingveloce.compat.VeloceMods.REFINED_STORAGE.isLoaded()
                && RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite())) {
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, targetPos, connDir.getOpposite());
            for (Map.Entry<Item, Long> entry : rsCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    counts.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
            return counts;
        }

        // 3. Fallback: whatever inventory the terminal faces, read through the
        // standard NeoForge item handler. This is ALSO the Tom's Simple Storage
        // path - Tom exposes his Inventory Connector through that same capability,
        // and the handler is a view over his entire network. So pointing the
        // terminal at his connector reads his whole storage, with no Tom type
        // referenced anywhere and no reflection into his private fields.
        collectAdjacentCounts(counts);

        return counts;
    }

    /** Adds the counts of the inventory the terminal faces, when there is one. */
    private void collectAdjacentCounts(Map<Item, Long> counts) {
        IItemHandler handler = adjacentHandler();
        if (handler == null) {
            return;
        }
        try {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                }
            }
        } catch (Throwable t) {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, t,
                    "reading the adjacent inventory at %s failed", worldPosition);
        }
    }

    /** Called by the ticker registered in {@link com.craftingveloce.block.VeloceTerminalBlock}. */
    public void updateServer() {
        // We do NOT call getStacks() here.
        //
        // getStacks() sets Tom's updateItems flag, which made Tom rebuild the
        // whole terminal item map on EVERY change of the network contents:
        // a full scan of all inventories + allocation of a TerminalItemStack per
        // stack + grouping and merging. That is a map which our mod reads
        // NOWHERE - our numbers come from VelocePipeNetwork.getAllItemCounts.
        //
        // Tom does not set that flag himself - his menu (StorageTerminalMenu)
        // does it when polling. We open our own screen, so that work was
        // performed solely for us and solely in vain.
        //
        // For the same reason we do not rely on slotCount/freeCount/beaconLevel.
        // (Tom's updateServer used to run here: it rebuilt his item map and
        // scanned for beacons 8 blocks out. We read neither, so it was pure work.)

        // NOTE: keeping the network's force-loads is NO LONGER called from here.
        //
        // There used to be `VeloceCraftingCache.get(net).tickIdle(sl)` under the
        // condition "once every 5 ticks". The effect: all chunk keeping depended
        // on whether a terminal happened to stand in the network - a network with
        // just a crafter and a furnace did not keep its chunks even once, so the
        // automation died when the player walked away. The driver is now the
        // level tick (see VeloceCraftingCache.tickAll) - it works independently
        // of blocks.

        // Periodically refresh active viewers
        if (level != null && !activeWatchingPlayers.isEmpty()) {
            long now = level.getGameTime();
            if (com.craftingveloce.util.VeloceTick.every(now, lastSyncTick, 20)) {
                lastSyncTick = now;
                syncCountsToAllWatchers();
            }
        }
    }

    /**
     * Prints full debug information about the connected network and resources to the player.
     */
    public void printDebugInfo(ServerPlayer player) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return;

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Debug] ==="));
        player.sendSystemMessage(Component.literal("§7Terminal Pos: §f[" + worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ() + "]"));

        // 1. Check Veloce Pipe Network
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net != null) {
            player.sendSystemMessage(Component.literal("§a[Pipe Network Found] §7ID: §e" + net.getId()));
            // "Nodes", not "Terminals": getTerminals() also returns crafters
            // and extractors, so the old label lied about what it counted.
            player.sendSystemMessage(Component.literal("  §7Pipes: §f" + net.getPipes().size() + "§7, Nodes (terminal/crafter/extractor): §f" + net.getTerminals().size()));
            player.sendSystemMessage(Component.literal("  §bTracked Chunks (" + net.getTrackedChunks().size() + "):"));
            for (ChunkPos cp : net.getTrackedChunks()) {
                boolean loaded = sl.isLoaded(cp.getWorldPosition());
                player.sendSystemMessage(Component.literal("    §7Chunk [" + cp.x + ", " + cp.z + "]: " + (loaded ? "§a[LOADED]" : "§c[UNLOADED]")));
            }
            player.sendSystemMessage(Component.literal("  §bConnected Endpoints (" + net.getEndpoints().size() + "):"));
            for (Map.Entry<BlockPos, ConnectedEndpointInfo> entry : net.getEndpoints().entrySet()) {
                BlockPos epPos = entry.getKey();
                ConnectedEndpointInfo ep = entry.getValue();
                boolean loaded = sl.isLoaded(epPos);
                player.sendSystemMessage(Component.literal("    §e" + ep.getType() + " §7at [" + epPos.getX() + ", " + epPos.getY() + ", " + epPos.getZ() + "] " + (loaded ? "§a[LOADED]" : "§c[UNLOADED]") + " §7(cached types: §f" + ep.getCachedCounts().size() + "§7)"));
            }
            Map<Item, Long> netCounts = net.getAllItemCounts(sl);
            player.sendSystemMessage(Component.literal("  §6Total Network Resources (" + netCounts.size() + " types):"));
            for (Map.Entry<Item, Long> itemEntry : netCounts.entrySet()) {
                player.sendSystemMessage(Component.literal("    §e" + itemEntry.getKey().getDescription().getString() + " §7x§a" + itemEntry.getValue()));
            }
        } else {
            player.sendSystemMessage(Component.literal("§c[No Veloce Pipe Network connected to this terminal]"));
        }

        // 2. Check Refined Storage
        boolean hasRS = RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite());
        player.sendSystemMessage(Component.literal("§7Refined Storage Network Detected: " + (hasRS ? "§aYES" : "§cNO")));
        if (hasRS) {
            Map<String, Long> rsItems = RefinedStorageHelper.queryRSNetwork(level, targetPos, connDir.getOpposite());
            player.sendSystemMessage(Component.literal("§b--- Direct Refined Storage Contents (" + rsItems.size() + " types) ---"));
            for (Map.Entry<String, Long> entry : rsItems.entrySet()) {
                player.sendSystemMessage(Component.literal("  §e" + entry.getKey() + " §7x§a" + entry.getValue()));
            }
        }

        // 3. The inventory on the facing side, through the standard item handler.
        player.sendSystemMessage(Component.literal("§b--- Adjacent inventory access ---"));
        IItemHandler handler = adjacentHandler();
        if (handler == null) {
            player.sendSystemMessage(Component.literal("§cNo inventory found on the facing side!"));
        } else {
            player.sendSystemMessage(Component.literal("§7Slots: " + handler.getSlots()));
        }
        player.sendSystemMessage(Component.literal("§6=============================="));
    }

    /**
     * Extracts an item from the connected network (Refined Storage or Tom's Storage / chests or Pipe Network).
     *
     * <p>If the item is not in the network, it tries to <b>auto-craft</b> it - see
     * {@link #craftItemFromNetwork}. Thanks to that the player can pull planks
     * with auto-crafting enabled for planks, even if nobody produced them
     * earlier: crafting is instant and does not pass through any physical
     * intermediate block.
     */
    public ItemStack extractItemFromConnectedNetwork(ItemStack requested, int count) {
        return extractWithReason(requested, count, true).stack();
    }

    /**
     * @param allowCrafting whether the item may be produced by auto-crafting when it is missing from the network
     */
    public ItemStack extractItemFromConnectedNetwork(ItemStack requested, int count, boolean allowCrafting) {
        return extractWithReason(requested, count, allowCrafting).stack();
    }

    /**
     * The pull result together with the REASON for failure.
     *
     * <p><b>Why.</b> Previously the player only got a generic "Item not in
     * network: X" - with no distinction between "there is no recipe", "an
     * ingredient is missing", "machine has no power" and "the plan did not fit
     * in the budget". The report "the GUI shows I can, but I cannot craft" then
     * cannot be solved other than by reading logs.
     *
     * @param reason language key of the reason (empty = no reason, e.g. the item
     *               simply is not in the network), {@code detail} is its complement
     */
    public record PullResult(ItemStack stack, String reason, String detail, String hint) {
        static PullResult ok(ItemStack stack) {
            return new PullResult(stack, "", "", "");
        }

        static PullResult empty() {
            return new PullResult(ItemStack.EMPTY, "", "", "");
        }
    }

    /**
     * @param allowCrafting whether the item may be produced by auto-crafting when it is missing from the network
     */
    public PullResult extractWithReason(ItemStack requested, int count, boolean allowCrafting) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl) || requested.isEmpty() || count <= 0) {
            return PullResult.empty();
        }

        // 1. Veloce Pipe Network extraction (handles live and on-demand unloaded chunk ticketing)
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        com.craftingveloce.crafting.VeloceCraftTrace.log(
                "terminal @%s: looking for %dx %s (%s), network=%s, auto-crafting=%s",
                worldPosition, count, requested.getHoverName().getString(),
                com.craftingveloce.crafting.VeloceCraftTrace.id(requested.getItem()),
                net == null ? "NONE (terminal not connected?)" : net.getId(),
                allowCrafting);
        com.craftingveloce.util.VeloceLog.Craft.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "player requested %sx %s from terminal at %s",
                count, requested.getItem(), worldPosition);
        if (net != null) {
            com.craftingveloce.util.VeloceLog.Network.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "network found: %d endpoint(s) for terminal at %s",
                    net.getEndpoints().size(), worldPosition);
            // The player just pulled something - speed up the refresh of numbers.

            Item extractProxy = com.craftingveloce.util.VelocePotionMapper.getProxy(requested);
            ItemStack extracted = net.extractItem(sl, extractProxy, count);
            extracted = com.craftingveloce.util.VelocePotionMapper.toRealPotion(extracted);
            if (!extracted.isEmpty()) {
                com.craftingveloce.util.VeloceLog.Craft.success(
                        com.craftingveloce.util.VeloceLog.Side.SERVER,
                        "took %sx %s from stock", extracted.getCount(), extracted.getItem());
                syncCountsToAllWatchers();
                return PullResult.ok(extracted);
            }
            com.craftingveloce.util.VeloceLog.Craft.why(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "%s not in stock, trying auto-crafting", requested.getItem());
            com.craftingveloce.crafting.VeloceCraftTrace.log(
                    "stock: not in network - trying auto-crafting");
            // 1b. Not in the network - try auto-crafting (if enabled for this item).
            if (allowCrafting) {
                PullResult crafted = craftItemFromNetwork(sl, net, requested, count);
                if (!crafted.stack().isEmpty()) {
                    // Crafting changed the stock. The client will ask for new
                    // numbers for the visible page on its own after receiving
                    // the new stock - there is nothing to invalidate here,
                    // because nothing is cached.
                    syncCountsToAllWatchers();
                    return crafted;
                }
                com.craftingveloce.crafting.VeloceCraftTrace.log(
                        "auto-crafting did not yield the item: reason=%s detail=%s",
                        crafted.reason(), crafted.detail());
                // Crafting failed - we pass the REASON on.
                if (!crafted.reason().isEmpty()) {
                    return crafted;
                }
            }
            com.craftingveloce.crafting.VeloceCraftTrace.log(
                    "network exists, but the item is not in stock and auto-crafting yielded nothing");
            return PullResult.empty();
        }

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        // 2. Try Refined Storage first
        if (com.craftingveloce.compat.VeloceMods.REFINED_STORAGE.isLoaded()
                && RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite())) {
            ItemStack rsExtracted = RefinedStorageHelper.extractItem(level, targetPos, connDir.getOpposite(), requested, count);
            if (!rsExtracted.isEmpty()) {
                syncCountsToAllWatchers();
                return PullResult.ok(rsExtracted);
            }
        }

        com.craftingveloce.crafting.VeloceCraftTrace.log(
                "terminal without a pipe network - trying chest/RS at %s", targetPos);
        // 3. Try Tom's Storage / connected chests
        try {
            ItemStack pulled = extractFromAdjacent(requested, count);
            if (!pulled.isEmpty()) {
                syncCountsToAllWatchers();
                return PullResult.ok(pulled);
            }
        } catch (Throwable t) {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, t,
                    "pulling %s from Tom's Storage at %s failed", requested, worldPosition);
        }

        return PullResult.empty();
    }

    /**
     * Takes up to {@code count} of {@code requested} from the inventory the
     * terminal faces.
     *
     * <p>Take-exactly-what-was-given: the result contains only what the handler
     * actually released, so a partial extraction cannot turn into a duplication.
     */
    private ItemStack extractFromAdjacent(ItemStack requested, int count) {
        IItemHandler handler = adjacentHandler();
        if (handler == null || requested.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        int remaining = count;
        try {
            for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, requested)) {
                    continue;
                }
                ItemStack taken = handler.extractItem(slot, remaining, false);
                if (taken.isEmpty()) {
                    continue;
                }
                if (result.isEmpty()) {
                    result = taken.copy();
                } else {
                    result.grow(taken.getCount());
                }
                remaining -= taken.getCount();
            }
        } catch (Throwable t) {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, t,
                    "extracting %s from the adjacent inventory at %s failed", requested, worldPosition);
        }
        return result;
    }

    /**
     * Inserts the player's items into the network (the "arrow" slot in the terminal GUI).
     *
     * <p><b>The server reads the player's state itself and takes the items
     * itself.</b> This is crucial for correctness: the previous version received
     * a stack in a packet and threw it into the network without taking anything
     * from the player - the item existed simultaneously in the barrel and in the
     * inventory (duplication). Now we insert EXACTLY as much as we managed to
     * take, and we take EXACTLY as much as we managed to insert.
     *
     * <p><b>What we do not touch:</b> crafter buffers (the planner's working
     * memory, not storage) and extractors (they only output). See
     * {@link VelocePipeNetwork#insertIntoStorage}.
     *
     * @param mode {@link com.craftingveloce.network.TerminalStoreItemPKT#MODE_CURSOR},
     *             {@code MODE_INVENTORY} (without the hotbar) or {@code MODE_EVERYTHING}
     */
    public void storeFromPlayer(ServerPlayer player, int mode) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return;
        }

        if (!hasAnythingToStore(player, mode)) {
            // Nothing to deposit - no message. Without this an empty cursor
            // (or an empty inventory) ended with the "network full" message,
            // which was simply a lie.
            return;
        }
        if (VeloceChunkLoader.isFrozen()) {
            // During world save we deliberately do NOT force-load chunks, so
            // inserting returns the whole stack. This is not "network full" -
            // it is "try again in a moment".
            player.displayClientMessage(
                    Component.translatable("gui.craftingveloce.terminal.storeBusy")
                            .withStyle(ChatFormatting.YELLOW),
                    true);
            return;
        }

        int moved = 0;
        if (mode == com.craftingveloce.network.TerminalStoreItemPKT.MODE_CURSOR) {
            // inventoryMenu, not containerMenu: the cursor sync goes precisely
            // through that menu (just like in TerminalPullItemPKT.resyncInventories).
            // The terminal screen does not open a menu on the server side, so
            // containerMenu is exactly inventoryMenu - but we do not guess.
            moved += storeStack(sl, net, player.inventoryMenu);
        } else {
            boolean includeHotbar = mode == com.craftingveloce.network.TerminalStoreItemPKT.MODE_EVERYTHING;
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                // Slots 0..8 are the hotbar - a normal shift skips them.
                if (!includeHotbar && i < 9) {
                    continue;
                }
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) {
                    continue;
                }
                ItemStack leftover = net.insertIntoStorage(sl, st.copy());
                int stored = st.getCount() - leftover.getCount();
                if (stored > 0) {
                    st.shrink(stored);
                    if (st.isEmpty()) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                    moved += stored;
                }
            }
        }

        if (moved <= 0) {
            // Nothing went in - we tell the player about it.
            //
            // The decision belongs to the server, because the client does not
            // know the contents of individual slots nor whether its data is
            // fresh (the client NO LONGER blocks this action - see
            // VeloceTerminalScreen). The player MUST therefore get a reason,
            // otherwise it looks like a broken button.
            //
            // IMPORTANT: "nothing went in" does NOT yet mean "network full".
            // So we ask the cache about the capacity FOR THIS SPECIFIC ITEM -
            // and only when it is a hard 0 for all of its types do we say
            // "full". Otherwise we say "try again in a moment", because it may
            // be e.g. a refusal due to the chunk loading budget or an ongoing
            // world save.
            //
            // NOTE: we use the IMPORTED names (Component, ChatFormatting),
            // and not `net.minecraft...` - in this method there is a local
            // variable `net` (the pipe network) which SHADOWS the `net`
            // package name. The code `net.minecraft.network.chat.Component`
            // compiled as a reference to the `minecraft` field of the `net`
            // variable and did not work.
            String reason = nothingStoredReason(sl, net, player, mode);
            player.displayClientMessage(
                    Component.translatable(reason)
                            .withStyle("gui.craftingveloce.terminal.storeFull".equals(reason)
                                    ? ChatFormatting.RED : ChatFormatting.YELLOW),
                    true);
            return;
        }
        // We send the changes back so the cursor and the inventory match on the client.
        com.craftingveloce.network.TerminalPullItemPKT.resyncInventories(player);
        syncCountsToAllWatchers();
    }

    /**
     * Whether the player has anything to deposit in mode {@code mode}.
     *
     * <p>We check this BEFORE inserting, to distinguish "there was nothing to
     * deposit" (no message) from "depositing failed" (a message). Previously
     * both cases ended with the text "network full".
     */
    private boolean hasAnythingToStore(ServerPlayer player, int mode) {
        return !stacksToStore(player, mode).isEmpty();
    }

    /**
     * The stacks that mode {@code mode} would try to deposit.
     *
     * <p>The single place deciding WHAT is a candidate for depositing - used
     * both when checking "is there anything to deposit" and when determining
     * the reason for refusal. Previously those two places computed independently
     * and could drift apart.
     */
    private List<ItemStack> stacksToStore(ServerPlayer player, int mode) {
        List<ItemStack> out = new ArrayList<>();
        if (mode == com.craftingveloce.network.TerminalStoreItemPKT.MODE_CURSOR) {
            ItemStack carried = player.inventoryMenu.getCarried();
            if (!carried.isEmpty()) {
                out.add(carried);
            }
            return out;
        }
        boolean includeHotbar = mode == com.craftingveloce.network.TerminalStoreItemPKT.MODE_EVERYTHING;
        var inv = player.getInventory();
        // Slots 0..8 are the hotbar - a normal shift skips them.
        for (int i = includeHotbar ? 0 : 9; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty()) {
                out.add(st);
            }
        }
        return out;
    }

    /**
     * Why nothing went in - a ready translation key.
     *
     * <p>We distinguish TWO reasons, because they have different consequences
     * for the player:
     * <ul>
     *   <li>{@code storeFull} - the network's capacity for EACH of their items
     *       is a hard 0. The network really is full, there is no point trying.</li>
     *   <li>{@code storeBusy} - the capacity is positive or UNKNOWN
     *       ({@code -1}). That is, we cannot honestly say "full" -
     *       most often it is a refusal due to the chunk loading budget or an
     *       ongoing world save. It is worth trying again in a moment.</li>
     * </ul>
     *
     * <p>Without this distinction every failed attempt - including a temporary
     * one - ended with the text "network full", which is a lie that sent the
     * player looking for a non-existent capacity problem.
     */
    private String nothingStoredReason(ServerLevel sl, VelocePipeNetwork net,
                                       ServerPlayer player, int mode) {
        for (ItemStack st : stacksToStore(player, mode)) {
            if (net.capacityFor(sl, st.getItem()) != 0) {
                return "gui.craftingveloce.terminal.storeBusy";
            }
        }
        return "gui.craftingveloce.terminal.storeFull";
    }

    /**
     * Takes a stack from the player's cursor and inserts it into the network.
     *
     * @return how many items were moved
     */
    private int storeStack(ServerLevel sl, VelocePipeNetwork net,
                           net.minecraft.world.inventory.AbstractContainerMenu menu) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return 0;
        }
        ItemStack leftover = net.insertIntoStorage(sl, carried.copy());
        int stored = carried.getCount() - leftover.getCount();
        if (stored <= 0) {
            return 0;
        }
        carried.shrink(stored);
        if (carried.isEmpty()) {
            menu.setCarried(ItemStack.EMPTY);
        }
        return stored;
    }

    /**
     * Tries to auto-craft the item and hand it over immediately.
     *
     * <p>Conditions:
     * <ol>
     *   <li>The network must contain an auto-crafter with the recipe for this
     *       item <b>enabled</b> (see {@code VeloceCraftingTableBlockEntity}).
     *       If there is no enabled crafter for the item - we do not craft.</li>
     *   <li>Crafting is instant: the engine pulls the ingredients from the
     *       network and inserts the result. Nothing goes through a physical
     *       intermediate block.</li>
     * </ol>
     *
     * @return the crafted stack or {@link ItemStack#EMPTY}
     */
    private PullResult craftItemFromNetwork(ServerLevel sl, VelocePipeNetwork net,
                                            ItemStack requested, int count) {
        Item item = com.craftingveloce.util.VelocePotionMapper.getProxy(requested);

        // Terminal/JEI parity starts here: the item the player clicked and the item the
        // planner is asked about are NOT always the same (potions are planned in the
        // proxy domain). Printing both makes a mismatch obvious instead of looking
        // like "the terminal cannot craft this".
        TERMINAL_LOG.debug("[VELOCE-DEBUG] terminal craft request: {}x {} -> plan target {} "
                        + "(proxy={}, network={})",
                count, requested.getHoverName().getString(), item,
                com.craftingveloce.util.VelocePotionMapper.isProxy(item),
                net == null ? "none" : net.getId());

        // DIAGNOSTIC DUMP BEFORE ANY GATE.
        //
        // Thanks to this the trace answers also when the craft does NOT reach
        // planning (e.g. the item is not in the craftable set) - the player
        // reported exactly such a case as "I cannot craft it".
        if (com.craftingveloce.crafting.VeloceCraftTrace.active()) {
            com.craftingveloce.crafting.VeloceCraftTrace.log(
                    "request from terminal: %dx %s (%s)", count,
                    requested.getHoverName().getString(),
                    com.craftingveloce.crafting.VeloceCraftTrace.id(item));
            com.craftingveloce.crafting.VeloceCraftTrace.dumpEnvironment(sl, net, item);
            com.craftingveloce.crafting.VeloceCraftTrace.dumpRecipes(sl, net, item);
        }

        // WHETHER CRAFTING IS ALLOWED - decided by the SET OF ENABLED ITEMS.
        //
        // The BUG this fixes (player report: "I cannot craft anything from the
        // veloce furnace either"): the gate asked about a CRAFTER in the network
        // (findEnabledCrafter), and not about whether the network is able to
        // make this item. Ever since processing powers are modules (furnace,
        // Create, Mekanism...), a crafter is no longer needed for furnace
        // recipes nor for module machines - yet the GUI showed numbers from a
        // set computed exactly that way. Effect: the GUI said "you can", and the
        // craft ended with "disabled".
        var enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        if (!enabled.contains(item)) {
            // The reason for the player and for the trace: WHY it is not in the set.
            var reason = com.craftingveloce.crafting.VeloceCraftingRegistry
                    .whyNotCraftable(sl, net, item);
            com.craftingveloce.crafting.VeloceCraftTrace.log(
                    "item is NOT in the craftable set: %s (detail: %s)",
                    reason.reasonKey(), reason.detail());
            return new PullResult(ItemStack.EMPTY, reason.reasonKey(), reason.detail(), "");
        }
        var preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);

        var buffers = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getBuffers(sl, net);
        // The block position = the emergency drop location, in case the network were full.
        var ctx = new com.craftingveloce.crafting.VeloceAutoCrafter.Context(
                sl, net, enabled, preferred, null, buffers, this.getBlockPos());

        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, count, ctx);
        if (!result.success()) {
            // Reason from the planner: noBase + the name of the missing
            // ingredient, tooComplex or extract. Without this the player only
            // saw a generic "item not in network".
            return new PullResult(ItemStack.EMPTY, result.reason(), result.detail(), result.hint());
        }

        // The crafting result goes first to the crafter's buffer (working memory),
        // and only then to the network endpoints. The buffer is NOT an endpoint,
        // so net.extractItem() does not see it - that is why we first try to pull
        // from the buffers, and only as a fallback from the network.
        ItemStack fromBuffer = extractFromBuffers(buffers, item, count);
        if (!fromBuffer.isEmpty()) {
            return PullResult.ok(com.craftingveloce.util.VelocePotionMapper.toRealPotion(fromBuffer));
        }
        return PullResult.ok(com.craftingveloce.util.VelocePotionMapper.toRealPotion(net.extractItem(sl, item, count)));
    }

    /**
     * Pulls the item from the auto-crafter buffers.
     *
     * <p>The buffer is not a network endpoint, so the standard extraction skips
     * it. Without this, items crafted on the spot would stay in the crafter
     * block instead of reaching the player.
     */
    private static ItemStack extractFromBuffers(
            java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
            Item item, int count) {
        ItemStack result = ItemStack.EMPTY;
        int remaining = count;
        for (var buf : buffers) {
            if (remaining <= 0) {
                break;
            }
            for (int slot = 0; slot < buf.getContainerSize() && remaining > 0; slot++) {
                ItemStack inSlot = buf.getItem(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                ItemStack taken = buf.removeItem(slot, take);
                if (taken.isEmpty()) {
                    continue;
                }
                if (result.isEmpty()) {
                    result = taken.copy();
                } else {
                    result.grow(taken.getCount());
                }
                remaining -= taken.getCount();
            }
        }
        return result;
    }
}
