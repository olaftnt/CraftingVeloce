package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.FeModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Capability naszych maszyn modulu Alchemistry.
 *
 * <p>Maszyna wystawia NeoForge {@code EnergyStorage} (FE), tak samo jak
 * Velocity Electric Furnace i maszyny modulu Mekanism. Kable Alchemistry (albo
 * dowolne inne) pytaja wlasnie o te capability.
 *
 * <p><b>Czego tu nie ma.</b> Capability plynow - maszyny z tego etapu sa
 * w 100% itemowe (dissolver i liquifier/atomizer dochodza z warstwa plynow).
 */
public final class AlchemistryCapabilities {

    private AlchemistryCapabilities() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            // Jedna petla po wszystkich maszynach: nowa maszyna w
            // AlchemistryFeModules.ALL dostaje capability sama.
            for (FeModule module : AlchemistryFeModules.ALL) {
                event.registerBlockEntity(
                        Capabilities.EnergyStorage.BLOCK,
                        AlchemistryBlockEntities.holderFor(module).get(),
                        (be, side) -> be);
            }
        });
    }
}
