package com.craftingveloce.util;

import net.minecraft.core.BlockPos;

/**
 * Sprawdzanie "czy to juz czas na okresowa prace" - odporne na faze ticku.
 *
 * <p><b>Problem, ktory to rozwiazuje.</b> W modzie bylo kilka miejsc, ktore
 * robily to tak:
 *
 * <pre>
 *   if (level.getGameTime() % 20 == 0) { zrobCos(); }
 * </pre>
 *
 * <p>To wyglada niewinnie, ale jest bledne wszedzie tam, gdzie wywolujacy
 * <b>nie</b> trafia w kazdy tick. Konkretny przypadek, ktory to ujawnil:
 * {@code tickIdle} w cache wolane jest tylko raz na tick, i tylko przez ten
 * terminal, ktory akurat trafil (blokada {@code claimTick}). Jesli terminal
 * wola w tickach 1, 6, 11, 16, 21..., to w wielokrotnosc 20 <b>nie trafi
 * nigdy</b> - i wymuszanie chunkow nie zdarzylo sie ANI RAZU. Objaw w grze:
 * "wymuszonych chunkow: 0" mimo stojacego terminala, a bez force-loadu
 * terminal i crafter przestaja pracowac, gdy gracz odejdzie od bazy.
 *
 * <p><b>Rozwiazanie.</b> Mierzymy ODSTEP od ostatniego wykonania, a nie
 * rownosc z wielokrotnoscia. Dziala niezaleznie od tego, w ktorych tickach
 * wolajacy trafia.
 *
 * <p><b>Rozproszenie.</b> Drugi problem z {@code % N == 0} jest taki, ze
 * WSZYSTKIE bloki robia swoja okresowa prace w TYM SAMYM ticku - przy
 * kilkudziesieciu blokach to jeden skok obciazenia na sekunde zamiast pracy
 * rozlozonej rownomiernie. Dlatego oferujemy tez wariant z rozproszeniem po
 * pozycji bloku: kazdy blok dostaje wlasna faze, ale jego wlasny odstep
 * pozostaje zachowany.
 */
public final class VeloceTick {

    private VeloceTick() {
    }

    /**
     * Czy minelo juz {@code interval} tickow od {@code lastRun}.
     *
     * <p>Wariant bezstanowy - wolajacy sam trzyma znacznik czasu. Uzywaj go,
     * gdy klasa i tak ma juz pole na ostatni tick.
     *
     * <p>Uwaga na cofniety czas swiata (wczytanie starszego save'a): gdy
     * {@code now < lastRun}, uznajemy ze czas "przewinieto" i pozwalamy
     * wykonac prace od razu, zamiast czekac w nieskonczonosc.
     */
    public static boolean every(long now, long lastRun, long interval) {
        if (interval <= 0) {
            return true;
        }
        if (lastRun == Long.MIN_VALUE) {
            return true;   // nigdy nie bylo - zrob teraz
        }
        if (now < lastRun) {
            return true;   // czas sie cofnal - nie blokuj na zawsze
        }
        return now - lastRun >= interval;
    }

    /**
     * Czy minelo juz {@code interval} tickow - z rozproszeniem po pozycji.
     *
     * <p>Rozproszenie sprawia, ze bloki nie wykonuja pracy w tym samym ticku.
     * Faza jest wyliczana z pozycji, wiec jest stabilna miedzy uruchomieniami
     * i taka sama dla tego samego bloku.
     *
     * <p>Odstep jest zachowany: po wykonaniu pracy blok odczekuje pelne
     * {@code interval} tickow, a nie "czeka do najblizszej pasujacej fazy".
     */
    public static boolean everySpread(long now, long lastRun, long interval, BlockPos pos) {
        // Zabezpieczenie PRZED `now % interval`: dla odstepu 0 lub mniejszego
        // to zwykle dzielenie przez zero. `every()` ma taki sam warunek, a ten
        // wariant wolal modulo WCZESNIEJ, wiec byl od niego mniej odporny.
        if (interval <= 0) {
            return true;
        }
        if (lastRun == Long.MIN_VALUE) {
            // Pierwszy raz: rozkladamy start w czasie, zeby nie wszystkie
            // bloki zrobily prace w jednym ticku.
            return now % interval == phase(pos, interval);
        }
        return every(now, lastRun, interval);
    }

    /**
     * Faza tego bloku w cyklu o dlugosci {@code interval}.
     *
     * <p>Hash pozycji jest mieszany, zeby sasiednie bloki nie wypadaly w tej
     * samej fazie - inaczej cala sciana rur robilaby prace naraz.
     */
    private static long phase(BlockPos pos, long interval) {
        int h = pos.getX() * 73856093 ^ pos.getY() * 19349663 ^ pos.getZ() * 83492791;
        // floorMod, a nie %, bo hash moze byc ujemny - a faza musi byc z zakresu.
        //
        // RZUTOWANIE NA int MUSI byc sprawdzone: dla odstepu wiekszego niz
        // Integer.MAX_VALUE daloby 0, a wtedy floorMod rzuca
        // ArithmeticException (dzielenie przez zero). Wszyscy obecni wolajacy
        // podaja male stale (20), ale ta metoda jest w gorącej sciezce rur -
        // wyjatek tutaj zabilby tick serwera, a nie jedna operacje.
        if (interval <= 0 || interval > Integer.MAX_VALUE) {
            return 0L;
        }
        return Math.floorMod(h, (int) interval);
    }
}
