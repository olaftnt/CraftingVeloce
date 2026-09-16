package com.craftingveloce.crafting;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;

/**
 * Status maszyny: pracuje / za malo sily / za malo energii.
 *
 * <p>Jedyne, co pokazuje Jade (gracz: "zostaw tylko powered/working, czyli
 * zasilane dziala albo nie ma wystarczajacej predkosci"). Korzysta z tego takze
 * okno maszyny, zeby napis byl ten sam w obu miejscach.
 */
public final class VeloceModuleStatus {

    private VeloceModuleStatus() {
    }

    /** Czy to maszyna na energie (a nie kinetyczna). */
    public static boolean isEnergy(CompoundTag info) {
        return info.contains("energy");
    }

    /** Czy maszyna ma to, czego potrzebuje do pracy. */
    public static boolean working(CompoundTag info) {
        return isEnergy(info) ? info.getBoolean("powered") : info.getBoolean("enoughSpeed");
    }

    /** Napis statusu - jedyna linia, jaka dokladamy w Jade. */
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
