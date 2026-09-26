package com.craftingveloce.compat.curios;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.ArrayList;
import java.util.List;

public final class VeloceCuriosCompat {
    private VeloceCuriosCompat() {}

    public static List<ItemStack> getEquippedCurios(Player player) {
        List<ItemStack> list = new ArrayList<>();
        try {
            CuriosApi.getCuriosInventory(player).ifPresent(inv -> {
                for (SlotResult slotResult : inv.findCurios(stack -> !stack.isEmpty())) {
                    list.add(slotResult.stack());
                }
            });
        } catch (Throwable ignored) {
        }
        return list;
    }
}
