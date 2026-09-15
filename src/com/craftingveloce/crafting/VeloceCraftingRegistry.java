package com.craftingveloce.crafting;

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
     * Znajduje crafter w sieci, ktory ma wlaczone auto-craftowanie dla danego itemu.
     *
     * @return pierwszy taki block entity albo {@code null}
     */
    @Nullable
    public static VeloceCraftingTableBlockEntity findEnabledCrafter(ServerLevel level,
                                                                    VelocePipeNetwork network,
                                                                    Item item) {
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter && crafter.isEnabled(item)) {
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
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                for (Map.Entry<Item, ResourceLocation> e : crafter.getPreferredRecipes().entrySet()) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /** Zbior itemow, dla ktorych auto-crafting jest wlaczony w calej sieci. */
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
        if (craftable.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Item> disabled = new java.util.HashSet<>();
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                disabled.addAll(crafter.getDisabledItems());
            }
        }
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
        for (BlockPos pos : network.getTerminals()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                out.add(crafter.getBuffer());
            }
        }
        return out;
    }
}
