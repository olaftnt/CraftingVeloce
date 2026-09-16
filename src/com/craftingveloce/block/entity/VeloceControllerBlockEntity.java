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
 * Block entity Veloce Controller.
 *
 * <p>Zbiera i wysyla do klienta pelny obraz sieci potrzebny do GUI:
 * <ul>
 *   <li>{@code stock} - ile sztuk kazdego itemu jest fizycznie w sieci</li>
 *   <li>{@code craftable} - ktore itemy maja recepture wykonywalna bez energii</li>
 *   <li>{@code craftingEnabled} - dla ktorych auto-crafting jest wlaczony
 *       (czyli crafter potrafi je zrobic, nawet bez itemow na stocku)</li>
 *   <li>{@code hotbar} - co gracz ma w hotbarze (do kolorowania ikon)</li>
 * </ul>
 */
public class VeloceControllerBlockEntity extends BlockEntity
        implements VeloceCraftCountSource {

    /**
     * Jak blisko musi stac gracz, zeby kontroler obsluzyl jego zapytanie
     * o tempo przeplywu. Bez tego limitu dowolny gracz moglby zamowic prace
     * serwera dla dowolnej pozycji w swiecie.
     */
    public static final double MAX_FLOW_REQUEST_DISTANCE = 64.0;

    /**
     * Miernik przeplywu. Zyje razem z kontrolerem i mierzy siec, do ktorej
     * kontroler jest AKTUALNIE podlaczony.
     */
    private final VeloceFlowTracker flow = new VeloceFlowTracker();
    /** Roznica stocku wobec tego, co juz poszlo do klientow (patrz sendFlowTo). */
    private final com.craftingveloce.crafting.VeloceStockDeltas stockDeltas =
            new com.craftingveloce.crafting.VeloceStockDeltas();

    /** Siec, dla ktorej zbieramy migawki - zeby wykryc przestawienie kontrolera. */
    private java.util.UUID sampledNetworkId;

    public VeloceControllerBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CONTROLLER_BE.get(), pos, state);
    }

    /**
     * Jedno tykniecie kontrolera: migawka stocku (raz na 5 s) i nic wiecej.
     *
     * <p>Nie wysylamy tu nic do graczy - o tempo pyta klient osobnym pakietem
     * (patrz {@link com.craftingveloce.network.ControllerFlowRequestPKT}).
     * Dzieki temu kontroler nie musi pamietac, kto patrzy, i nie ma czego
     * zgubic, gdy klient wyjdzie z gry albo sie teleportuje.
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
            // Kontroler bez sieci nie ma czego mierzyc. Zerujemy pomiar, zeby
            // po ponownym podlaczeniu nie mieszac historii z innej sieci.
            if (sampledNetworkId != null) {
                flow.reset();
                sampledNetworkId = null;
            }
            return;
        }
        // Inna siec niz dotad (kontroler przestawiony) - stara historia jest
        // bez znaczenia i tylko zafalszowalaby tempo.
        if (sampledNetworkId != null && !sampledNetworkId.equals(net.getId())) {
            flow.reset();
        }
        sampledNetworkId = net.getId();
        flow.sample(now, net.getAllItemCounts(sl));
    }

    /**
     * Liczby "ile da sie jeszcze dorobic" dla widocznej strony kontrolera.
     *
     * <p>Ta sama logika co w terminalu (i ten sam wspolny interfejs), bo
     * kontroler ma pokazywac DOKLADNIE te same dwie liczby co terminal -
     * a nie wlasne, uproszczone przyblizenie.
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
            // Siec jeszcze nie gotowa - NIE mowimy "nic sie nie da zrobic",
            // bo klient skasowalby wtedy poprawne liczby.
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
        // Ten sam log co terminal - bez niego nie da sie stwierdzic, czy
        // kontroler w ogole dostal liczby (a gracz wlasnie to zglosil).
        com.craftingveloce.util.VeloceLog.Craft.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "controller craftable count for %d item(s) -> %d result(s) in %d ms "
                        + "(complete=%s, heat=%s)",
                items.size(), result.counts().size(),
                (System.nanoTime() - start) / 1_000_000L, result.complete(),
                result.heatAvailable() ? "piec w sieci" : "brak pieca");
        return result;
    }

    /**
     * Wysyla graczowi tempo przeplywu I ZMIANE stocku (odpowiedz na zapytanie).
     *
     * <p>Stock jedzie razem z tempem, bo kontroler ma sie odswiezac tak samo
     * jak terminal: bez tego wyjecie itemu ze skrzynki przy otwartym GUI nie
     * zmienialo ani liczby, ani koloru ikony.
     *
     * <p><b>Tylko zmiany.</b> Zapytanie leci raz na sekunde, a stock prawie
     * nigdy sie nie zmienia - wysylanie calego obrazu sieci za kazdym razem
     * znaczyloby tysiace wpisow na sekunde w duzej sieci (im wieksza siec, tym
     * wiekszy ruch bez pozytku). Roznice liczy
     * {@link com.craftingveloce.crafting.VeloceStockDeltas}, a raz na minute
     * leci pelny zrzut jako zabezpieczenie przed rozjazdem.
     */
    public void sendFlowTo(ServerPlayer player) {
        Map<Item, Long> stock = Map.of();
        long gameTime = 0L;
        if (level instanceof ServerLevel sl) {
            gameTime = sl.getGameTime();
            VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                    .getNetworkForTerminal(sl, worldPosition);
            if (net != null) {
                stock = net.getAllItemCounts(sl);
            }
        }
        com.craftingveloce.crafting.VeloceStockDeltas.Delta delta =
                stockDeltas.diff(stock, gameTime);
        PacketDistributor.sendToPlayer(player, new com.craftingveloce.network.SyncControllerFlowPKT(
                worldPosition, delta.changed(), delta.removed(), flow.steadyRates(), delta.full()));
    }

    /** Ile roznych itemow ma siec (dla overlay Jade). */
    public int stockItemTypes() {
        if (!(level instanceof ServerLevel sl)) {
            return 0;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        return net == null ? 0 : net.getAllItemCounts(sl).size();
    }

    /** Ile itemow ma teraz STALY trend (dla overlay Jade) - to samo, co GUI. */
    public int steadyFlowCount() {
        return flow.steadyRates().size();
    }

    /** Zbiera aktualny stan sieci i wysyla GUI graczowi. */
    public void syncToPlayer(ServerPlayer player) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);

        // BRAK SIECI NIE MOZE BYC PRZEMILCZANY.
        //
        // BUG, ktory to ukrywalo: kontroler nie byl rozpoznawany jako wezel
        // (patrz VeloceNodeBlocks), wiec `net` bylo tu ZAWSZE null. Kontroler
        // dostawal pusty stock i pusty zbior itemow z wlaczonym auto-craftingiem
        // - i pokazywal "auto-crafting wylaczony" dla WSZYSTKICH itemow, mimo
        // ze crafter w sieci mial je wlaczone. Bez tego logu wygladalo to jak
        // blad w samym auto-craftingu, a nie w podlaczeniu kontrolera.
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

        // PIEC: receptury pieca sa uzywalne TYLKO gdy w sieci stoi ZASILONY piec.
        // Klient uzywa tego do JEDNEJ rzeczy: zolte tlo dla itemow, ktore da sie
        // przepalic. "Stoi jakikolwiek piec bez paliwa" nie jest juz potrzebne -
        // te informacje nosily usuniete teksty w tooltipie.
        Set<Item> furnaceCraftable = VeloceRecipeRegistry.getAllFurnaceCraftableItems(sl);
        boolean furnacePowered = net != null && VeloceHeatSources.hasPower(sl, net);

        // Preferencja "crafting czy piec" - z sieci, wiec widzi ja cala siec,
        // a nie jeden kontroler. Zamiast hotbara (usuniety na zyczenie):
        // gracz nie chcial informacji "in hotbar" na ikonach.
        Set<Item> furnacePreferred = net == null ? Set.of() : net.getFurnacePreferred();

        PacketDistributor.sendToPlayer(player, new OpenControllerScreenPKT(
                this.getBlockPos(), stock, craftingEnabled,
                furnaceCraftable, furnacePowered, furnacePreferred));

        // Tempo przeplywu idzie osobnym, lekkim pakietem. Wysylamy je od razu,
        // zeby gracz nie czekal sekundy na pierwsze liczby - a potem klient
        // dopytuje sam, dopoki ma otwarte GUI.
        sendFlowTo(player);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Controller nie przechowuje stanu - jest tylko widokiem na siec.
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    }

    /** Pomocnicze: id itemu (do NBT/debugowania). */
    public static ResourceLocation idOf(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
    }
}
