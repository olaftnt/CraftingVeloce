package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejestr modulow przetwarzania - jedyne miejsce, ktore wie, CO siec potrafi.
 *
 * <p><b>Zasada.</b> Kto pyta "czy da sie zrobic item X i ile", pyta ten rejestr
 * (patrz {@link VeloceCraftingRegistry#getAllEnabledItems}). Dzieki temu:
 * <ul>
 *   <li>siec z samym piecem umie przepalac (i nie potrzebuje do tego craftera),</li>
 *   <li>siec z samym crafterem umie craftowac,</li>
 *   <li>nowy modul (takze z innego moda) wystarczy zarejestrowac - reszta rdzenia
 *       nie wymaga zadnej zmiany.</li>
 * </ul>
 *
 * <p><b>Kolejnosc ma znaczenie tylko dla logow</b> - moduly nie konkuruja ze
 * soba o wynik, bo kazdy doklada swoje itemy do wspolnej sumy.
 */
public final class VeloceProcessingRegistry {

    private VeloceProcessingRegistry() {
    }

    private static final Map<String, VeloceProcessingModule> MODULES = new LinkedHashMap<>();
    private static boolean builtinsRegistered;

    /** Rejestruje modul. Powtorna rejestracja tego samego id jest ignorowana. */
    public static synchronized void register(VeloceProcessingModule module) {
        if (module == null || module.id() == null) {
            return;
        }
        MODULES.putIfAbsent(module.id(), module);
    }

    /** Wbudowane moduly rdzenia: crafter i piec. */
    private static synchronized void registerBuiltins() {
        if (builtinsRegistered) {
            return;
        }
        builtinsRegistered = true;
        register(new CraftingModule());
        register(new FurnaceModule());
    }

    /** Wszystkie moduly (wbudowane + zarejestrowane przez integracje). */
    public static synchronized List<VeloceProcessingModule> all() {
        registerBuiltins();
        return new ArrayList<>(MODULES.values());
    }

    /** Ktory modul obsluguje dany typ receptury - do diagnostyki i gatingu. */
    public static VeloceProcessingModule moduleFor(RecipeType<?> type) {
        for (VeloceProcessingModule module : all()) {
            if (module.recipeTypes().contains(type)) {
                return module;
            }
        }
        return null;
    }

    /** Id wszystkich zarejestrowanych modulow - do logu przy starcie. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (VeloceProcessingModule module : all()) {
            out.add(module.id());
        }
        return out;
    }

    /**
     * Czysci pamiec WSZYSTKICH modulow (indeksy receptur).
     *
     * <p>Moduly trzymaja wlasne indeksy receptur, wiec po przeladowaniu danych
     * trzeba je wyczyscic - ale rdzen nie moze znac ich typow. Dlatego kazdy
     * modul czysci sie sam, a rdzen tylko o to prosi.
     */
    public static void invalidateAll() {
        for (VeloceProcessingModule module : all()) {
            module.invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Wbudowane moduly
    // ------------------------------------------------------------------

    /**
     * Crafter: receptury bez infrastruktury (crafting table, stonecutter,
     * smithing). Wymaga co najmniej jednego craftera w sieci, a jego lista
     * wylaczen dziala TYLKO na te receptury.
     */
    private static final class CraftingModule implements VeloceProcessingModule {

        @Override
        public String id() {
            return "crafting";
        }

        @Override
        public Set<RecipeType<?>> recipeTypes() {
            return VeloceRecipeFamilies.FREE;
        }

        @Override
        public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
            List<VeloceCraftingTableBlockEntity> crafters =
                    VeloceCraftingRegistry.crafters(level, network);
            if (crafters.isEmpty()) {
                return Set.of();
            }
            Set<Item> items = new HashSet<>(VeloceRecipeRegistry.getAllCraftableItems(level));
            // Wylaczenia gracza (model opt-out) dotycza wylacznie tych receptur -
            // wlasnie dlatego ten filtr jest TU, a nie we wspolnej sumie modulow.
            items.removeAll(crafters.get(0).getDisabledItems());
            return items;
        }

        @Override
        public boolean available(ServerLevel level, VelocePipeNetwork network) {
            return !VeloceCraftingRegistry.crafters(level, network).isEmpty();
        }

        @Override
        public boolean powered(ServerLevel level, VelocePipeNetwork network) {
            // Crafter nie potrzebuje paliwa ani pradu - jak stoi, to dziala.
            return available(level, network);
        }
    }

    /**
     * Piec: przepalanie (smelting, blasting, smoking).
     *
     * <p><b>BUG, ktory to naprawia.</b> Wczesniej brak craftera w sieci zerowal
     * CALA liste mozliwosci, wiec siec z samym piecem nie umiala zrobic szkla
     * z piasku - choc to receptura WYLACZNIE piecowa. Teraz piec jest osobnym
     * modulem i wystarcza sam.
     */
    private static final class FurnaceModule implements VeloceProcessingModule {

        @Override
        public String id() {
            return "furnace";
        }

        @Override
        public Set<RecipeType<?>> recipeTypes() {
            return VeloceRecipeFamilies.FURNACE;
        }

        @Override
        public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
            return VeloceRecipeRegistry.getAllFurnaceCraftableItems(level);
        }

        @Override
        public boolean available(ServerLevel level, VelocePipeNetwork network) {
            return VeloceHeatSources.hasAnyHeatSource(level, network);
        }

        @Override
        public boolean powered(ServerLevel level, VelocePipeNetwork network) {
            return VeloceHeatSources.hasPower(level, network);
        }
    }
}
