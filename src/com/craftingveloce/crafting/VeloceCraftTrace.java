package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceHeatSource;
import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PELNY slad jednej proby craftowania - dla diagnostyki "pokazuje, ze moge,
 * a nie moge".
 *
 * <p><b>Po co osobny mechanizm.</b> Zwykly log moda jest filtrowany configiem
 * (poziomami), wiec akurat wtedy, gdy cos nie dziala, brakuje najwazniejszych
 * linii. Ten slad loguje sie ZAWSZE (surowe INFO), ale TYLKO w obrebie jednej
 * akcji gracza - patrz {@link #begin(String)}. Dzieki temu:
 * <ul>
 *   <li>gracz klika "wyciagnij item" i dostaje kompletny opis decyzji,</li>
 *   <li>automaty w tle (extractor, bufory) NIE zasmiecaja loga - ich sciezka
 *       nie zaczyna sladu.</li>
 * </ul>
 *
 * <p><b>Format.</b> Kazda linia ma prefiks {@code [Veloce][CRAFT-TRACE][#N]},
 * gdzie N to numer akcji. Wystarczy skopiowac wszystkie linie z jednym N.
 *
 * <p>Slad jest per watek (ThreadLocal), bo craftowanie gracza leci w calosci
 * na watku serwera.
 */
public final class VeloceCraftTrace {

    private VeloceCraftTrace() {
    }

    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    /** Czy w tym watku trwa wlasnie sledzona akcja. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Otwiera slad i zwraca jego numer. */
    public static long begin(String what) {
        long id = IDS.incrementAndGet();
        CURRENT.set(id);
        out(id, "========== START #%d: %s ==========", id, what);
        return id;
    }

    /** Zamyka slad (bezpiecznie wolac zawsze - bez aktywnego sladu nic nie robi). */
    public static void end(String result) {
        Long id = CURRENT.get();
        if (id == null) {
            return;
        }
        out(id, "========== END #%d: %s ==========", id, result);
        CURRENT.remove();
    }

    /** Linia sladu (tylko w obrebie aktywnej akcji). */
    public static void log(String fmt, Object... args) {
        Long id = CURRENT.get();
        if (id == null) {
            return;
        }
        out(id, fmt, args);
    }

    private static void out(long id, String fmt, Object... args) {
        String body = args.length == 0 ? fmt : String.format(fmt, args);
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce][CRAFT-TRACE][#{}] {}", id, body);
    }

    /**
     * Wyjatek w obrebie sledzonej akcji - ze stosem, pod tym samym numerem.
     *
     * <p>Bez tego wyjatek z modulu by lecial "obok" sladu i nie byloby widac,
     * w ktorym kroku przerwal cala probe.
     */
    public static void exception(String context, Throwable thrown) {
        Long id = CURRENT.get();
        if (id == null || thrown == null) {
            return;
        }
        com.craftingveloce.CraftingVeloceMod.LOGGER.warn(
                "[Veloce][CRAFT-TRACE][#" + id + "] WYJATEK w " + context + ": " + thrown,
                thrown);
    }

    // ------------------------------------------------------------------
    // Zrzuty - po jednej metodzie na jedno pytanie
    // ------------------------------------------------------------------

    /**
     * Co siec MA do zrobienia tego itemu: moduly (czy stoi maszyna, czy ma
     * prad, ile receptur), piec (zrodla, ile operacji, czy zasilone), craftery.
     */
    public static void dumpEnvironment(ServerLevel level, VelocePipeNetwork network, Item item) {
        if (!active()) {
            return;
        }
        log("--- SRODOWISKO sieci ---");
        log("siec: %s, terminale(wezly)=%d, endpointy=%d",
                network == null ? "BRAK" : network.getId(),
                network == null ? 0 : network.getTerminals().size(),
                network == null ? 0 : network.getEndpoints().size());
        if (network == null) {
            return;
        }

        log("moduly przetwarzania (id | maszyna stoi | zasilona | receptur na ten item):");
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            boolean available = module.available(level, network);
            boolean powered = module.powered(level, network);
            int recipes = module.recipesAnywhere(level, item).size();
            log("  - %-10s available=%-5s powered=%-5s recipes=%d  typy=%s",
                    module.id(), available, powered, recipes, typeNames(module.recipeTypes()));
        }

        List<VeloceHeatSource> heat = VeloceHeatSources.allIn(level, network);
        log("piec: zrodel=%d, hasAny=%s, hasPower=%s, suma operacji=%d",
                heat.size(), VeloceHeatSources.hasAnyHeatSource(level, network),
                VeloceHeatSources.hasPower(level, network),
                VeloceHeatSources.totalOperations(level, network));
        for (VeloceHeatSource source : heat) {
            log("  - %s: operacji=%d, zasilone=%s",
                    source.heatSourceName(), source.availableOperations(),
                    source.isPowered());
        }

        List<VeloceProcessingSource> machines = VeloceProcessingSources.allIn(level, network);
        log("maszyny modulow: %d", machines.size());
        for (VeloceProcessingSource machine : machines) {
            log("  - %s: typy=%s, operacji=%d, zasilona=%s",
                    machine.sourceName(), typeNames(machine.recipeTypes()),
                    machine.availableOperations(), machine.isPowered());
        }

        log("craftery: %d, item w zbiorze wlaczonych=%s",
                VeloceCraftingRegistry.crafters(level, network).size(),
                VeloceCraftingRegistry.getAllEnabledItems(level, network).contains(item));
    }

    /**
     * Wszystkie receptury na ten item, per zrodlo, ze skladnikami i liczbami
     * sztuk. To odpowiada na pytanie "czy w ogole wiemy, jak to zrobic".
     */
    public static void dumpRecipes(ServerLevel level, VelocePipeNetwork network, Item item) {
        if (!active()) {
            return;
        }
        log("--- RECEPTURY na %s (%s) ---", name(item), id(item));
        dumpOne("waniliowe (crafting/stonecutting/smithing)",
                VeloceRecipeRegistry.getRecipesFor(level, item));
        dumpOne("piec", VeloceRecipeRegistry.getFurnaceRecipesFor(level, item));
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            dumpOne("modul " + module.id(), module.recipesAnywhere(level, item));
        }
    }

    private static void dumpOne(String label, List<ProcessingEntry> recipes) {
        log("  %s: %d receptur(y)", label, recipes.size());
        for (ProcessingEntry recipe : recipes) {
            log("    * %s [%s] -> %s", recipe.id(), VeloceRecipeFinder.typeName(recipe.type()),
                    results(recipe));
            for (int i = 0; i < recipe.ingredients().size(); i++) {
                Ingredient ing = recipe.ingredients().get(i);
                log("        skladnik %d x%d: %s", i + 1, recipe.ingredientCount(i),
                        ingredientOptions(ing));
            }
        }
    }

    /** Stock dla itemu i jego skladnikow (z receptur powyzej), z limitem linii. */
    public static void dumpStock(Map<Item, Long> stock, Item item, List<ProcessingEntry> recipes) {
        if (!active()) {
            return;
        }
        long total = 0;
        for (long value : stock.values()) {
            total += value;
        }
        log("--- STOCK: %d roznych itemow, %d sztuk razem ---", stock.size(), total);
        log("  szukany %s: %d sztuk", name(item), stock.getOrDefault(item, 0L));
        int lines = 0;
        for (ProcessingEntry recipe : recipes) {
            for (int i = 0; i < recipe.ingredients().size(); i++) {
                for (ItemStack option : recipe.ingredients().get(i).getItems()) {
                    if (option.isEmpty() || lines++ > 60) {
                        continue;
                    }
                    long have = stock.getOrDefault(option.getItem(), 0L);
                    log("  skladnik %s: %d sztuk (potrzeba %d)", name(option.getItem()), have,
                            recipe.ingredientCount(i));
                }
            }
            if (lines > 60) {
                log("  ... (dalsze skladniki pomijam - limit linii)");
                break;
            }
        }
    }

    private static String results(ProcessingEntry recipe) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recipe.results().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(recipe.results().get(i).getCount()).append("x ")
                    .append(name(recipe.results().get(i).getItem()));
            sb.append(" @").append(recipe.resultChances().get(i));
        }
        return sb.toString();
    }

    private static String ingredientOptions(Ingredient ing) {
        ItemStack[] options = ing.getItems();
        if (options.length == 0) {
            return "(brak opcji - pusty tag)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.length && i < 6; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(id(options[i].getItem()));
        }
        if (options.length > 6) {
            sb.append(" | ... (").append(options.length).append(" opcji)");
        }
        return sb.toString();
    }

    private static String typeNames(Collection<RecipeType<?>> types) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (RecipeType<?> type : types) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(VeloceRecipeFinder.typeName(type));
            first = false;
        }
        return sb.append(']').toString();
    }

    public static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    public static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? String.valueOf(item) : key.toString();
    }
}
