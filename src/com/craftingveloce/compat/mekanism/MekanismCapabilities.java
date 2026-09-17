package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.FeModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Capability of our Mekanism module machines.
 *
 * <p>The machine exposes the NeoForge {@code EnergyStorage} (FE), just like the
 * Velocity Electric Furnace. Mekanism cables cannot be used here without it -
 * they are precisely what asks for this capability.
 *
 * <p><b>What is (still) missing here.</b> Chemicals ({@code IChemicalHandler}).
 * That is a separate, large stage: the capability lives in the Mekanism
 * implementation package (not in the API itself), so it has to be recreated by
 * the name {@code mekanism:chemical_handler}. The machines from this stage are
 * 100% item-based.
 */
public final class MekanismCapabilities {

    private MekanismCapabilities() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            // A single loop over all machines: a new machine in
            // MekanismFeModules.ALL
            // gets the capability on its own, without adding itself here.
            for (FeModule module : MekanismFeModules.ALL) {
                event.registerBlockEntity(
                        Capabilities.EnergyStorage.BLOCK,
                        MekanismBlockEntities.holderFor(module).get(),
                        (be, side) -> be);
            }
        });
    }
}
