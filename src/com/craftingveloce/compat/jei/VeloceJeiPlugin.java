package com.craftingveloce.compat.jei;

import com.craftingveloce.compat.VeloceJeiCatalysts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin JEI: nasze klocki na liscie "w tym mozna zrobic ten przepis".
 *
 * <p><b>Co to daje.</b> JEI przy kazdym przepisie pokazuje po lewej stronie
 * ikonki maszyn, ktore go obsluguja (stol rzemieslniczy, crafter, formulatic
 * assembler, robot, terminal...). Ten plugin dopisuje do tych list NASZE klocki:
 * stol Veloce do przepisow wytwarzania, a moduly kinetyczne i na energie - do
 * kategorii Create, Mekanism i Alchemistry.
 *
 * <p><b>Dlaczego plugin NIE zna Create, Mekanism ani Alchemistry.</b> Zna tylko
 * UID kategorii i szuka gotowego typu przez JEI API
 * ({@link IJeiHelpers#getRecipeType(ResourceLocation)}), a nie przez klasy tych
 * modow. Trzy powody:
 * <ol>
 *   <li>plugin JEI jest skanowany przez JEI na starcie i zaladowany ZAWSZE, gdy
 *       JEI jest obecne - takze bez Create/Mekanism/Alchemistry. Odwolanie do
 *       ich klas konczyloby sie {@code NoClassDefFoundError} u kazdego gracza
 *       bez tych modow,</li>
 *   <li>typ kategorii obcego moda da sie zbudowac tylko z jego klasy przepisu,
 *       a {@code RecipeType.equals} porownuje TE KLASE - wiec samodzielnie
 *       sklecony typ nigdy nie trafilby w kategorie zarejestrowana przez tamten
 *       plugin (a szukanie po UID trafia w prawdziwy obiekt),</li>
 *   <li>UID kategorii to dane, nie kod: brak kategorii (np. wylaczona integracja
 *       JEI w tamtym modzie) jest zwyklym pominieciem, a nie crashem.</li>
 * </ol>
 *
 * <p><b>Faza rejestracji.</b> Katalizatory dodajemy w
 * {@code registerRecipeCatalysts}, czyli PO fazie {@code registerCategories}
 * wszystkich pluginow - dopiero wtedy JEI zna typy kategorii i
 * {@code getRecipeType(UID)} ma co zwrocic. Kategorie nieznalezione raportujemy
 * w logu, bo to jedyny sygnal, ze ktorys UID przestal sie zgadzac (np. po
 * aktualizacji tamtego moda).
 */
@JeiPlugin
public class VeloceJeiPlugin implements IModPlugin {

    /** Identyfikator naszego moda - ten sam co w {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "craftingveloce";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID + "-jei");

    /** UID tego pluginu (musi byc unikalny w calym JEI). */
    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        IJeiHelpers helpers = registration.getJeiHelpers();
        List<ResourceLocation> unknown = new ArrayList<>();
        int added = 0;
        for (VeloceJeiCatalysts.Catalyst catalyst : VeloceJeiCatalysts.all()) {
            Optional<RecipeType<?>> category = helpers.getRecipeType(catalyst.category());
            if (category.isEmpty()) {
                unknown.add(catalyst.category());
                continue;
            }
            registration.addRecipeCatalysts(category.get(), catalyst.item().get());
            added++;
        }
        LOGGER.info("[Veloce][JEI] katalizatory: {} dodanych, {} bez kategorii {}",
                added, unknown.size(), unknown);
    }
}
