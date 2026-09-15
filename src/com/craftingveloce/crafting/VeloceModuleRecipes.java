package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Receptury modulow (z innych modow) dla danego itemu.
 *
 * <p><b>Po co osobna klasa.</b> Planer musi widziec receptury WSZYSTKICH
 * modulow tak samo jak receptury waniliowe - inaczej automat nigdy nie uzyje
 * kruszarki czy compaktora, mimo ze stoja w sieci. To miejsce zbiera je w jeden
 * strumien, pytajac wylacznie interfejs {@link VeloceProcessingModule}:
 * <ol>
 *   <li>modul musi byc DOSTEPNY (stoi jego maszyna) i ZASILONY (ma prad/paliwo) -
 *       inaczej jego receptury nie sa wykonalne i nie moga trafic do planu,</li>
 *   <li>modul sam tlumaczy swoja recepture na wspolny {@link ProcessingEntry}.</li>
 * </ol>
 *
 * <p>Dzieki temu rdzen nie zna ani jednej nazwy z obcego moda - dodanie
 * kolejnego modulu to rejestracja i implementacja receptur, bez zmian tutaj.
 *
 * <p><b>Pamiec na czas ticku.</b> Pytanie "ktore moduly sa zasilone" wymaga
 * przejscia po wezlach sieci i odczytu ich block entity. Planer zadaje je przy
 * KAZDYM rozpatrywanym itemie (a przy liczeniu liczb - setki razy na jedno
 * klikniecie), wiec bez pamieci byloby to samo, co kiedys zrobiono z piecem:
 * setki skanow sieci w jednym ticku. W obrebie jednego ticku i jednej sieci
 * odpowiedz sie nie zmienia.
 */
public final class VeloceModuleRecipes {

    private VeloceModuleRecipes() {
    }

    private static VelocePipeNetwork cacheNetwork;
    private static long cacheTick = Long.MIN_VALUE;
    private static List<VeloceProcessingModule> cacheModules = List.of();

    /** Receptury wszystkich zasilonych modulow, ktore wytwarzaja {@code item}. */
    public static List<ProcessingEntry> forItem(ServerLevel level, VelocePipeNetwork network,
                                                Item item) {
        if (item == null) {
            return List.of();
        }
        List<VeloceProcessingModule> active = activeModules(level, network);
        if (active.isEmpty()) {
            return List.of();
        }
        List<ProcessingEntry> out = new ArrayList<>();
        for (VeloceProcessingModule module : active) {
            List<ProcessingEntry> recipes = module.recipesFor(level, item);
            if (recipes != null && !recipes.isEmpty()) {
                out.addAll(recipes);
            }
        }
        return out;
    }

    /** Czy choc jeden zasilony modul ma recepture na ten item. */
    public static boolean hasAny(ServerLevel level, VelocePipeNetwork network, Item item) {
        return !forItem(level, network, item).isEmpty();
    }

    /** Moduly, ktore maja w tej sieci maszyne i moga nia teraz zaplacic. */
    private static List<VeloceProcessingModule> activeModules(ServerLevel level,
                                                             VelocePipeNetwork network) {
        long now = level.getGameTime();
        if (network == cacheNetwork && now == cacheTick) {
            return cacheModules;
        }
        List<VeloceProcessingModule> out = new ArrayList<>();
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            if (module.available(level, network) && module.powered(level, network)) {
                out.add(module);
            }
        }
        cacheNetwork = network;
        cacheTick = now;
        cacheModules = out;
        return out;
    }

    /** Czysci pamiec - wolane przy zmianie swiata, tak jak inne cache'y. */
    public static void invalidate() {
        cacheNetwork = null;
        cacheTick = Long.MIN_VALUE;
        cacheModules = List.of();
    }
}
