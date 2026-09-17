package com.craftingveloce.block.entity;

import com.craftingveloce.util.VeloceLog;
import com.craftingveloce.crafting.VeloceRecipeRegistry;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.OpenCraftingTableScreenPKT;
import com.craftingveloce.network.SyncCraftingTableStatePKT;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Veloce auto-crafter.
 *
 * <p>It stores:
 * <ul>
 *   <li>{@code enabledItems} - the items for which auto-crafting is enabled.
 *       The player enables them in the GUI (by clicking the item).</li>
 *   <li>{@code preferredRecipes} - for items with multiple recipes: which
 *       recipe should be used first. Chosen with shift+scroll in the GUI.</li>
 * </ul>
 *
 * <p>Note: this block does NOT have its own inventory - crafting happens
 * directly on the network (see {@code VeloceAutoCrafter}). Thanks to that
 * crafting is instant and nothing passes through a physical block.
 */
public class VeloceCraftingTableBlockEntity extends BlockEntity {

    /**
     * The items for which auto-crafting is <b>disabled</b>.
     *
     * <p>The model is opt-out: a newly placed crafter has everything enabled,
     * and the player consciously disables what they do not want. Thanks to that
     * there is no need to click hundreds of items for the crafter to start
     * working, and the exception list is short and fits in NBT.
     *
     * <p>Watch out for a trap: a freshly loaded block without saved exceptions
     * means "everything enabled". If the default value were ever changed to
     * "everything disabled", old worlds would suddenly stop crafting.
     */
    private Set<Item> disabledItems = new HashSet<>();

    /**
     * The migration from the old (opt-in) format is waiting to be performed.
     *
     * <p><b>The BUG this fixes.</b> The migration computed the exceptions as
     * "everything craftable MINUS the list of enabled ones from the old save" and
     * did it in {@code loadAdditional}. But there {@code level} is STILL NULL -
     * Minecraft creates the block entity and calls {@code loadAdditional}, and
     * assigns the level only afterwards ({@code setLevel}). And
     * {@code getAllCraftableItems(null)} returns an EMPTY set (because {@code null}
     * is not {@code instanceof ServerLevel}), so the loop had nothing to iterate
     * over and {@code disabledItems} stayed empty.
     *
     * <p>Effect: an old world saved in the opt-in format got EVERYTHING ENABLED -
     * that is, exactly the regression the comment next to {@link #disabledItems}
     * warns about.
     *
     * <p>That is why we only remember the list from the old save, and recompute it
     * later - when the level and the recipes are already available.
     */
    @Nullable
    private Set<Item> pendingOptInMigration;

