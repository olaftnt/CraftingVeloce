package com.craftingveloce.compat.create;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.function.Supplier;

/**
 * ONE description of a Create kinetic machine - data, not a class.
 *
 * <p>Kinetic machines (millstone, saw, crusher, mechanical crafter) differ only
 * in recipe type, label and SU draw, so they use one block and one block
 * entity.
 *
 * <p><b>A machine may own MORE THAN ONE recipe type</b>, because Create's own machines
 * do. The Deployer is the case that matters: Create registers it as the catalyst for both
 * its "Deploying" and its "Manual Item Application" category, so a mod that lists only
 * one of the two is claiming the machine can do half of what it can do.
 *
 * <p><b>Why {@link Supplier} and not a ready recipe type.</b> Create recipe
 * types are DeferredHolders bound only after the registration events, and these
 * constants are created in the mod constructor - the type must be resolved
 * lazily.
 *
 * @param id           identifier for logs, e.g. {@code "create:milling"}
 * @param label        machine label (logs, messages)
 * @param recipeTypes  recipe types handled by the machine (lazily) - one or more
 * @param constantSu   constant SU pool drawn from the kinetic network (see
 *                     {@code VeloceKineticModuleBlockEntity.calculateStressApplied})
 */
public record KineticModule(String id, String label,
                            Supplier<java.util.Set<RecipeType<?>>> recipeTypes,
                            float constantSu) {
}
