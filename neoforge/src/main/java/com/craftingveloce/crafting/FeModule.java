package com.craftingveloce.crafting;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.function.Supplier;

/**
 * ONE description of an FE-powered module machine - data, not a class.
 *
 * <p><b>Why.</b> Module machines (crusher, enrichment, compactor, ...) differ
 * ONLY in three things: the recipe type, the energy cost and the label. A
 * separate block entity class for each would mean copying the same energy logic -
 * that is, exactly the kind of duplicate that has already drifted apart several
 * times in this project.
 *
 * <p><b>Why a {@link Supplier}, and not a ready recipe type.</b> Recipe types of
 * foreign mods are DeferredHolders, bound only after the registration events.
 * Instances of this record are created while loading the block classes (that is,
 * in the mod constructor), so the type MUST be resolved later - the supplier
 * does that lazily, at the moment of first use.
 *
 * @param id             identifier for logs, e.g. {@code "mekanism:crusher"}
 * @param label          machine label (logs, energy message)
 * @param fePerOperation cost of one operation in FE
 * @param capacity       accumulator capacity in FE
 * @param recipeType     recipe type handled by this machine (lazily)
 */
public record FeModule(String id, String label, int fePerOperation, int capacity,
                       Supplier<RecipeType<?>> recipeType) {

    /**
     * Cost of ONE operation of this machine, in FE.
     *
     * <p>The component above is the value the machine was BUILT with; this asks the config
     * first, so a pack can retune a single machine without touching another. A record may
     * override its own accessor, which is what lets every existing caller keep saying
     * {@code module.fePerOperation()} and get the configured number.
     */
    @Override
    public int fePerOperation() {
        return com.craftingveloce.config.VeloceBlockConfig.fePerOperationOf(id, fePerOperation);
    }

    /** Battery size of this machine, in FE - the configured one when there is one. */
    @Override
    public int capacity() {
        return com.craftingveloce.config.VeloceBlockConfig.capacityOf(id, capacity);
    }
}
