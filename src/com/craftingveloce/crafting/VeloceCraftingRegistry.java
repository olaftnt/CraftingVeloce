package com.craftingveloce.crafting;

import com.craftingveloce.util.VeloceLog;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Spojny widok na wszystkie auto-craftery w sieci.
 *
 * <p>Zbiera informacje z blokow Veloce Crafting Table podlaczonych do sieci:
 * ktore itemy maja wlaczone auto-craftowanie oraz ktora receptura ma priorytet
 * (dla itemow z wieloma recepturami gracz moze wybrac recepture shift+scrollem).
 */
public final class VeloceCraftingRegistry {

    private VeloceCraftingRegistry() {
    }

    /**
     * Craftery w sieci, w DETERMINISTYCZNEJ kolejnosci.
     *
     * <p>{@code network.getTerminals()} to zbior bez okreslonej kolejnosci, wiec
     * wybor "pierwszego craftera" byl przypadkowy i mogl sie zmieniac miedzy
     * uruchomieniami. Sortujemy po pozycji, zeby zachowanie bylo powtarzalne:
     * przy kilku crafterach w sieci zawsze wygrywa ten sam.
     */
    private static java.util.List<VeloceCraftingTableBlockEntity> crafters(
            ServerLevel level, VelocePipeNetwork network) {
        java.util.List<BlockPos> sorted = new java.util.ArrayList<>(network.getTerminals());
        sorted.sort(java.util.Comparator
                .comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt(p -> p.getY())
                .thenComparingInt(p -> p.getZ()));
        java.util.List<VeloceCraftingTableBlockEntity> out = new java.util.ArrayList<>();
        int unreadable = 0;
        for (BlockPos pos : sorted) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                out.add(crafter);
            } else if (be == null) {
                // Wezel, ktorego NIE DA SIE ODCZYTAC (chunk nie jest zaladowany).
                //
                // Liczymy to i raportujemy, bo inaczej awaria jest CICHA:
                // crafter wypada z listy, `getAllEnabledItems` zwraca pusty
                // zbior i cale auto-craftowanie wylacza sie bez jednego sladu
                // w logu - a objaw ("auto-crafting OFF na wszystkim") jest
                // identyczny z bledem, ktory juz raz tu byl. Gdyby ktokolwiek
                // to widzial w logu, szukalby przyczyny w force-loadach,
                // a nie w recepturach.
                unreadable++;
            }
        }
        if (unreadable > 0) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "%d node(s) of the network could not be read (chunk not loaded) - "
                            + "auto-crafting may look disabled for everything; "
                            + "check /cv chunk status",
                    unreadable);
        }
        return out;
    }

    /**
     * Znajduje crafter w sieci, ktory ma wlaczone auto-craftowanie dla danego itemu.
     *
     * @return pierwszy taki block entity albo {@code null}
     */
    @Nullable
    public static VeloceCraftingTableBlockEntity findEnabledCrafter(ServerLevel level,
                                                                    VelocePipeNetwork network,
                                                                    Item item) {
        for (VeloceCraftingTableBlockEntity crafter : crafters(level, network)) {
            if (crafter.isEnabled(item)) {
                return crafter;
            }
        }
        return null;
    }

    /** Czy w sieci istnieje jakikolwiek crafter z wlaczona receptura dla itemu. */
    public static boolean isCraftingEnabled(ServerLevel level, VelocePipeNetwork network, Item item) {
        return findEnabledCrafter(level, network, item) != null;
    }

    /**
     * Buduje mape preferowanych receptur na podstawie ustawien wszystkich
     * crafterow w sieci. Przy konflikcie wygrywa crafter blizej poczatku
     * zbioru terminali (kolejnosc stabilna).
     */
    public static Map<Item, ResourceLocation> getPreferredRecipes(ServerLevel level,
                                                                  VelocePipeNetwork network) {
        Map<Item, ResourceLocation> out = new HashMap<>();
        for (VeloceCraftingTableBlockEntity crafter : crafters(level, network)) {
            for (Map.Entry<Item, ResourceLocation> e : crafter.getPreferredRecipes().entrySet()) {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Itemy, ktore auto-craftery w tej sieci faktycznie potrafia zrobic.
     *
     * <p>UWAGA na semantyke: crafter dziala w modelu opt-out (domyslnie wszystko
     * wlaczone, gracz zapisuje tylko wyjatki). Ta metoda musi wiec zwrocic
     * roznice: wszystkie craftowalne itemy MINUS te, ktore gracz wylaczyl
     * w ktorymkolwiek crafterze.
     *
     * <p>Zwracanie samych wyjatkow byloby bledem - silnik dostalby liste
     * itemow, ktore wolno craftowac, a zamiast tego dostalby liste
     * wylaczonych, czyli dokladna odwrotnosc.
     */
    public static java.util.Set<Item> getAllEnabledItems(ServerLevel level, VelocePipeNetwork network) {
        java.util.Set<Item> craftable = VeloceRecipeRegistry.getAllCraftableItems(level);

        // PIEC DOKLADA SWOJE RECEPTURY - ale tylko gdy jest ZASILONY.
        //
        // Bez tego auto-crafter nigdy nie tknąłby itemu, ktory powstaje
        // WYŁĄCZNIE w piecu (sztabka z rudy, szkło, węgiel drzewny): nie ma go
        // na liście craftowalnych bez energii, więc wypadał z `enabled` i cała
        // sciezka piecowa była martwa - mimo że piec stal w sieci i się palił.
        //
        // Gdy pieca nie ma albo stoi bez paliwa, lista zostaje bez zmian -
        // czyli dokladnie tak, jakby pieca w ogole nie było.
        if (VeloceHeatSources.hasPower(level, network)) {
            java.util.Set<Item> merged = new java.util.HashSet<>(craftable);
            merged.addAll(VeloceRecipeRegistry.getAllFurnaceCraftableItems(level));
            craftable = merged;
        }

        if (craftable.isEmpty()) {
            return java.util.Set.of();
        }
        // Decyduje PIERWSZY crafter (deterministycznie). Wczesniej laczylismy
        // wylaczenia ze WSZYSTKICH crafterow, wiec jeden crafter z wylaczonymi
        // deskami psul auto-crafting deskami dla calej sieci - mimo ze inny
        // crafter mial je wlaczone.
        java.util.List<VeloceCraftingTableBlockEntity> crafters = crafters(level, network);
        if (crafters.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Item> disabled = new java.util.HashSet<>(
                crafters.get(0).getDisabledItems());

        // KLUCZOWE: bez craftera w sieci NIE mozna nic zrobic.
        // Wczesniej, gdy lista wyjatkow byla pusta, zwracalismy wszystkie
        // craftowalne itemy - wiec terminal pokazywal "+N" mimo braku craftera.

        if (disabled.isEmpty()) {
            return craftable;
        }
        java.util.Set<Item> out = new java.util.HashSet<>(craftable);
        out.removeAll(disabled);
        return out;
    }

    /**
     * Bufory wszystkich crafterow w sieci - pamiec podreczna na nadwyzke produkcji.
     * Kolejnosc stabilna (kolejnosc terminali), zeby wyniki byly przewidywalne.
     */
    public static java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> getBuffers(
            ServerLevel level, VelocePipeNetwork network) {
        java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> out = new java.util.ArrayList<>();
        for (VeloceCraftingTableBlockEntity crafter : crafters(level, network)) {
            out.add(crafter.getBuffer());
        }
        return out;
    }
}
