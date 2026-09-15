package com.craftingveloce.compat.mekanism;

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
 * Modul Mekanism w rdzeniu Veloce.
 *
 * <p><b>To jest cala integracja od strony rdzenia.</b> Reszta rdzenia
 * (planer, liczenie liczb, kontroler) nie wie, ze istnieje Mekanism - pyta
 * tylko ten modul o to samo, co kazdy inny:
 * <ul>
 *   <li>{@link #producible} - co umiem zrobic (tylko rodziny, ktorych maszyna
 *       stoi w sieci; inaczej kruszarka bez piły obiecywalaby deski),</li>
 *   <li>{@link #available} / {@link #powered} - czy maszyna stoi i czy ma prad,</li>
 *   <li>{@link #recipesFor} - konkretne receptury z liczbami sztuk.</li>
 * </ul>
 *
 * <p>Rejestruje sie w {@code FMLCommonSetupEvent}, bo typy receptur Mekanism
 * (DeferredHoldery) sa wiazane dopiero po zdarzeniach rejestracji.
 */
public final class MekanismModule implements VeloceProcessingModule {

    /** Jedna instancja - modul nie ma stanu. */
    private static final MekanismModule INSTANCE = new MekanismModule();

    private MekanismModule() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            VeloceProcessingRegistry.register(INSTANCE);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: modul maszyn zarejestrowany", ID);
        });
    }

    private static final String ID = "mekanism";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        return MekanismRecipeFamily.types();
    }

    /**
     * Itemy, ktore maszyny STOJACE w sieci potrafia zrobic.
     *
     * <p>Rodzina bez swojej maszyny nie doklada nic: kruszarka nie umie
     * pilowac, wiec receptury sawing nie moga sie liczyc jako produkowalne,
     * nawet jesli sam mod jest obecny.
     */
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasAny(level, network, type)) {
                continue;
            }
            out.addAll(MekanismRecipeHarvest.index(level, type).keySet());
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
     * <p>Filtr per rodzina jest tu konieczny: sam fakt, ze modul jest "zasilony"
     * (bo gdzies stoi kruszarka z pradem), nie znaczy, ze mozna uzyc receptur
     * pilowania - do tego trzeba piły.
     */
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                            Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(MekanismRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    @Override
    public void invalidate() {
        MekanismRecipeHarvest.invalidate();
    }
}
