package com.craftingveloce.item;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;

import java.util.Optional;

/**
 * A network proxy for one potion state, made at registration time.
 *
 * <p><b>Why these cannot be hand-written.</b> The mod used to carry nineteen proxy
 * items, one per potion somebody had typed out - which covered a single container.
 * Every other state the game can brew (the extended and level-II variants, and every
 * splash and lingering potion) had no proxy, and since the network is keyed by
 * {@code Item}, a potion without its own item cannot be stored, planned or handed
 * over at all. That is what kept 250 of the game's 281 mixes unreachable.
 *
 * <p><b>Why the name is computed.</b> A generated item has no lang file entry. It
 * does not need one: the potion registry already names every potion, and vanilla's
 * own keys ({@code item.minecraft.potion.effect.night_vision} and the splash and
 * lingering spellings) already exist. {@link Potion#getName} resolves them, so a
 * generated proxy reads "Splash Potion of Night Vision" without a single new
 * translation.
 */
public class VelocePotionProxyItem extends Item {

    /** The proxy-table key this item stands for, e.g. {@code splash_night_vision}. */
    private final String proxyKey;

    private final Holder<Potion> potion;

    /** Vanilla's name prefix for this container - the half of the lang key before the potion. */
    private final String namePrefix;

    public VelocePotionProxyItem(Item.Properties properties, String proxyKey,
                                 Item container, Holder<Potion> potion) {
        super(properties);
        this.proxyKey = proxyKey;
        this.potion = potion;
        if (container == Items.SPLASH_POTION) {
            this.namePrefix = "item.minecraft.splash_potion.effect.";
        } else if (container == Items.LINGERING_POTION) {
            this.namePrefix = "item.minecraft.lingering_potion.effect.";
        } else {
            this.namePrefix = "item.minecraft.potion.effect.";
        }
    }

    public String proxyKey() {
        return proxyKey;
    }

    /**
     * The translation key this proxy answers to.
     *
     * <p>Not {@code getName}: vanilla's own {@link PotionItem} overrides the id and lets
     * {@code Item.getName} wrap it in a translatable component, so the client resolves
     * the name through the language file it already has. Returning a literal component
     * here instead would work in English and stay English everywhere else.
     */
    @Override
    public String getDescriptionId(ItemStack stack) {
        return Potion.getName(Optional.of(potion), namePrefix);
    }
}
