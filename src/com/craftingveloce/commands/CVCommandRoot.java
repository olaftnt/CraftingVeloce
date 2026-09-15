package com.craftingveloce.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Wspolny korzen komend {@code /cv} - JEDNO miejsce decydujace o uprawnieniach.
 *
 * <p><b>Dlaczego osobna klasa, a nie {@code Commands.literal("cv")} w kazdym
 * pliku.</b> Komendy {@code /cv} sa rejestrowane w TRZECH plikach
 * ({@code /cv debug}, {@code /cv testnet}, {@code /cv trace}) i Brigadier scala
 * je w jeden wezel o nazwie {@code cv}. Gdyby kazdy plik deklarowal wlasny
 * warunek dostepu, wezly moglyby sie nie zlaczyc albo (gorzej) jeden z nich
 * po cichu zdecydowalby o dostepie do wszystkich - a wykrylibysmy to dopiero
 * po tym, jak komenda przestalaby dzialac albo zaczela dzialac za szeroko.
 * To dokladnie ta klasa bledu, ktora w tym projekcie trafiala sie wielokrotnie:
 * ta sama regula zapisana recznie w kilku miejscach, ktora z czasem sie rozjezdza.
 *
 * <p><b>Dlaczego w ogole wymog uprawnien.</b> Wczesniej zadna z tych komend nie
 * miala {@code requires(...)}, czyli domyslnie mogli je wywolywac WSZYSCY gracze
 * na serwerze. A {@code /cv testnet} wymusza przebudowe sieci rur, {@code /cv
 * chunk cleanup} zmienia stan swiata, a {@code /cv trace} wlacza szczegolowe
 * logowanie - to sa operacje administracyjne, nie dla kazdego.
 *
 * <p>Poziom 2 to standard wanilii dla komend administracyjnych (np. {@code
 * /gamemode}). W trybie singleplayer wlasciciel swiata ma go zawsze, gdy swiat
 * ma wlaczone cheaty - a bez tego nie mialby jak korzystac z wlasnej diagnostyki.
 */
public final class CVCommandRoot {

    private CVCommandRoot() {
    }

    /** Poziom uprawnien wymagany do komend {@code /cv} (2 = operator/cheaty). */
    public static final int REQUIRED_PERMISSION_LEVEL = 2;

    /** Korzen {@code /cv} z wymogiem uprawnien. Uzywaj TEGO, nie wlasnego literal(). */
    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("cv").requires(CVCommandRoot::mayUse);
    }

    public static boolean mayUse(CommandSourceStack source) {
        return source.hasPermission(REQUIRED_PERMISSION_LEVEL);
    }
}
