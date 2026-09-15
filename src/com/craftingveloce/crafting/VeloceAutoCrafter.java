package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Silnik auto-craftowania Veloce.
 *
 * <p>Potrafi wyprodukowac item "z niczego" (z punktu widzenia gracza), o ile
 * wszystkie skladniki sa dostepne w sieci lub da sie je wycraftowac rekurencyjnie
 * z tego, co w sieci jest. Craftowanie jest <b>natychmiastowe</b> - nie ma
 * fizycznego bloku posredniego, przez ktory przechodza itemy. Auto crafter
 * dziala "w tle" sieci, dokladnie tak jak opisano w zalozeniach projektu.
 *
 * <p>Algorytm:
 * <ol>
 *   <li>Sprawdz, czy item jest juz w sieci w wystarczajacej ilosci.</li>
 *   <li>Jesli nie - wybierz recepture (z uwzglednieniem priorytetu gracza)
 *       i sprobuj zapewnic kazdy skladnik rekurencyjnie.</li>
 *   <li>Jesli receptura jest niewykonalna (brakuje bazowego surowca), sprobuj
 *       nastepna recepture tego itemu.</li>
 *   <li>Wykonaj recepture: pobierz skladniki z sieci, wstaw wynik do sieci.</li>
 * </ol>
 */
public final class VeloceAutoCrafter {

    /** Maksymalna glebokosc rekurencji - zabezpieczenie przed petlami receptur. */
    private static final int MAX_DEPTH = 16;

    /**
     * Limit pracy na jedno zadanie, zeby zle zadanie nie zamrozilo serwera.
     * Liczy kazda probe craftu.
     */
    private static final int MAX_OPERATIONS = 4096;

    private VeloceAutoCrafter() {
    }

    /** Wynik proby craftowania. */
    public record CraftResult(boolean success, int produced, String reason) {
        static CraftResult ok(int produced) {
            return new CraftResult(true, produced, "");
        }

        static CraftResult fail(String reason) {
            return new CraftResult(false, 0, reason);
        }
    }

