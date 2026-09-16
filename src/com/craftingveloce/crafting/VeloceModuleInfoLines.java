package com.craftingveloce.crafting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Opis modulu (predkosc/SU albo energia + siec) jako lista linii - JEDNO zrodlo.
 *
 * <p><b>Po co osobna klasa.</b> Te same informacje pokazuja dzis DWA miejsca:
 * okno modulu po prawym kliku ({@code VeloceModuleInfoScreen}) i tooltip Jade,
 * gdy gracz patrzy na maszyne. Zanim powstala ta klasa, linie skladalo GUI, a
 * Jade mialby wlasna, druga wersje - czyli dwa opisy tych samych liczb, ktore
 * rozjada sie przy pierwszej zmianie (dokladnie ten blad powtarza sie w tym
 * projekcie przy kazdym recznie pisanym spisie).
 *
 * <p><b>Wejscie to gotowy NBT</b> z serwera ({@code VeloceModuleInfoSource.moduleInfo}):
 * okno dostaje go pakietem, Jade - swoim mechanizmem danych serwera. Dzieki temu
 * klient nic nie liczy ani nie dopywytuje i nie musi znac typow Create.
 *
 * <p>Pierwsza linia to naglowek (energii albo predkosci) - GUI rysuje pod nia
 * pasek baterii, dlatego {@link #isEnergy(CompoundTag)} mowi, ktory to wariant.
 */
public final class VeloceModuleInfoLines {

    private VeloceModuleInfoLines() {
    }

    /** Czy opis dotyczy modulu na energie (a nie kinetycznego). */
    public static boolean isEnergy(CompoundTag info) {
        return info.contains("energy");
    }

    /** Gotowe linie opisu - dokladnie te, ktore pokazuje GUI i Jade. */
    public static List<Component> build(CompoundTag info) {
        List<Component> lines = new ArrayList<>();
        if (isEnergy(info)) {
            lines.add(header("gui.craftingveloce.module.info.energy",
                    info.getLong("energy"), info.getLong("energyCapacity")));
            lines.add(text("gui.craftingveloce.module.info.operations",
                    info.getLong("operations"), info.getLong("fePerOperation")));
        } else {
            float speed = info.getFloat("speed");
            int required = info.getInt("requiredSpeed");
            lines.add(header("gui.craftingveloce.module.info.speed", speed, required, required));
            lines.add(text("gui.craftingveloce.module.info.speedRequired", required));
            lines.add(text("gui.craftingveloce.module.info.stress",
                    info.getFloat("suDraw"), info.getFloat("suCapacity"),
                    info.getFloat("suNeeded")));
            lines.add(text("gui.craftingveloce.module.info.parts", info.getInt("parts")));
        }
        lines.add(Component.empty());
        lines.add(header("gui.craftingveloce.module.info.network",
                info.getInt("networkNodes"), info.getInt("networkStorages"),
                info.getInt("networkItems")));
        lines.add(status(info));
        return lines;
    }

    /**
     * Status pracy - "za malo sily" / "brak pradu" albo "pracuje".
     *
     * <p>Ten sam warunek co po stronie serwera ({@code hasEnoughRotationSpeed} /
     * {@code powered}), wiec opis nie kłamie: gdy mowi "za malo", maszyna
     * naprawde nie pracuje - i odwrotnie.
     */
    private static Component status(CompoundTag info) {
        boolean working = isEnergy(info)
                ? info.getBoolean("powered")
                : info.getBoolean("enoughSpeed");
        if (working) {
            return Component.translatable("gui.craftingveloce.module.info.ok")
                    .withStyle(ChatFormatting.DARK_GREEN);
        }
        return Component.translatable(isEnergy(info)
                ? "gui.craftingveloce.module.info.noPower"
                : "gui.craftingveloce.module.info.notEnough")
                .withStyle(ChatFormatting.DARK_RED);
    }

    /** Naglowek sekcji - pogrubiony, bez koloru (czytelny i na jasnym GUI, i w tooltipie). */
    private static Component header(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.BOLD);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
