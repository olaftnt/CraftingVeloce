package com.craftingveloce.block;

import com.craftingveloce.init.VeloceRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What is INSIDE the Veloce Integrale casing - for each of our blocks.
 *
 * <p><b>The rule.</b> Every one of our blocks (apart from the pipe and the
 * terminal) has the <b>Integrale casing</b> model (a rod frame + purple glass),
 * and inside it renders the model of the <b>base block</b>: a lectern becomes
 * the controller, a dispenser the extractor, an observer the threshold sensor,
 * and a furnace a furnace. The frame turned into a machine behaves the same way
 * (see {@link VeloceIntegraleConversions}) - the appearance and the conversions
 * come from one list, so they cannot drift apart.
 *
 * <p><b>Modules from {@code compat/}</b> add their rows through
 * {@link #register} (e.g. a casing with a Create crushing wheel inside), without
 * changing the core.
 */
public final class VeloceCaseContents {

    /**
     * A table row: our block -&gt; what to show in its casing.
     *
     * @param scale              relative size of the content (1.0 = default).
     *                           Machines larger than the casing window (crusher,
     *                           crafter, press, mixer, deployer) get a smaller
     *                           value - otherwise, during the animation, they
     *                           stick up above the glass.
     * @param pitch              additional rotation around the X axis in degrees
     *                           (0 = straight). The saw and the deployer should
     *                           point DOWN, so they get -90.
     * @param keepItemRotation   whether to KEEP the rotation from the item
     *                           transformation. By default we zero the tilt (the
     *                           content stands straight), but the crushing
     *                           wheel looks better in its own orientation.
     */
    public record Entry(Supplier<Block> machine, Supplier<Block> content, float scale,
                        float pitch, boolean keepItemRotation, boolean useItemModel) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        add(() -> VeloceRegistry.VELOCE_CRAFTING_TABLE.get(), () -> Blocks.CRAFTING_TABLE);
        add(() -> VeloceRegistry.VELOCE_CONTROLLER.get(), () -> Blocks.LECTERN);
        add(() -> VeloceRegistry.VELOCE_EXTRACTOR.get(), () -> Blocks.DISPENSER);
        add(() -> VeloceRegistry.THRESHOLD_SENSOR.get(), () -> Blocks.OBSERVER);
        add(() -> VeloceRegistry.VELOCITY_FURNACE.get(), () -> Blocks.FURNACE);
        add(() -> VeloceRegistry.ELECTRIC_FURNACE.get(), () -> Blocks.BLAST_FURNACE);
        add(() -> VeloceRegistry.BREWING_STAND.get(), () -> Blocks.BREWING_STAND);
    }

    private VeloceCaseContents() {
    }

    /**
     * Adds a table row (for modules from {@code compat/}).
     *
     * <p>Both our block and the content are given as a {@code Supplier}: the
     * block registry resolves lazily, and modules add rows before their blocks
     * exist.
     */
    public static void register(Supplier<Block> machine, Supplier<Block> content) {
        register(machine, content, 1.0F, 0.0F, false);
    }

    /** Like {@link #register(Supplier, Supplier)}, but with its own content size. */
    public static void register(Supplier<Block> machine, Supplier<Block> content, float scale) {
        register(machine, content, scale, 0.0F, false);
    }

    /** The variant with rotation (the saw and the deployer point down) and kept model rotation. */
    public static void register(Supplier<Block> machine, Supplier<Block> content, float scale,
                                float pitch, boolean keepItemRotation) {
        register(machine, content, scale, pitch, keepItemRotation, false);
    }

    /**
     * The full variant, including whether the casing shows the block's BLOCK model or
     * its ITEM model.
     *
     * <p>Block model by default, which is what a machine standing inside a window
     * should look like. The item model is the exception, for machines whose block model
     * is not a complete picture of the machine - measured, not guessed: Create's
     * {@code millstone/block} has 6 elements against 12 in {@code millstone/item}, and
     * {@code mechanical_saw} has no block model file at all, because those parts are
     * drawn by a separate renderer (Flywheel). Showing the block model there gives a
     * machine that is visibly missing its centre stone or its blade.
     */
    public static void register(Supplier<Block> machine, Supplier<Block> content, float scale,
                                float pitch, boolean keepItemRotation, boolean useItemModel) {
        ENTRIES.add(new Entry(machine, content, scale, pitch, keepItemRotation, useItemModel));
    }

    /** Whether the casing should draw this machine's item model instead of its block model. */
    public static boolean usesItemModel(BlockState state) {
        Entry entry = entryFor(state);
        return entry != null && entry.useItemModel();
    }

    /** Relative size of this machine's content (1.0, when nobody set it otherwise). */
    public static float contentScale(BlockState state) {
        Entry entry = entryFor(state);
        return entry == null ? 1.0F : entry.scale();
    }

    /** Additional rotation of the content around the X axis (0 = straight). */
    public static float contentPitch(BlockState state) {
        Entry entry = entryFor(state);
        return entry == null ? 0.0F : entry.pitch();
    }

    /** Whether the content should keep the rotation from the item transformation. */
    public static boolean keepsItemRotation(BlockState state) {
        Entry entry = entryFor(state);
        return entry != null && entry.keepItemRotation();
    }

    private static Entry entryFor(BlockState state) {
        Block block = state.getBlock();
        for (Entry entry : ENTRIES) {
            if (entry.machine().get() == block) {
                return entry;
            }
        }
        return null;
    }

    private static void add(Supplier<Block> machine, Supplier<Block> content) {
        register(machine, content);
    }

    /**
     * The block to show in the casing of this block, or {@code null} (there is
     * no casing - e.g. a pipe or a terminal).
     */
    public static Block contentFor(BlockState state) {
        return contentFor(state.getBlock());
    }

    /** The same by block alone (used among others by the uncrafting recipe). */
    public static Block contentFor(Block machine) {
        for (Entry entry : ENTRIES) {
            if (entry.machine().get() == machine) {
                return entry.content().get();
            }
        }
        return null;
    }

    /** All rows (tooltips, guards, documentation). */
    public static List<Entry> all() {
        return List.copyOf(ENTRIES);
    }
}
