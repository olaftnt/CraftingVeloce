package com.craftingveloce.crafting;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Teksty "dlaczego sie nie udalo" - jedno zrodlo dla kazdego miejsca, ktore
 * pokazuje powod nieudanego craftu.
 *
 * <p><b>Po co osobna klasa.</b> Powod ({@code reason}) i detal ({@code detail})
 * pochodza z planera i sa KLUCZAMI TLUMACZEN, a nie gotowym tekstem. Ten sam
 * powod pokazuje dzis tooltip itemu w terminalu, a wczesniej pokazywal go pasek
 * akcji - gdyby kazde miejsce sklejalo komunikat samo, klucze rozjechalyby sie
 * przy pierwszej zmianie (a rozjazd widac tylko w grze, jako surowy napis
 * "craftingveloce.craft.error.noFurnace").
 *
 * <p>Klient dostaje z serwera sam powod i detal, a tlumaczy je u SIEBIE - dzieki
 * temu tekst jest w jezyku gracza, a nie serwera.
 */
public final class VeloceCraftErrors {

    private VeloceCraftErrors() {
    }

    /**
     * Klucz komunikatu z detalem dla danego powodu planera.
     *
     * <p>Nie kazdy powod ma miejsce na detal: {@code noBase} i {@code extract}
     * mowia "brakuje X" / "nie udalo sie wziac X", wiec maja osobne warianty,
     * a {@code noModule} i {@code moduleUnpowered} maja to miejsce juz w sobie
     * (podstawiamy tam nazwe modulu albo maszyny).
     */
    public static String detailKey(String reason) {
        return switch (reason) {
            case "craftingveloce.craft.error.noBase" ->
                    "craftingveloce.craft.error.noBaseItem";
            case "craftingveloce.craft.error.extract" ->
                    "craftingveloce.craft.error.extractItem";
            default -> reason;
        };
    }

    /**
     * Komunikat bledu: najpierw powod z serwera, a dopiero gdy go nie ma -
     * ogolne "nie ma tego itemu w sieci".
     */
    public static MutableComponent message(String reason, String detail, String itemName) {
        if (!reason.isEmpty()) {
            if (!detail.isEmpty()) {
                return Component.translatable(detailKey(reason), detail);
            }
            return Component.translatable(reason);
        }
        return Component.translatable("craftingveloce.message.itemNotInNetwork", itemName);
    }
}
