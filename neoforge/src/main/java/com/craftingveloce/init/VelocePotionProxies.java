package com.craftingveloce.init;

import com.craftingveloce.item.VelocePotionProxyItem;
import com.craftingveloce.util.VelocePotionMapper;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a proxy item for every potion state the game has, at registration time.
 *
 * <p><b>Why at the ITEM event, and why that is enough.</b> Vanilla potions are
 * already in {@code BuiltInRegistries.POTION} when the ITEM event fires - measured,
 * not assumed: the event sees all 46 of them. So the whole vanilla tree can be
 * covered here. A potion registered by ANOTHER mod arrives in the POTION event,
 * which fires after this one, so those are counted and reported at the end rather
 * than silently missing.
 *
 * <p><b>Why not a crafted pool of generic items.</b> The network counts by
 * {@code Item} ({@code getAllItemCounts} returns a {@code Map<Item, Long>}), so a
 * single "dynamic potion" item carrying the potion id in a component would collapse
 * every unknown potion into one stock entry and hand the player whichever it found
 * first. One item per state is not a preference here, it is the only shape the
 * storage can express.
 */
public final class VelocePotionProxies {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-potion-proxy");

    /** Containers a brewing stand works with - all three, because brewing reaches them all. */
    private static final List<Item> CONTAINERS =
            List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private static final List<Item> CREATED = new ArrayList<>();

    private VelocePotionProxies() {
    }

    /** The proxies this class registered - the client needs them to bake a model. */
    public static List<Item> created() {
        return List.copyOf(CREATED);
    }

    public static void register(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ITEM)) {
            return;
        }
        int created = 0;
        int kept = 0;
        for (Holder<Potion> potion : BuiltInRegistries.POTION.holders().toList()) {
            String potionPath = potion.unwrapKey().map(key -> key.location().getPath()).orElse(null);
            if (potionPath == null) {
                // A potion holder with no key cannot be named, so it cannot be proxied
                // either - our recipe ids are derived from that name.
                continue;
            }
            for (Item container : CONTAINERS) {
                String key = VelocePotionMapper.containerPrefix(container) + potionPath;
                if (VeloceRegistry.handAuthoredProxyKeys().contains(key)
                        || VelocePotionMapper.hasProxy(key)) {
                    // The nineteen hand-authored proxies keep their own names, models
                    // and lang entries - and, more urgently, their own registry names:
                    // generating potion_water as well would be a duplicate item key and
                    // the game would refuse to start.
                    kept++;
                    continue;
                }
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                        "craftingveloce", "potion_" + key);
                event.register(Registries.ITEM, id, () -> {
                    VelocePotionProxyItem item =
                            new VelocePotionProxyItem(new Item.Properties(), key, container, potion);
                    // Registered from inside the supplier: the mapper needs the ITEM,
                    // and the ITEM does not exist until the registry asks for it.
                    VelocePotionMapper.registerProxy(key, item);
                    CREATED.add(item);
                    return item;
                });
                created++;
            }
        }
        LOG.info("potion proxies: {} generated, {} hand-authored kept "
                        + "(a potion another mod registers arrives too late for an item to be made for it)",
                created, kept);
    }
}
