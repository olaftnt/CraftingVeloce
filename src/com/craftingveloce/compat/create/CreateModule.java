package com.craftingveloce.compat.create;

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
 * Modul Create w rdzeniu Veloce.
 *
 * <p>Rdzen pyta ten modul o to samo, co kazdy inny: co umie zrobic, czy maszyna
 * stoi w sieci, czy jest napedzana i jakie ma receptury. Reszta (planer,
 * liczenie liczb, kontroler) nie zna Create.
 *
 * <p>Rejestruje sie w {@code FMLCommonSetupEvent}, bo DeferredHoldery typow
 * receptur Create sa wiazane dopiero po zdarzeniach rejestracji.
 */
public final class CreateModule implements VeloceProcessingModule {

    /** Jedna instancja - modul nie ma stanu. */
    private static final CreateModule INSTANCE = new CreateModule();

    private static final String ID = "create";

    private CreateModule() {
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
        return CreateRecipeFamily.types();
    }

    /**
     * Itemy, ktore maszyny STOJACE w sieci potrafia zrobic.
     *
     * <p>Rodzina bez swojej maszyny nic nie doklada - sam mlynek nie obiecuje
     * wynikow kruszarki, nawet jesli Create jest obecne.
     */
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasAny(level, network, type)) {
                continue;
            }
            out.addAll(CreateRecipeHarvest.index(level, type).keySet());
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
     * Receptury na dany item - TYLKO z rodzin, ktorych maszyna jest NAPEDZANA.
     *
     * <p>Filtr per rodzina jest konieczny: krecacy sie mlynek nie znaczy, ze
     * mozna uzywac receptur kruszarki.
     */
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                            Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(CreateRecipeHarvest.forItem(level, type, item));
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
            out.addAll(CreateRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    @Override
    public void invalidate() {
        CreateRecipeHarvest.invalidate();
    }
}
