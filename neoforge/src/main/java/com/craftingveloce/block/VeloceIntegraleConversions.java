package com.craftingveloce.block;

import com.craftingveloce.init.VeloceRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the Veloce Integrale frame turns into what: the "vanilla block -&gt; our
 * block" table.
 *
 * <p><b>How it works.</b> The player places the frame and, with a right click,
 * inserts the appropriate vanilla block into it. The frame <b>replaces itself</b>
 * with our machine (see {@link VeloceIntegraleBlock#convert}) - a real Veloce
 * block then stands in the world, not a dummy or an "exhibit".
 *
 * <p><b>The key is an ID, not a block reference.</b> Blocks from other mods may
 * not exist yet at the moment the gate registers its entries (mod registration
 * order is not ours), and in that case a reference would be an empty block and
 * a click on the casing would do NOTHING. We look the ID up only at the moment
 * of use.
 *
 * <p><b>ONE place with this rule.</b> The mapping comes from the old project
 * (InventoryExchange), where the controller was made from a lectern, the
 * extractor from a dispenser, and the sensor from an observer - and there it
 * was scattered across recipes. Here there is one table, so:
 * <ul>
 *   <li>adding a machine is one row (or {@link #register} from a compat
 *       module, when the input block comes from another mod),</li>
 *   <li>the frame item's tooltip is generated from the same table
 *       ({@code VeloceIntegraleItem}), so it cannot drift apart from it.</li>
 * </ul>
 */
public final class VeloceIntegraleConversions {

    /**
     * A single conversion: what the player inserts -&gt; which Veloce block is
     * made from it.
     *
     * <p>A {@code Supplier} (and not the block itself) for two reasons: the
     * block registry resolves lazily, and modules from {@code compat/} add their
     * rows before their blocks exist.
     */
    public record Conversion(ResourceLocation inputId, Supplier<Block> result) {

        /** The block that is made from this input. */
        public Block resultBlock() {
            return result.get();
        }
    }

    private static final List<Conversion> CONVERSIONS = new ArrayList<>();

    static {
        // The order = the order in the frame item's tooltip.
        add(Blocks.CRAFTING_TABLE, () -> VeloceRegistry.VELOCE_CRAFTING_TABLE.get());
        add(Blocks.LECTERN, () -> VeloceRegistry.VELOCE_CONTROLLER.get());
        add(Blocks.DISPENSER, () -> VeloceRegistry.VELOCE_EXTRACTOR.get());
        add(Blocks.OBSERVER, () -> VeloceRegistry.THRESHOLD_SENSOR.get());
        add(Blocks.FURNACE, () -> VeloceRegistry.VELOCITY_FURNACE.get());
        // The second furnace takes the SECOND vanilla furnace, so the two do not
        // compete for the same block: velocity burns fuel, electric has an accumulator,
        // and a player holding a plain furnace means the fuel one.
        add(Blocks.BLAST_FURNACE, () -> VeloceRegistry.ELECTRIC_FURNACE.get());
        add(Blocks.BREWING_STAND, () -> VeloceRegistry.BREWING_STAND.get());
    }

    private VeloceIntegraleConversions() {
    }

    /**
     * Adds a conversion to the table.
     *
     * <p>For modules from {@code compat/}: the input block may come from a
     * foreign mod, while the result is our block - thanks to that an integration
     * does not touch the core (see {@code VeloceMods}).
     */
    public static void register(ResourceLocation inputId, Supplier<Block> result) {
        CONVERSIONS.add(new Conversion(inputId, result));
    }

    /**
     * An entry for an owned block (vanilla, our blocks): we take its ID.
     *
     * <p>Vanilla and our blocks are registered before anyone reaches for this
     * table, so the ID is certain.
     */
    private static void add(Block input, Supplier<Block> result) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(input);
        if (id != null) {
            register(id, result);
        }
    }

    /**
     * The conversion for the item in hand, or {@code null} (absent = nothing happens).
     *
     * <p>We also check that the target block really exists: rows added from
     * {@code compat/} modules may point at a block that is not present in this
     * session (the mod is missing) - in that case it is better to do NOTHING
     * than to replace the frame with nothing and eat the block the player
     * inserted.
     */
    public static Conversion forItem(ItemStack stack) {
        Conversion conversion = stack.getItem() instanceof BlockItem blockItem
                ? forBlock(blockItem.getBlock())
                : null;
        return conversion != null && conversion.resultBlock() != null ? conversion : null;
    }

    /** The conversion for a block, or {@code null}. */
    public static Conversion forBlock(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return null;
        }
        for (Conversion conversion : CONVERSIONS) {
            if (conversion.inputId().equals(id)) {
                return conversion;
            }
        }
        return null;
    }

    /** All conversions (item tooltip, documentation, tests). */
    public static List<Conversion> all() {
        return CONVERSIONS.stream().filter(entry -> entry.resultBlock() != null).toList();
    }
}