    /**
     * Finishes the migration from the old format as soon as it can be computed.
     *
     * <p>Called from {@link #setLevel} (the level is already assigned) AND on
     * every query for the exceptions - if the recipes were not ready yet at the
     * moment the chunk was loaded, the attempt will come back on first use
     * instead of being lost.
     */
    private void finishOptInMigration() {
        if (pendingOptInMigration == null || !(level instanceof ServerLevel sl)) {
            return;
        }
        Set<Item> craftable = VeloceRecipeRegistry.getAllCraftableItems(sl);
        if (craftable.isEmpty()) {
            // Recipes not loaded yet - we leave the flag and will try again on
            // the next query. Clearing it now would mean saving an empty
            // exception list, that is "everything enabled".
            return;
        }
        for (Item candidate : craftable) {
            if (!pendingOptInMigration.contains(candidate)) {
                disabledItems.add(candidate);
            }
        }
        pendingOptInMigration = null;
        setChanged();
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "migrated opt-in crafter at %s: %d recipe(s) disabled (was %d enabled)",
                worldPosition, disabledItems.size(), 0);
    }


    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        // The level is available from now on - this is the first opportunity to
        // finish the migration from the old format (see finishOptInMigration).
        finishOptInMigration();
    }

    /** Item -> recipe id that has priority during auto-crafting. */
    private Map<Item, ResourceLocation> preferredRecipes = new HashMap<>();

    /**
     * The production surplus buffer - normally available to the whole network.
     * When crafting yields more than the player pulled (e.g. 1 log -> 4 planks,
     * but they wanted 1), the rest lands here and can be pulled from the terminal.
     */
    private final com.craftingveloce.inventory.VeloceCraftingBuffer buffer =
            new com.craftingveloce.inventory.VeloceCraftingBuffer() {
                @Override
                public void setChanged() {
                    VeloceCraftingTableBlockEntity.this.setChanged();
                }
            };

    public com.craftingveloce.inventory.VeloceCraftingBuffer getBuffer() {
        return buffer;
    }

    /**
     * Whether this block entity is the CRAFTER for the network.
     *
     * <p><b>The BLOCK TYPE decides.</b> The Veloce Integrale frame no longer even
     * has its own block entity - a right click with the right block replaces it
     * with a real machine (see {@code VeloceIntegraleConversions}), and with the
     * crafting table only when the player inserts a crafting table. So the crafter
     * is exclusively the crafting table block.
     */
    public boolean isActiveCrafter() {
        return getBlockState().getBlock()
                instanceof com.craftingveloce.block.VeloceCraftingTableBlock;
    }

    public VeloceCraftingTableBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(), pos, state);
    }

    /** The disabled items (exceptions to the "everything enabled" rule). */
    public Set<Item> getDisabledItems() {
        // Retry the migration: if the recipes were not ready yet when the chunk
        // was loaded, we finish it now - this call already has the level.
        finishOptInMigration();
        return disabledItems;
    }

    /**
     * Whether auto-crafting for this item is enabled.
     * Default YES - only explicit exceptions are disabled.
     */
    public boolean isEnabled(Item item) {
        return !disabledItems.contains(item);
    }

    public Map<Item, ResourceLocation> getPreferredRecipes() {
        return preferredRecipes;
    }

    /** The preferred recipe for the item (may be null = the first available one). */
    @Nullable
    public ResourceLocation getPreferredRecipe(Item item) {
        return preferredRecipes.get(item);
    }

    /**
     * Toggles auto-crafting for the item. Disabling also clears the preference,
     * so as not to be left with state from an item that is no longer crafted.
     */
    public void toggleItem(Item item) {
        if (disabledItems.contains(item)) {
            disabledItems.remove(item);
        } else {
            disabledItems.add(item);
            preferredRecipes.remove(item);
        }
        setChanged();
        markUpdated();
    }

    /**
     * Sets the preferred recipe for the item (shift+scroll in the GUI).
     * It advances to the next recipe from the list provided by the server.
     */
    public void cyclePreferredRecipe(Item item, java.util.List<ResourceLocation> available) {
        if (available == null || available.isEmpty()) {
            return;
        }
        ResourceLocation current = preferredRecipes.get(item);
        int idx = current == null ? -1 : available.indexOf(current);
        int next = (idx + 1) % available.size();
        preferredRecipes.put(item, available.get(next));
        setChanged();
        markUpdated();
    }

    /** Sets a specific recipe as preferred. */
    public void setPreferredRecipe(Item item, ResourceLocation recipeId) {
        if (recipeId == null) {
            preferredRecipes.remove(item);
        } else {
            preferredRecipes.put(item, recipeId);
        }
        setChanged();
        markUpdated();
    }

    /**
     * Crafters waiting to have their state broadcast.
     *
     * <p><b>Why a queue.</b> A right click on a tab toggles a WHOLE group of
     * items, and the client then sends one packet per item (hundreds for a large
     * tab). Each such packet ended with an immediate broadcast of
     * {@code SyncCraftingTableStatePKT} to ALL players in the world, with all the
     * disabled items inside. Hundreds of broadcasts of a dozen-odd kilobytes in a
     * fraction of a second is a real stutter.
     *
     * <p>Now we only collect "this crafter changed" and send it ONCE per tick
     * (see {@link #flushPendingSyncs}). A weak set, so as not to hold the block
     * entity strongly.
     */
    private static final java.util.Set<VeloceCraftingTableBlockEntity> PENDING_SYNC =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Sends the pending crafter states. Called from the server tick. */
    public static void flushPendingSyncs(ServerLevel level) {
        if (PENDING_SYNC.isEmpty()) {
            return;
        }
        java.util.Iterator<VeloceCraftingTableBlockEntity> it = PENDING_SYNC.iterator();
        while (it.hasNext()) {
            VeloceCraftingTableBlockEntity be = it.next();

            // The BUG that was here: `it.remove()` ran BEFORE the dimension check,
            // so the first ticking world ate (and discarded) entries belonging to
            // ALL the others. Toggling a recipe in a crafter standing in the
            // Nether (when the Overworld ticks first) therefore did not send
            // SyncCraftingTableStatePKT - the GUI showed the old disabled/enabled
            // items until it was reopened.
            //
            // Now we remove an entry ONLY when we actually handle it or it is
            // dead. Entries of other dimensions wait for their tick.
            if (be.isRemoved() || be.getLevel() == null) {
                it.remove();
                continue;
            }
            if (be.getLevel() == level) {
                it.remove();
                be.syncToWatchers(level);
            }
        }
    }

    private void markUpdated() {
        if (level instanceof ServerLevel) {
            // We do not send right away - we collect and broadcast once per tick,
            // so that a series of toggles does not turn into an avalanche of broadcasts.
            PENDING_SYNC.add(this);
        }
    }

    public void syncToPlayer(ServerPlayer player) {
        // We also send the buffer contents - the player browses it in the
        // "Survival Inventory" tab and can pull from it (but not insert into it).
        java.util.List<ItemStack> contents = new java.util.ArrayList<>();
        for (int i = 0; i < buffer.getContainerSize(); i++) {
            ItemStack st = buffer.getItem(i);
            if (!st.isEmpty()) {
                contents.add(st.copy());
            }
        }
        PacketDistributor.sendToPlayer(player, new OpenCraftingTableScreenPKT(
                this.getBlockPos(), new HashSet<>(disabledItems),
                new HashMap<>(preferredRecipes), contents));
    }

    public void syncToWatchers(ServerLevel level) {
        SyncCraftingTableStatePKT pkt = new SyncCraftingTableStatePKT(
                this.getBlockPos(), new HashSet<>(disabledItems), new HashMap<>(preferredRecipes));
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Item item : disabledItems) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            if (rl != null) {
                list.add(StringTag.valueOf(rl.toString()));
            }
        }
        tag.put("DisabledItems", list);

        // Preferred recipes: item -> recipe id
        CompoundTag prefs = new CompoundTag();
        for (Map.Entry<Item, ResourceLocation> e : preferredRecipes.entrySet()) {
            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(e.getKey());
            if (itemKey != null) {
                prefs.putString(itemKey.toString(), e.getValue().toString());
            }
        }
        tag.put("PreferredRecipes", prefs);

        // The production surplus buffer.
        tag.put("Buffer", buffer.saveTo(registries));
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        disabledItems = new HashSet<>();
        if (tag.contains("DisabledItems")) {
            ListTag list = tag.getList("DisabledItems", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String s = list.getString(i);
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) {
                    Item item = BuiltInRegistries.ITEM.get(rl);
                    if (item != null) {
                        disabledItems.add(item);
                    }
                }
            }
        } else if (tag.contains("EnabledItems")) {
            // Migration from the old (opt-in) format: back then only what was on
            // the list was enabled, so the exceptions must be ALL THE REMAINING
            // craftable items.
            //
            // We compute this only later - in loadAdditional `level` is still
            // null and the craftable list is empty (see
            // pendingOptInMigration). Here we only remember what was enabled.
            Set<Item> wasEnabled = new HashSet<>();
            ListTag list = tag.getList("EnabledItems", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                ResourceLocation rl = ResourceLocation.tryParse(list.getString(i));
                if (rl != null) {
                    Item item = BuiltInRegistries.ITEM.get(rl);
                    if (item != null) {
                        wasEnabled.add(item);
                    }
                }
            }
            pendingOptInMigration = wasEnabled;
        }

        preferredRecipes = new HashMap<>();
        CompoundTag prefs = tag.getCompound("PreferredRecipes");
        for (String key : prefs.getAllKeys()) {
            ResourceLocation itemKey = ResourceLocation.tryParse(key);
            ResourceLocation recipeId = ResourceLocation.tryParse(prefs.getString(key));
            if (itemKey != null && recipeId != null) {
                Item item = BuiltInRegistries.ITEM.get(itemKey);
                if (item != null) {
                    preferredRecipes.put(item, recipeId);
                }
            }
        }

        // The production surplus buffer.
        buffer.loadFrom(tag.getList("Buffer", Tag.TAG_COMPOUND), registries);
    }
}
