package com.craftingveloce.block.entity;


import com.craftingveloce.crafting.VeloceCraftingRegistry;
import com.craftingveloce.crafting.VeloceFlowTracker;
import com.craftingveloce.crafting.VeloceHeatSources;
import com.craftingveloce.crafting.VeloceRecipeRegistry;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.OpenControllerScreenPKT;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Block entity of the Veloce Controller.
 *
 * <p>It collects and sends to the client the full picture of the network needed by the GUI:
 * <ul>
 *   <li>{@code stock} - how many pieces of every item are physically in the network</li>
 *   <li>{@code craftable} - which items have a recipe executable without energy</li>
 *   <li>{@code craftingEnabled} - for which ones auto-crafting is enabled
 *       (i.e. the crafter can make them, even without items in stock)</li>
 *   <li>{@code hotbar} - what the player has in the hotbar (for colouring the icons)</li>
 * </ul>
 */
public class VeloceControllerBlockEntity extends BlockEntity
        implements VeloceCraftCountSource {

    /**
     * How close a player must be for the controller to handle their request
     * for the flow rate. Without this limit any player could order server work
     * for any position in the world.
     */
    public static final double MAX_FLOW_REQUEST_DISTANCE = 64.0;

    /**
     * The flow meter. It lives together with the controller and measures the
     * network the controller is CURRENTLY connected to.
     */
    private final VeloceFlowTracker flow = new VeloceFlowTracker();
    /** The stock difference against what has already gone to the clients (see sendFlowTo). */
    private final com.craftingveloce.crafting.VeloceStockDeltas stockDeltas =
            new com.craftingveloce.crafting.VeloceStockDeltas();

    /** The network we collect snapshots for - to detect the controller being moved. */
    private java.util.UUID sampledNetworkId;

    public VeloceControllerBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CONTROLLER_BE.get(), pos, state);
    }

    /**
     * One tick of the controller: a stock snapshot (once every 5 s) and nothing more.
     *
     * <p>We do not send anything to players here - the client asks for the rate with
     * a separate packet (see {@link com.craftingveloce.network.ControllerFlowRequestPKT}).
     * Thanks to that the controller does not have to remember who is looking, and
     * there is nothing to lose when the client leaves the game or teleports.
     */
    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        long now = sl.getGameTime();
        if (!flow.due(now)) {
            return;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            // A controller without a network has nothing to measure. We zero the
            // measurement so that after reconnecting we do not mix in history from
            // another network.
            if (sampledNetworkId != null) {
                flow.reset();
                sampledNetworkId = null;
            }
            return;
        }
        // A different network than before (the controller was moved) - the old
        // history is meaningless and would only falsify the rate.
        if (sampledNetworkId != null && !sampledNetworkId.equals(net.getId())) {
            flow.reset();
        }
        sampledNetworkId = net.getId();
        flow.sample(now, net.getAllItemCounts(sl));
    }

    /**
     * The "how many can still be made" numbers for the visible page of the controller.
     *
     * <p>The same logic as in the terminal (and the same shared interface), because
     * the controller is to show EXACTLY the same two numbers as the terminal -
     * and not its own, simplified approximation.
     */
    @Override
    public com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult computeCraftableCounts(
            java.util.Collection<Item> items) {
        if (!(level instanceof ServerLevel sl) || items == null || items.isEmpty()) {
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), true);
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            // The network is not ready yet - we do NOT say "nothing can be made",
            // because the client would then wipe the correct numbers.
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), false);
        }
        Set<Item> enabled = VeloceCraftingRegistry.getAllEnabledItems(sl, net);
        if (enabled.isEmpty()) {
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), true);
        }
        java.util.Map<Item, ResourceLocation> preferred =
                VeloceCraftingRegistry.getPreferredRecipes(sl, net);
        long start = System.nanoTime();
        var result = com.craftingveloce.crafting.VeloceAutoCrafter.countCraftableBatchResult(
                sl, net, items, enabled, preferred,
                com.craftingveloce.crafting.VeloceAutoCrafter.DEFAULT_ESTIMATE_BUDGET_NS);
        // The same log as the terminal - without it there is no way to tell whether
        // the controller received the numbers at all (and the player just reported
        // exactly that).
        com.craftingveloce.util.VeloceLog.Craft.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "controller craftable count for %d item(s) -> %d result(s) in %d ms "
                        + "(complete=%s, heat=%s)",
                items.size(), result.counts().size(),
                (System.nanoTime() - start) / 1_000_000L, result.complete(),
                result.heatAvailable() ? "furnace in network" : "no furnace");
        return result;
    }

    /**
     * Sends the player the flow rate AND the stock CHANGE (the answer to the request).
     *
     * <p>The stock travels together with the rate, because the controller is to
     * refresh just like the terminal: without it, taking an item out of a chest with
     * the GUI open changed neither the number nor the colour of the icon.
     *
     * <p><b>Changes only.</b> The request comes once a second, and the stock almost
     * never changes - sending the whole picture of the network every time would mean
     * thousands of entries per second in a large network (the larger the network, the
     * more traffic for no benefit). The differences are computed by
     * {@link com.craftingveloce.crafting.VeloceStockDeltas}, and once a minute a full
     * dump is sent as a safeguard against drift.
     */
    public void sendFlowTo(ServerPlayer player) {
        Map<Item, Long> stock = Map.of();
        long gameTime = 0L;
        ServerLevel serverLevel = level instanceof ServerLevel sl ? sl : null;
        if (serverLevel != null) {
            gameTime = serverLevel.getGameTime();
            VelocePipeNetwork net = VelocePipeNetworkManager.get(serverLevel)
                    .getNetworkForTerminal(serverLevel, worldPosition);
            if (net != null) {
                stock = net.getAllItemCounts(serverLevel);
            }
        }
        com.craftingveloce.crafting.VeloceStockDeltas.Delta delta =
                stockDeltas.diff(stock, gameTime);

        // "Which mods could make this" - for the tooltip of an item the GUI shows as RED.
        //
        // Only for the items this delta actually MENTIONS, and memoised, because the answer
        // costs a recipe lookup per module: without the memo a full dump of a large network
        // would re-derive the same strings on every sync.
        Map<Item, String> madeBy = new java.util.HashMap<>();
        if (serverLevel != null) {
            for (Item changedItem : delta.changed().keySet()) {
                ServerLevel lookupLevel = serverLevel;
                madeBy.put(changedItem, madeByMemo.computeIfAbsent(changedItem,
                        it -> com.craftingveloce.crafting.VeloceCraftingRegistry
                                .modsThatCanMake(lookupLevel, it)));
            }
        }

        PacketDistributor.sendToPlayer(player, new com.craftingveloce.network.SyncControllerFlowPKT(
                worldPosition, delta.changed(), delta.removed(), flow.steadyRates(), madeBy,
                delta.full()));
    }

    /**
     * Memo for {@link #sendFlowTo}: item -&gt; "create, mekanism".
     *
     * <p>An item's set of possible makers is a property of the mods that are installed, not of
     * the network, so it cannot change while the game runs - which is exactly what makes a
     * permanent memo safe here and not merely a speed-up.
     */
    private final Map<Item, String> madeByMemo = new java.util.HashMap<>();

    /** Collects the current network state and sends it to the player's GUI. */
    public void syncToPlayer(ServerPlayer player) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);

        // A MISSING NETWORK MUST NOT BE PASSED OVER IN SILENCE.
        //
        // The BUG this was hiding: the controller was not recognized as a node
        // (see VeloceNodeBlocks), so `net` was ALWAYS null here. The controller
        // got an empty stock and an empty set of items with auto-crafting enabled
        // - and showed "auto-crafting disabled" for ALL items, even though the
        // crafter in the network had them enabled. Without this log it looked like
        // a bug in auto-crafting itself, and not in the controller's connection.
        if (net == null) {
            com.craftingveloce.util.VeloceLog.Block.failure(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "controller at %s is NOT connected to any pipe network "
                            + "(stock and auto-crafting will show as empty) - "
                            + "place a pipe directly next to it",
                    worldPosition);
        }

        Map<Item, Long> stock = net == null ? Map.of() : net.getAllItemCounts(sl);
        Set<Item> craftingEnabled = net == null
                ? Set.of()
                : VeloceCraftingRegistry.getAllEnabledItems(sl, net);

        // FURNACE: the furnace's recipes are usable ONLY when a POWERED furnace is
        // in the network. The client uses this for ONE thing: a yellow background
        // for items that can be smelted. "Any furnace without fuel is present" is no
        // longer needed - that information used to be carried by the removed tooltip
        // texts.
        Set<Item> furnaceCraftable = VeloceRecipeRegistry.getAllFurnaceCraftableItems(sl);
        boolean furnacePowered = net != null && VeloceHeatSources.hasPower(sl, net);

        // The "crafting or furnace" preference - from the network, so the whole
        // network sees it, not just one controller. Instead of the hotbar (removed
        // on request): the player did not want the "in hotbar" information on the icons.
        Set<Item> furnacePreferred = net == null ? Set.of() : net.getFurnacePreferred();

        PacketDistributor.sendToPlayer(player, new OpenControllerScreenPKT(
                this.getBlockPos(), stock, craftingEnabled,
                furnaceCraftable, furnacePowered, furnacePreferred));

        // The flow rate goes in a separate, lightweight packet. We send it right
        // away so that the player does not wait a second for the first numbers -
        // and then the client asks on its own for as long as it has the GUI open.
        sendFlowTo(player);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // The controller does not store state - it is only a view onto the network.
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    }

    /** Helper: the item's id (for NBT/debugging). */
    public static ResourceLocation idOf(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
    }
}
