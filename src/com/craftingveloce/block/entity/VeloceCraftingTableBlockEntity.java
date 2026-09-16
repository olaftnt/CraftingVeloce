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

    /**
     * Migracja ze starego formatu (opt-in) czeka na wykonanie.
     *
     * <p><b>BUG, ktory to naprawia.</b> Migracja liczyla wyjatki jako
     * "wszystko craftowalne MINUS lista wlaczonych ze starego zapisu" i robila
     * to w {@code loadAdditional}. Ale tam {@code level} jest JESZCZE NULL -
     * Minecraft tworzy block entity i wola {@code loadAdditional}, a poziom
     * przypisuje dopiero potem ({@code setLevel}). A
     * {@code getAllCraftableItems(null)} zwraca zbior PUSTY (bo {@code null}
     * nie jest {@code instanceof ServerLevel}), wiec petla nie miala po czym
     * iterowac i {@code disabledItems} zostawalo puste.
     *
     * <p>Skutek: stary swiat zapisany w formacie opt-in dostawal WSZYSTKO
     * WLACZONE - czyli dokladnie ta regresja, przed ktora ostrzega komentarz
     * przy {@link #disabledItems}.
     *
     * <p>Dlatego liste ze starego zapisu tylko zapamietujemy, a przeliczamy ja
     * pozniej - gdy poziom i receptury sa juz dostepne.
     */
    @Nullable
    private Set<Item> pendingOptInMigration;

    /**
     * Domyka migracje ze starego formatu, gdy tylko da sie ja policzyc.
     *
     * <p>Wolane z {@link #setLevel} (poziom jest juz przypisany) ORAZ przy
     * kazdym pytaniu o wyjatki - gdyby w chwili wczytania chunka receptury nie
     * byly jeszcze gotowe, proba wroci przy pierwszym uzyciu zamiast przepasc.
     */
    private void finishOptInMigration() {
        if (pendingOptInMigration == null || !(level instanceof ServerLevel sl)) {
            return;
        }
        Set<Item> craftable = VeloceRecipeRegistry.getAllCraftableItems(sl);
        if (craftable.isEmpty()) {
            // Receptury jeszcze nie wczytane - zostawiamy flage i sprobujemy
            // ponownie przy nastepnym pytaniu. Wyczyszczenie jej teraz znaczyloby
            // zapisanie pustej listy wyjatkow, czyli "wszystko wlaczone".
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
        // Poziom jest od teraz dostepny - to pierwsza okazja, zeby dokonczyc
        // migracje ze starego formatu (patrz finishOptInMigration).
        finishOptInMigration();
    }

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

    /**
     * Gabriota wyswietlana w srodku klatki (pusta = zwykly stol craftingu).
     *
     * <p>Tylko do WIDOKU i do zwrotu przy zbiciu - logika craftingu jej nie
     * uzywa. Trzymana w block entity, bo stan bloku nie uniesie dowolnego
     * przedmiotu.
     */
    private ItemStack displayItem = ItemStack.EMPTY;

    /** Gabriota w srodku (pusta dla zwyklego stolu). */
    public ItemStack getDisplayItem() {
        return displayItem;
    }

    /** Ustawia gablote i wysyla ja klientowi (renderuje ja w srodku). */
    public void setDisplayItem(ItemStack stack) {
        this.displayItem = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    /**
     * Czy ten block entity jest CRAFTEREM dla sieci.
     *
     * <p>Klatka uzywa tego samego block entity co stol, wiec pusta klatka nie
     * moze udawac craftera (inaczej siec widzialaby craftery, ktorych nie ma).
     */
    public boolean isActiveCrafter() {
        BlockState state = getBlockState();
        if (state.getBlock() instanceof com.craftingveloce.block.VeloceIntegraleBlock integrale) {
            return integrale.isFilled(state) || com.craftingveloce.block.VeloceIntegraleBlock
                    .isFilled(state);
        }
        return true;
    }

    /** Pakiet z gablota dla klienta (klatka renderuje ja w srodku). */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.saveOptional(registries));
        }
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    public VeloceCraftingTableBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(), pos, state);
    }

    /** Itemy wylaczone (wyjatki od reguly "wszystko wlaczone"). */
    public Set<Item> getDisabledItems() {
        // Ponowienie migracji: jesli w chwili wczytania chunka receptury nie byly
        // jeszcze gotowe, dokanczamy ja teraz - ten call ma juz poziom.
        finishOptInMigration();
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

    /**
     * Craftery czekajace na rozgloszenie swojego stanu.
     *
     * <p><b>Po co kolejka.</b> Prawy klik na zakladce przelacza CALA grupe
     * itemow, a klient wysyla wtedy jeden pakiet na item (przy duzej zakladce
     * setki). Kazdy taki pakiet konczyl sie natychmiastowym rozgloszeniem
     * {@code SyncCraftingTableStatePKT} do WSZYSTKICH graczy w swiecie, ze
     * wszystkimi wylaczonymi itemami w srodku. Setki broadcastow po kilkanascie
     * kilobajtow w ulamku sekundy to realne zacięcie.
     *
     * <p>Teraz zbieramy tylko "ten crafter sie zmienil" i wysylamy RAZ na tick
     * (patrz {@link #flushPendingSyncs}). Slaby zbior, zeby nie trzymac
     * block entity na sztywno.
     */
    private static final java.util.Set<VeloceCraftingTableBlockEntity> PENDING_SYNC =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Wysyla zalegle stany crafterow. Wolane z ticku serwera. */
    public static void flushPendingSyncs(ServerLevel level) {
        if (PENDING_SYNC.isEmpty()) {
            return;
        }
        java.util.Iterator<VeloceCraftingTableBlockEntity> it = PENDING_SYNC.iterator();
        while (it.hasNext()) {
            VeloceCraftingTableBlockEntity be = it.next();

            // BUG, ktory tu byl: `it.remove()` wykonywalo sie PRZED sprawdzeniem
            // wymiaru, wiec pierwszy tickujacy swiat zjadal (i wyrzucal) wpisy
            // należace do WSZYSTKICH pozostalych. Przelaczenie receptury w
            // crafterze stojacym w Netherze (gdy Overworld tika pierwszy) nie
            // wysylalo wiec SyncCraftingTableStatePKT - GUI pokazywalo stare
            // wylaczone/wlaczone itemy az do ponownego otwarcia.
            //
            // Teraz wpis usuwamy TYLKO wtedy, gdy faktycznie go obsluzymy albo
            // jest martwy. Wpisy innych wymiarow czekaja na swoj tick.
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
            // Nie wysylamy od razu - zbieramy i rozglaszamy raz na tick,
            // zeby seria przelaczen nie zamienila sie w lawine broadcastow.
            PENDING_SYNC.add(this);
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

        // Gabriota w klatce (tylko do widoku).
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.saveOptional(registries));
        }
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        displayItem = ItemStack.parseOptional(registries, tag.getCompound("DisplayItem"));
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
            // Migracja ze starego formatu (opt-in): wtedy wlaczone bylo tylko to,
            // co na liscie, wiec wyjatkami maja byc WSZYSTKIE POZOSTALE
            // craftowalne itemy.
            //
            // Liczymy to dopiero pozniej - w loadAdditional `level` jest jeszcze
            // null i lista craftowalnych jest pusta (patrz
            // pendingOptInMigration). Tutaj tylko zapamietujemy, co bylo wlaczone.
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

        // Bufor nadwyzki produkcji.
        buffer.loadFrom(tag.getList("Buffer", Tag.TAG_COMPOUND), registries);
    }
}
