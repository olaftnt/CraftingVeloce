package com.craftingveloce.block.entity;

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
 * Auto-crafter Veloce.
 *
 * <p>Przechowuje:
 * <ul>
 *   <li>{@code enabledItems} - itemy, dla ktorych auto-crafting jest wlaczony.
 *       Gracz wlacza je w GUI (klikniecie itemu).</li>
 *   <li>{@code preferredRecipes} - dla itemow z wieloma recepturami: ktora
 *       receptura ma byc uzyta jako pierwsza. Wybor przez shift+scroll w GUI.</li>
 * </ul>
 *
 * <p>Uwaga: ten blok NIE posiada wlasnego ekwipunku - craftowanie odbywa sie
 * bezposrednio na sieci (patrz {@code VeloceAutoCrafter}). Dzieki temu
 * craftowanie jest natychmiastowe i nic nie przechodzi przez fizyczny blok.
 */
public class VeloceCraftingTableBlockEntity extends BlockEntity {

    /**
     * Itemy, dla ktorych auto-crafting jest <b>wylaczony</b>.
     *
     * <p>Model jest opt-out: nowo postawiony crafter ma wlaczone wszystko,
     * a gracz swiadomie wylacza to, czego nie chce. Dzieki temu nie trzeba
     * klikac setek itemow, zeby crafter zaczal dzialac, a lista wyjatkow
     * jest krotka i miesci sie w NBT.
     *
     * <p>Uwaga na puapke: swiezo wczytany blok bez zapisanych wyjatkow
     * znaczy "wszystko wlaczone". Gdyby kiedys zmienic domyslna wartosc na
     * "wszystko wylaczone", stare swiaty nagle przestalyby craftowac.
     */
    private Set<Item> disabledItems = new HashSet<>();

    /** Znacznik, czy blok byl juz kiedys zapisany (patrz {@link #loadAdditional}). */
    private boolean loadedFromSave = false;

    /** Item -> id receptury, ktora ma priorytet przy auto-craftowaniu. */
    private Map<Item, ResourceLocation> preferredRecipes = new HashMap<>();

    /**
     * Bufor nadwyzki produkcji - dostepny normalnie dla calej sieci.
     * Gdy craftowanie daje wiecej niz gracz pobral (np. 1 log -> 4 deski,
     * a chcial 1), reszta ladauje tutaj i mozna ja wyciagnac z terminala.
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

    public VeloceCraftingTableBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(), pos, state);
    }

    /** Itemy wylaczone (wyjatki od reguly "wszystko wlaczone"). */
    public Set<Item> getDisabledItems() {
        return disabledItems;
    }

    /**
     * Czy auto-crafting dla tego itemu jest wlaczony.
     * Domyslnie TAK - wylaczone sa tylko jawne wyjatki.
     */
    public boolean isEnabled(Item item) {
        return !disabledItems.contains(item);
    }

    public Map<Item, ResourceLocation> getPreferredRecipes() {
        return preferredRecipes;
    }

    /** Receptura preferowana dla itemu (moze byc null = pierwsza dostepna). */
    @Nullable
    public ResourceLocation getPreferredRecipe(Item item) {
        return preferredRecipes.get(item);
    }

    /**
     * Przelacza auto-crafting dla itemu. Wylaczenie czysci tez preferencje,
     * zeby nie zostawac ze stanem po itemie, ktory nie jest juz craftowany.
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
     * Ustawia preferowana recepture dla itemu (shift+scroll w GUI).
     * Przechodzi do kolejnej receptury z listy podanej przez serwer.
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

    /** Ustawia konkretna recepture jako preferowana. */
    public void setPreferredRecipe(Item item, ResourceLocation recipeId) {
        if (recipeId == null) {
            preferredRecipes.remove(item);
        } else {
            preferredRecipes.put(item, recipeId);
        }
        setChanged();
        markUpdated();
    }

    private void markUpdated() {
        if (level instanceof ServerLevel sl) {
            syncToWatchers(sl);
        }
    }

    public void syncToPlayer(ServerPlayer player) {
        // Wysylamy tez zawartosc bufora - gracz przeglada go w zakladce
        // "Survival Inventory" i moze z niego wyciagac (ale nie wkladac).
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

        // Preferowane receptury: item -> recipe id
        CompoundTag prefs = new CompoundTag();
        for (Map.Entry<Item, ResourceLocation> e : preferredRecipes.entrySet()) {
            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(e.getKey());
            if (itemKey != null) {
                prefs.putString(itemKey.toString(), e.getValue().toString());
            }
        }
        tag.put("PreferredRecipes", prefs);

        // Bufor nadwyzki produkcji.
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
            // Migracja ze starego formatu (opt-in). Wtedy wlaczone bylo tylko to,
            // co na liscie, wiec wyliczamy wyjatki jako "wszystko poza lista".
            // Bez tego stare swiaty nagle wlaczylyby wszystko.
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
            for (Item candidate : com.craftingveloce.crafting.VeloceRecipeRegistry
                    .getAllCraftableItems(level instanceof ServerLevel sl2 ? sl2 : null)) {
                if (!wasEnabled.contains(candidate)) {
                    disabledItems.add(candidate);
                }
            }
        }
        loadedFromSave = true;

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

        // Bufor nadwyzki produkcji.
        buffer.loadFrom(tag.getList("Buffer", Tag.TAG_COMPOUND), registries);
    }
}
