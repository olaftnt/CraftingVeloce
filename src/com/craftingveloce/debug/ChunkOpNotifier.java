package com.craftingveloce.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Debug na czacie: raportuje operacje na itemach w NIEZALADOWANYCH chunkach.
 *
 * <p><b>Po co.</b> Gdy siec siega do skrzyni stojacej w chunku, ktory nie jest
 * symulowany, serwer musi ten chunk najpierw wczytac. To jest dokladnie ta
 * operacja, ktora potrafi zamrozic tick albo zawiesic zapis swiata - i ktora
 * jest niewidoczna z perspektywy gracza. Ten notifier pokazuje ja na czacie:
 * ktory chunk, przy jakim bloku, jaka operacja i ile to zajelo.
 *
 * <p><b>Nie spamuje.</b> Ta sama pozycja z ta sama operacja jest raportowana
 * najwyzej raz na {@link #THROTTLE_TICKS} tickow. Bez tego jedna seria
 * wyciagania itemow zalalaby czat setkami linii - a to nie diagnoza, tylko szum.
 *
 * <p>Domyslnie WYLACZONE. Wlacz komenda {@code /cv chunkdebug ops on}.
 */
public final class ChunkOpNotifier {

    /** Rodzaj operacji na zawartosci chunku. */
    public enum Op {
        EXTRACT("wyciagniecie"),
        INSERT("wlozenie");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Czy raportowac operacje. */
    private static boolean enabled = false;

    /** Ile tickow odstepu dla tej samej pozycji i operacji. */
    private static final int THROTTLE_TICKS = 100;

    /** (level, pos, op) -> tick ostatniego raportu. Slabe klucze na swiaty. */
    private static final Map<ServerLevel, Map<String, Long>> LAST_REPORT =
            new WeakHashMap<>();

    private ChunkOpNotifier() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            LAST_REPORT.clear();
        }
    }

    /**
     * Raportuje, ze operacja wymusila wczytanie chunku.
     *
     * @param chunkKey klucz chunku ({@link ChunkPos#asLong})
     * @param pos      pozycja bloku, do ktorego siegniemy
     */
    public static void reportLoad(ServerLevel level, long chunkKey, BlockPos pos, Op op) {
        if (!enabled) {
            return;
        }
        if (isThrottled(level, pos, op)) {
            return;
        }
        int cx = ChunkPos.getX(chunkKey);
        int cz = ChunkPos.getZ(chunkKey);

        boolean frozen = com.craftingveloce.network.pipe.VeloceChunkLoader.isFrozen();

        MutableComponent head = Component.literal("§8[§6Veloce§8] §e" + op.label()
                + " §7w §cNIEZALADOWANYM §7chunku §f[" + cx + ", " + cz + "]");
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(head, false);
            player.displayClientMessage(coordsLine(pos), false);
            if (frozen) {
                // To najwazniejszy przypadek: operacja w trakcie zapisu swiata
                // jest tym, co zawiesza zapis.
                player.displayClientMessage(Component.literal(
                        "§8    ! §4swiat jest ZAMROZONY (zapis/zamkniecie) - "
                                + "ta operacja moze zawiesic zapis"), false);
            }
        }
    }

    /** Linia z klikalnymi wspolrzednymi bloku - klik = teleport. */
    private static MutableComponent coordsLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        MutableComponent coords = Component.literal("§8    blok: §f" + plain)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Kliknij, aby sie teleportowac"))));
        return coords;
    }

    /**
     * Czy ten raport jest zbyt swiezy, zeby go powtorzyc.
     *
     * <p>Klucz zawiera pozycje I operacje, wiec wyciaganie i wkladanie w to
     * samo miejsce sa raportowane osobno - to dwie rozne rzeczy do diagnozy.
     */
    private static boolean isThrottled(ServerLevel level, BlockPos pos, Op op) {
        String key = pos.asLong() + ":" + op.name();
        Map<String, Long> seen = LAST_REPORT.computeIfAbsent(level, k -> new HashMap<>());
        long now = level.getGameTime();
        Long last = seen.get(key);
        if (last != null && now >= last && now - last < THROTTLE_TICKS) {
            return true;
        }
        seen.put(key, now);
        // Przy cofnietym czasie swiata licznik tez jest bez sensu - czyscimy,
        // zeby mapa nie rosla bez konca.
        if (last != null && now < last) {
            seen.clear();
            seen.put(key, now);
        }
        return false;
    }
}
