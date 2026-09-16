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
            // Maszyna BEZ pradu nie jest "dostepna" - gracz nie moze z niej
            // korzystac, wiec nie moze tez pojawiac sie na liscie "co umiemy".
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            int side = VeloceProcessingSources.maxGridSide(level, network, type);
            int parts = VeloceProcessingSources.maxParts(level, network, type);
            CreateRecipeHarvest.index(level, type).forEach((item, entries) -> {
                for (ProcessingEntry entry : entries) {
                    if (entry.fitsGrid(side, parts)
                            && requirementsMet(level, network, type, entry)) {
                        out.add(item);
                        return;
                    }
                }
            });
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
            // Receptura z siatka wchodzi tylko wtedy, gdy maszyna ma dosc
            // zbudowanych pol (crafter mechaniczny buduje sie z oczek).
            int side = VeloceProcessingSources.maxGridSide(level, network, type);
            int parts = VeloceProcessingSources.maxParts(level, network, type);
            for (ProcessingEntry entry : CreateRecipeHarvest.forItem(level, type, item)) {
                if (entry.fitsGrid(side, parts) && requirementsMet(level, network, type, entry)) {
                    out.add(entry);
                }
            }
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

    /**
     * Wymagania dodatkowe receptury: cieplo i Basen.
     *
     * <p>Gracz opisal to wprost: "jak cos potrzebuje mixer, to mixer jest;
     * jesli tylko basin jest w jakimkolwiek inventory, to mamy basin
     * zaliczony, a jak musi byc heated blaze burner, to tez mamy zaliczone,
     * jesli tylko mamy blaze burner". Dlatego NIE budujemy modelu sieci
     * przeplywow - pytamy wylacznie o obecnosc przedmiotu w sieci.
     */
    private static boolean requirementsMet(ServerLevel level, VelocePipeNetwork network,
                                           RecipeType<?> type, ProcessingEntry entry) {
        if (entry.requiresHeat() && !hasItem(level, network, "blaze_burner")) {
            return false;
        }
        if (needsBasin(type) && !hasItem(level, network, "basin")) {
            return false;
        }
        return true;
    }

    /** Prasa i mixer pracuja na zawartosci Basenu - bez Basenu nie ma czego mieszac. */
    private static boolean needsBasin(RecipeType<?> type) {
        return type == CreateRecipeFamily.pressing() || type == CreateRecipeFamily.mixing();
    }

    /** Czy siec ma przedmiot z Create (w magazynie albo w buforze craftera). */
    private static boolean hasItem(ServerLevel level, VelocePipeNetwork network, String path) {
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", path));
        return item != net.minecraft.world.item.Items.AIR
                && network.getAllItemCounts(level).containsKey(item);
    }

    @Override
    public void invalidate() {
        CreateRecipeHarvest.invalidate();
    }
}
