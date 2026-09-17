package com.craftingveloce.crafting;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;

/**
 * Machine status: working / not enough force / not enough energy.
 *
 * <p>The only thing Jade shows (player: "leave just powered/working, that is,
 * whether the powered one works or does not have enough speed"). The machine
 * window also uses this, so that the text is the same in both places.
 */
public final class VeloceModuleStatus {

    private VeloceModuleStatus() {
    }

    /** Whether this is an energy machine (and not a kinetic one). */
    public static boolean isEnergy(CompoundTag info) {
        return info.contains("energy");
    }

    /** Whether the machine has what it needs to work. */
    public static boolean working(CompoundTag info) {
        return isEnergy(info) ? info.getBoolean("powered") : info.getBoolean("enoughSpeed");
    }

    /** The status text - the only line we add in Jade. */
    public static MutableComponent message(CompoundTag info) {
        if (working(info)) {
            return Component.translatable("gui.craftingveloce.module.info.ok")
                    .withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable(isEnergy(info)
                ? "gui.craftingveloce.module.info.noPower"
                : "gui.craftingveloce.module.info.notEnough")
                .withStyle(ChatFormatting.RED);
    }
}
