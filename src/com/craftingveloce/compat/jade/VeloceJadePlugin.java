package com.craftingveloce.compat.jade;

import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade plugin: a description of our machines in the tooltip at the crosshair.
 *
 * <p><b>What this gives.</b> The player looks at a machine and sees the same as
 * in the window after a right click: current/required/maximum speed, SU draw,
 * the number of elements inserted, the accumulator status (for FE machines), the
 * pipe network state and the status - including <b>"not enough power"</b>.
 * Previously we drew that status with our own text in the middle of the screen;
 * the player told us to throw it out and do it through Jade ("that mod that
 * shows what you are looking at").
 *
 * <p><b>Registration without knowing other mods.</b> The data goes through
 * {@code VeloceModuleInfoSource} (a core interface), and not through
 * Create/Mekanism/Alchemistry types:
 * <ul>
 *   <li>server: a data provider for <b>all</b> block entities
 *       ({@code BlockEntity.class}, exactly as Jade itself does) and it asks
 *       for data only when the BE implements our interface
 *       ({@code shouldRequestData}) - which is why there is not a single type
 *       from the machine modules here,</li>
 *   <li>client: a component for all blocks with the same filter.</li>
 * </ul>
 * Thanks to that the plugin also works when there is NOTHING from those mods
 * (in which case there simply are no machines it could describe).
 */
@WailaPlugin
public class VeloceJadePlugin implements IWailaPlugin {

    /** The shared UID of the providers (Jade requires a unique identifier). */
    public static final ResourceLocation MODULE_INFO_UID =
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "module_info");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(VeloceModuleDataProvider.INSTANCE,
                net.minecraft.world.level.block.entity.BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(VeloceModuleComponentProvider.INSTANCE,
                net.minecraft.world.level.block.Block.class);
    }
}
