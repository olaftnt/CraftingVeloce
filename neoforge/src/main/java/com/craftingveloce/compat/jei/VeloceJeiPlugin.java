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
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JEI plugin: our blocks on the "this recipe can be made in this" list.
 *
 * <p><b>What it gives.</b> For every recipe JEI shows icons of the machines that
 * handle it on the left side (crafting table, crafter, formulaic assembler,
 * robot, terminal...). This plugin adds OUR blocks to those lists: the Veloce
 * table to crafting recipes, and the kinetic and energy modules to the Create,
 * Mekanism and Alchemistry categories.
 *
 * <p><b>Why the plugin does NOT know Create, Mekanism or Alchemistry.</b> It
 * only knows the category UID and looks up the ready-made type through the JEI
 * API ({@link IJeiHelpers#getRecipeType(ResourceLocation)}), not through the
 * classes of those mods. Three reasons:
 * <ol>
 *   <li>the JEI plugin is scanned by JEI at startup and loaded ALWAYS when JEI
 *       is present - also without Create/Mekanism/Alchemistry. Referring to
 *       their classes would end in a {@code NoClassDefFoundError} for every
 *       player without those mods,</li>
 *   <li>a foreign mod's category type can only be built from its recipe class,
 *       and {@code RecipeType.equals} compares THAT CLASS - so a type cobbled
 *       together on our own would never match a category registered by that
 *       plugin (whereas a lookup by UID hits the real object),</li>
 *   <li>the category UID is data, not code: a missing category (e.g. the JEI
 *       integration disabled in that mod) is an ordinary skip, not a crash.</li>
 * </ol>
 *
 * <p><b>The registration phase.</b> We add the catalysts in
 * {@code registerRecipeCatalysts}, that is AFTER the {@code registerCategories}
 * phase of all plugins - only then does JEI know the category types and
 * {@code getRecipeType(UID)} has something to return. We report categories that
 * were not found in the log, because that is the only signal that one of the
 * UIDs stopped matching (e.g. after an update of that mod).
 */
@JeiPlugin
public class VeloceJeiPlugin implements IModPlugin {

    /** Our mod identifier - the same as in {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "craftingveloce";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID + "-jei");

    /** UID of this plugin (it must be unique within the whole of JEI). */
    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    /**
     * Hides the potion proxies from JEI.
     *
     * <p><b>Why they exist at all.</b> The network counts its stock by {@code Item}
     * ({@code getAllItemCounts} returns a {@code Map<Item, Long>}), so every potion STATE
     * needs an item of its own: a real water bottle and a real night vision potion are
     * both {@code minecraft:potion} and differ only by a data component, which the
     * storage cannot tell apart. The proxies are that plumbing - one item per state, 138
     * of them - and the network converts to them on the way in and back to real potions
     * on the way out.
     *
     * <p><b>Why they must not be seen.</b> They are not items a player can obtain or use.
     * Left visible they appear in JEI as a second copy of every potion in the game, with
     * no contents and no use - a duplicate the player cannot explain, and rightly does
     * not want. This plugin registers no category for the brewing recipe type, so nothing
     * is lost by hiding them: JEI never showed how a potion is brewed in the first place.
     */
    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        java.util.List<net.minecraft.world.item.ItemStack> proxies = new java.util.ArrayList<>();
        for (net.minecraft.world.item.Item item : com.craftingveloce.init.VelocePotionProxies.created()) {
            proxies.add(new net.minecraft.world.item.ItemStack(item));
        }
        for (var proxy : com.craftingveloce.init.VeloceRegistry.handAuthoredProxyItems()) {
            proxies.add(new net.minecraft.world.item.ItemStack(proxy.get()));
        }
        if (proxies.isEmpty()) {
            return;
        }
        // Logged for the same reason the creative tab logs its contents: an ingredient
        // that fails to hide is invisible in a different sense - it silently shows up as
        // a second copy of every potion in the game, and nothing anywhere says so.
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce] JEI: hiding {} potion proxies (internal network plumbing)", proxies.size());
        registration.getIngredientVisibility().hideIngredients(
                mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                proxies,
                java.util.Set.of(mezz.jei.api.ingredients.subtypes.UidContext.Ingredient));
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
            // One line per catalyst: JEI/terminal parity means the same machine must
            // advertise the same categories in both places, and a missing catalyst is
            // invisible in game (the recipe simply shows no machine).
            LOGGER.debug("[VELOCE-DEBUG] JEI catalyst: {} -> category {}",
                    catalyst.item().get(), catalyst.category());
        }
        LOGGER.info("[Veloce][JEI] catalysts: {} added, {} without a category {}",
                added, unknown.size(), unknown);
        if (!unknown.isEmpty()) {
            // A category we name but JEI does not know means a typo or a mod that did
            // not register its recipe type - the machine silently stops appearing.
            LOGGER.warn("[VELOCE-DEBUG] JEI categories with no registered recipe type: {} "
                    + "(those machines will not show as a catalyst anywhere)", unknown);
        }
    }
}
