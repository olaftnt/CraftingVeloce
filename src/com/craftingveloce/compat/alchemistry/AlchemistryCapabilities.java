package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.FeModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Capability of our Alchemistry module machines.
 *
 * <p>The machine exposes NeoForge {@code EnergyStorage} (FE), just like the
 * Velocity Electric Furnace and the Mekanism module machines. Alchemistry cables
 * (or any other ones) ask for exactly this capability.
 *
 * <p><b>What is not here.</b> The fluid capability - the machines from this stage
 * are 100% item-based (the dissolver and the liquifier/atomizer come with the
 * fluid layer).
 */
public final class AlchemistryCapabilities {

    private AlchemistryCapabilities() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            // One loop over all machines: a new machine in
            // AlchemistryFeModules.ALL gets the capability by itself.
            for (FeModule module : AlchemistryFeModules.ALL) {
                event.registerBlockEntity(
                        Capabilities.EnergyStorage.BLOCK,
                        AlchemistryBlockEntities.holderFor(module).get(),
                        (be, side) -> be);
            }
        });
    }
}
