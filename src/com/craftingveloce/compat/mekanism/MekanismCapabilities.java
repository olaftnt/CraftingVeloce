package com.craftingveloce.compat.mekanism;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Capability naszych maszyn modulu Mekanism.
 *
 * <p>Maszyna wystawia NeoForge {@code EnergyStorage} (FE), tak samo jak
 * Velocity Electric Furnace. Kabli Mekanism nie da sie tu uzyc bez tego -
 * one wlasnie pytaja o te capability.
 *
 * <p><b>Czego tu (jeszcze) nie ma.</b> Chemikaliow ({@code IChemicalHandler}).
 * To osobny, duzy etap: capability zyje w pakiecie implementacji Mekanism
 * (nie w samym API), wiec trzeba je odtworzyc po nazwie
 * {@code mekanism:chemical_handler}. Maszyny z tego etapu sa w 100% itemowe.
 */
public final class MekanismCapabilities {

    private MekanismCapabilities() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event ->
                event.registerBlockEntity(
                        Capabilities.EnergyStorage.BLOCK,
                        MekanismBlockEntities.CRUSHER_MODULE.get(),
                        (be, side) -> be));
    }
}
