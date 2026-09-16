package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.ProcessingEntry;
import com.craftingveloce.crafting.VeloceProcessingModule;
import com.craftingveloce.crafting.VeloceProcessingRegistry;
import com.craftingveloce.crafting.VeloceProcessingSources;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Modul Alchemistry w rdzeniu Veloce.
 *
 * <p>Rdzen pyta ten modul o to samo, co kazdy inny: co umie zrobic, czy jego
 * maszyna stoi w sieci, czy ma prad i jakie ma receptury. Reszta (planer,
 * liczenie liczb, kontroler) nie zna Alchemistry.
 *
 * <p>Rejestruje sie w {@code FMLCommonSetupEvent}, bo DeferredHoldery typow
 * receptur sa wiazane dopiero po zdarzeniach rejestracji.
 */
public final class AlchemistryModule implements VeloceProcessingModule {

    /** Jedna instancja - modul nie ma stanu. */
    private static final AlchemistryModule INSTANCE = new AlchemistryModule();

    private static final String ID = "alchemistry";

    private AlchemistryModule() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            VeloceProcessingRegistry.register(INSTANCE);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: modul maszyn zarejestrowany", ID);
        });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        return AlchemistryRecipeFamily.types();
    }

    /**
     * Itemy, ktore maszyny STOJACE w sieci potrafia zrobic.
     *
     * <p>Rodzina bez swojej maszyny nic nie doklada - sam compactor nie
     * obiecuje wynikow fuzji, nawet jesli mod jest obecny.
     */
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : recipeTypes()) {
            // Bez pradu maszyna nie jest dostepna dla gracza, wiec nie moze
            // pojawiac sie na liscie "co umiemy" (gracz: "jesli nie maja pradu,
            // to nie chcemy, zeby byly w sieci jako dostepne").
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(AlchemistryRecipeHarvest.index(level, type).keySet());
        }
        return out;
    }

    @Override
    public boolean available(ServerLevel level, VelocePipeNetwork network) {
        return VeloceProcessingSources.hasAnyOf(level, network, recipeTypes());
    }

    @Override
    public boolean powered(ServerLevel level, VelocePipeNetwork network) {
        return VeloceProcessingSources.hasPoweredAny(level, network, recipeTypes());
    }

    /**
     * Receptury na dany item - TYLKO z rodzin, ktorych maszyna jest zasilona.
     *
     * <p>Filtr per rodzina jest konieczny: zasilony compactor nie znaczy, ze
     * mozna uzywac receptur fuzji.
     */
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                            Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(AlchemistryRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    /**
     * Receptury na dany item BEZ patrzenia na maszyny i zasilanie.
     *
     * <p>Dla narzedzi diagnostycznych (komenda getitems): gracz pyta "jak to
     * sie robi", a nie "czy moge to teraz zrobic". Planer nadal uzywa
     * recipesFor, ktore wymaga maszyny i pradu.
     */
    @Override
    public List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            out.addAll(AlchemistryRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    @Override
    public void invalidate() {
        AlchemistryRecipeHarvest.invalidate();
    }
}
