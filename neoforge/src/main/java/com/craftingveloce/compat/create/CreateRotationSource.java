package com.craftingveloce.compat.create;

import com.craftingveloce.block.entity.VeloceRotationSources;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * Create's rotation source, configured from the compat package.
 *
 * <p><b>256 RPM, and the number matters.</b> Our kinetic machines need
 * {@code CreateKineticModules.REQUIRED_SPEED} = 256 RPM before they count as powered;
 * Create's creative motor defaults to SIXTEEN. A test rig with a default motor therefore
 * leaves the machine spinning and unpowered at the same time - which cost several rounds of
 * looking for a missing drive that was there all along.
 *
 * <p>The foreign type lives here, in the integration, and never in the core - see
 * {@link VeloceRotationSources} for why the core cannot hold it.
 */
public final class CreateRotationSource {

    /** Create's creative motor: an infinite, configurable source of rotation. */
    public static final ResourceLocation CREATIVE_MOTOR =
            ResourceLocation.fromNamespaceAndPath("create", "creative_motor");

    private CreateRotationSource() {
    }

    /** Registers the tuner (called once, from the Create gate). */
    public static void register() {
        VeloceRotationSources.register(CREATIVE_MOTOR, (level, pos) -> {
            if (level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity motor) {
                motor.generatedSpeed.setValue(256);
                motor.setChanged();
                motor.notifyUpdate();
            }
        });
    }
}
