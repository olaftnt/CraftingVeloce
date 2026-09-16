package com.craftingveloce.client.gui;

import com.craftingveloce.crafting.VeloceCraftErrors;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Powody nieudanych prob z terminala - pokazywane w tooltipie itemu.
 *
 * <p><b>Problem, ktory to rozwiazuje.</b> Serwer meldowal powod na PASKU AKCJI,
 * ktorego w GUI terminala nie widac - gracz klikal item i nie dzialo sie nic
 * widocznego. Teraz powod trafia do tooltipa DOKLADNIE tego itemu, ktory probowal
 * wytworzyc ("Cannot craft: no furnace in the network", "Cannot craft: missing
 * iron ingot"...), wiec odpowiedz jest tam, gdzie gracz patrzy.
 *
 * <p><b>Klucz to ITEM, nie stos.</b> Tak samo jak liczniki sieci i jak grupowana
 * jest lista terminala - inaczej ten sam item z innym NBT nie znalazlby swojego
 * komunikatu.
 *
 * <p><b>Wpisy wygasaja</b> ({@link #LIFETIME_MS}): powod sprzed pol godziny
 * prawie zawsze przestal obowiazywac, a tooltip, ktory klamie, jest gorszy niz
 * brak tooltipa.
 */
public final class VeloceCraftErrorHints {

    /** Jak dlugo trzymamy powod, zanim uznamy go za nieaktualny. */
    private static final long LIFETIME_MS = 30_000L;

    /** Jeden zapamietany powod wraz z czasem zapisu. */
    private record Hint(String reason, String detail, long stamp) {
    }

    private final Map<Item, Hint> hints = new HashMap<>();

    /** Zapisuje powod nieudanej proby dla tego itemu. */
    public void record(ItemStack stack, String reason, String detail) {
        if (stack.isEmpty()) {
            return;
        }
        hints.put(stack.getItem(), new Hint(reason, detail, Util.getMillis()));
    }

    /**
     * Dopisuje czerwona linie z powodem, jesli ten item niedawno sie nie udal.
     *
     * <p>Nieaktualny wpis usuwamy przy okazji - to jedyne miejsce, ktore wie,
     * ze powod jest jeszcze potrzebny.
     */
    public void appendTo(List<Component> tooltip, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Hint hint = hints.get(stack.getItem());
        if (hint == null) {
            return;
        }
        if (Util.getMillis() - hint.stamp() > LIFETIME_MS) {
            hints.remove(stack.getItem());
            return;
        }
        tooltip.add(VeloceCraftErrors.message(hint.reason(), hint.detail(),
                stack.getHoverName().getString()).withStyle(ChatFormatting.RED));
    }

    /** Czysci pamiec - wolane przy zamykaniu ekranu. */
    public void clear() {
        hints.clear();
    }
}