    /**
     * Zapewnia, ze w sieci bedzie co najmniej {@code count} sztuk {@code item}.
     * Jesli trzeba - dotwarza brakujaca ilosc.
     *
     * @param network   siec, z ktorej korzystamy (i do ktorej wkladamy wynik)
     * @param inventory dodatkowe zrodlo/przeznaczenie - ekwipunek gracza
     *                  (priorytet wyciagania: ekwipunek -> siec -> crafting)
     */
    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count,
                                              @Nullable ItemInventory inventory,
                                              @Nullable Map<Item, ResourceLocation> preferredRecipes) {
        if (count <= 0) {
            return CraftResult.fail("niepoprawna ilosc");
        }

        // 1. Ekwipunek gracza ma priorytet (nie marnujemy sieci).
        int inInventory = inventory == null ? 0 : inventory.count(item);
        if (inInventory >= count) {
            return CraftResult.ok(count);
        }

        // 2. Siec.
        long inNetwork = network.getAllItemCounts(level).getOrDefault(item, 0L);
        long available = inInventory + inNetwork;
        if (available >= count) {
            return CraftResult.ok(count);
        }

        // 3. Brakuje - trzeba wycraftowac.
        int missing = (int) Math.min(Integer.MAX_VALUE, count - available);
        Set<Item> visiting = new HashSet<>();
        Map<Item, Integer> budget = new HashMap<>();
        int[] operations = {0};

        List<ResourceLocation> order = preferredOrder(level, item, preferredRecipes);
        boolean crafted = craftItem(level, network, item, missing, inventory,
                preferredRecipes, visiting, budget, operations, order, 0);

        if (!crafted) {
            return CraftResult.fail("brak skladnikow do wycraftowania");
        }
        return CraftResult.ok(count);
    }

    /** Kolejnosc receptur dla itemu - najpierw preferowana przez gracza. */
    private static List<ResourceLocation> preferredOrder(ServerLevel level, Item item,
                                                         @Nullable Map<Item, ResourceLocation> preferred) {
        List<ResourceLocation> order = new ArrayList<>();
        ResourceLocation pref = preferred == null ? null : preferred.get(item);
        if (pref != null) {
            order.add(pref);
        }
        for (VeloceRecipeRegistry.CraftingEntry e : VeloceRecipeRegistry.getRecipesFor(level, item)) {
            if (pref == null || !e.id().equals(pref)) {
                order.add(e.id());
            }
        }
        return order;
    }

    /**
     * Probuje wycraftowac {@code amount} sztuk itemu, rekurencyjnie zapewniajac
     * skladniki. Zwraca true, jesli udalo sie osiagnac cel.
     */
    private static boolean craftItem(ServerLevel level, VelocePipeNetwork network,
                                     Item item, int amount,
                                     @Nullable ItemInventory inventory,
                                     @Nullable Map<Item, ResourceLocation> preferred,
                                     Set<Item> visiting, Map<Item, Integer> budget,
                                     int[] operations, List<ResourceLocation> order,
                                     int depth) {
        if (amount <= 0) {
            return true;
        }
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (operations[0]++ > MAX_OPERATIONS) {
            return false;
        }
        // Ochrona przed cyklami (A wymaga B, B wymaga A).
        if (!visiting.add(item)) {
            return false;
        }
        try {
            List<VeloceRecipeRegistry.CraftingEntry> recipes = VeloceRecipeRegistry.getRecipesFor(level, item);
            if (recipes.isEmpty()) {
                return false;
            }

            // Ustaw receptury w kolejnosci preferencji gracza.
            List<VeloceRecipeRegistry.CraftingEntry> ordered = new ArrayList<>();
            for (ResourceLocation id : order) {
                for (VeloceRecipeRegistry.CraftingEntry e : recipes) {
                    if (e.id().equals(id)) {
                        ordered.add(e);
                    }
                }
            }
            for (VeloceRecipeRegistry.CraftingEntry e : recipes) {
                if (!ordered.contains(e)) {
                    ordered.add(e);
                }
            }

            // Sprobuj kolejne receptury - jesli pierwsza jest niewykonalna
            // (brak skladnikow), przechodzimy do nastepnej.
            for (VeloceRecipeRegistry.CraftingEntry recipe : ordered) {
                if (tryRecipe(level, network, recipe, amount, inventory, preferred,
                        visiting, budget, operations, depth)) {
                    return true;
                }
            }
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    /** Proba wykonania jednej konkretnej receptury {@code runs} razy. */
    private static boolean tryRecipe(ServerLevel level, VelocePipeNetwork network,
                                     VeloceRecipeRegistry.CraftingEntry recipe, int amount,
                                     @Nullable ItemInventory inventory,
                                     @Nullable Map<Item, ResourceLocation> preferred,
                                     Set<Item> visiting, Map<Item, Integer> budget,
                                     int[] operations, int depth) {
        int perCraft = Math.max(1, recipe.result().getCount());
        int runs = (amount + perCraft - 1) / perCraft;

        // Zapewnij wszystkie skladniki.
        for (Ingredient ing : recipe.ingredients()) {
            ItemStack[] options = ing.getItems();
            if (options.length == 0) {
                continue;
            }
            // Wybierz opcje, ktorej mamy najwiecej / ktora da sie zapewnic.
            if (!ensureIngredient(level, network, ing, runs, inventory, preferred,
                    visiting, budget, operations, depth + 1)) {
                return false;
            }
        }

        // Wykonaj craftowanie: pobierz skladniki i wstaw wynik.
        for (int i = 0; i < runs; i++) {
            if (operations[0]++ > MAX_OPERATIONS) {
                return false;
            }
            NonNullList<ItemStack> consumed = NonNullList.create();
            boolean ok = true;
            for (Ingredient ing : recipe.ingredients()) {
                ItemStack taken = takeOne(level, network, ing, inventory);
                if (taken.isEmpty()) {
                    ok = false;
                    break;
                }
                consumed.add(taken);
            }
            if (!ok) {
                // Zwroc to, co juz zabralismy, zeby nie zgubic itemow.
                for (ItemStack s : consumed) {
                    deposit(level, network, s);
                }
                return false;
            }
            ItemStack result = recipe.result().copy();
            deposit(level, network, result);
        }
        return true;
    }

    /** Zapewnia, ze skladnik jest dostepny w potrzebnej ilosci. */
    private static boolean ensureIngredient(ServerLevel level, VelocePipeNetwork network,
                                            Ingredient ing, int runs,
                                            @Nullable ItemInventory inventory,
                                            @Nullable Map<Item, ResourceLocation> preferred,
                                            Set<Item> visiting, Map<Item, Integer> budget,
                                            int[] operations, int depth) {
        int needed = runs;
        ItemStack[] options = ing.getItems();
        if (options.length == 0) {
            return true;
        }

        // Ile juz mamy lacznie (ekwipunek + siec) sposrod wszystkich opcji?
        int have = 0;
        for (ItemStack opt : options) {
            if (opt.isEmpty()) {
                continue;
            }
            have += inventory == null ? 0 : inventory.count(opt.getItem());
            have += network.getAllItemCounts(level).getOrDefault(opt.getItem(), 0L);
        }
        if (have >= needed) {
            return true;
        }

        // Brakuje - probujemy dotworzyc kazda z opcji po kolei.
        int missing = needed - have;
        for (ItemStack opt : options) {
            if (opt.isEmpty()) {
                continue;
            }
            Item optItem = opt.getItem();
            List<ResourceLocation> order = preferredOrder(level, optItem, preferred);
            if (craftItem(level, network, optItem, missing, inventory, preferred,
                    visiting, budget, operations, order, depth)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pobiera jedna sztuke pasujaca do skladnika.
     * Priorytet: ekwipunek gracza -> siec.
     */
    private static ItemStack takeOne(ServerLevel level, VelocePipeNetwork network,
                                     Ingredient ing, @Nullable ItemInventory inventory) {
        for (ItemStack opt : ing.getItems()) {
            if (opt.isEmpty()) {
                continue;
            }
            if (inventory != null) {
                ItemStack fromInv = inventory.extract(opt.getItem(), 1);
                if (!fromInv.isEmpty()) {
                    return fromInv;
                }
            }
            ItemStack fromNet = network.extractItem(level, opt.getItem(), 1);
            if (!fromNet.isEmpty()) {
                return fromNet;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Wklada wynik do sieci (do pierwszego endpointu, ktory go przyjmie). */
    private static void deposit(ServerLevel level, VelocePipeNetwork network, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        for (var endpoint : network.getEndpoints().values()) {
            if (endpoint.insertItem(level, stack)) {
                return;
            }
        }
    }

    /**
     * Abstrakcja ekwipunku gracza - zeby silnik nie zalezal bezposrednio
     * od klasy gracza i dal sie testowac.
     */
    public interface ItemInventory {
        int count(Item item);

        ItemStack extract(Item item, int max);
    }
}
