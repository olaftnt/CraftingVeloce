package com.craftingveloce.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Nakladki na slotach: ile sztuk jest na stanie i ile da sie dorobic.
 *
 * <p><b>Po co osobna klasa.</b> Terminal rysowal te dwie liczby poprawnie,
 * a kontroler mial wlasna, uproszczona wersje - bez skalowania czcionki i bez
 * tego samego zakotwiczenia. Efekt byl taki, jak zglosil gracz: liczby
 * w kontrolerze wychodzily poza ikonke albo ich nie bylo widac.
 *
 * <p>Zamiast poprawiac druga kopie, oba ekrany korzystaja z TEGO kodu. Liczby
 * wygladaja wiec identycznie w terminalu i w kontrolerze, bo to doslownie ta
 * sama funkcja rysujaca - a nie dwie, ktore maja sie zgadzac.
 *
 * <p>Uklad (ustalony wczesniej i celowo niezmieniony):
 * <ul>
 *   <li><b>stan</b> - bialy, prawy dolny rog slotu,</li>
 *   <li><b>do dorobienia</b> - pomaranczowy, lewy gorny rog, z "+" gdy sie zmiesci.</li>
 * </ul>
 */
public final class VeloceSlotOverlay {

    private VeloceSlotOverlay() {
    }

    /** Skala czcionki w nakladce. Waniliowy rozmiar nie zmiescilby sie w slocie. */
    private static final float SCALE = 0.6f;

    /** Ile znakow miesci sie w nakladce na slocie. */
    private static final int MAX_OVERLAY_CHARS = 5;

    /** Kolor stanu - bialy, jak liczba stosu w wanilii. */
    private static final int COLOR_STOCK = 0xFFFFFF;

    /** Kolor "do dorobienia" - pomaranczowy, zeby odroznic od stanu. */
    private static final int COLOR_CRAFTABLE = 0xFFA500;

    /**
     * Skrocona liczba: 1K, 1.2K, 1M, 1.2M, 1B.
     *
     * <p><b>Regula zyje w JEDNYM miejscu</b>
     * ({@link com.craftingveloce.util.VeloceFormat#compact(long)}) - tutaj
     * zostaje tylko nazwa uzywana przez GUI. Wczesniej ta metoda miala wlasna
     * kopie formatowania obok {@code VeloceFormat}, a dwie kopie tej samej
     * reguly zawsze sie w koncu rozjezdzaja.
     */
    public static String formatCount(long number) {
        return com.craftingveloce.util.VeloceFormat.compact(number);
    }

    /** Stan magazynu: bialy, prawy dolny rog slotu. Zero nie jest rysowane. */
    public static void drawStock(GuiGraphics graphics, Font font, long count, int x, int y) {
        if (count <= 0) {
            return;
        }
        String text = formatCount(count);
        pushOverlay(graphics);
        graphics.pose().scale(SCALE, SCALE, SCALE);
        graphics.pose().translate(0, 0, 450);
        float inverse = 1.0f / SCALE;
        int textX = (int) (((float) x + 16.0f - font.width(text) * SCALE) * inverse);
        int textY = (int) (((float) y + 16.0f - 7.0f * SCALE) * inverse);
        graphics.drawString(font, text, textX, textY, COLOR_STOCK, true);
        popOverlay(graphics);
    }

    /**
     * Liczba sztuk, ktore da sie DOROBIC (np. "+12"), lewy gorny rog slotu.
     *
     * <p>Plus tylko wtedy, gdy zmiesci sie w 5 znakach RAZEM z liczba.
     * Slot ma miejsce na 5 znakow: "+1234" wchodzi, "+12.3K" nie - wiec
     * pokazujemy samo "12.3K". Dzieki temu liczba nigdy nie wychodzi poza
     * ikonke, a plus nadal odroznia "da sie dorobic" od zwyklego stanu,
     * dopoki jest miejsce.
     */
    public static void drawCraftable(GuiGraphics graphics, Font font, long craftable, int x, int y) {
        if (craftable <= 0) {
            return;
        }
        String number = formatCount(craftable);
        String text = (number.length() + 1 <= MAX_OVERLAY_CHARS) ? "+" + number : number;

        pushOverlay(graphics);
        graphics.pose().scale(SCALE, SCALE, SCALE);
        graphics.pose().translate(0, 0, 450);
        float inverse = 1.0f / SCALE;
        int textX = (int) (((float) x + 1.0f) * inverse);
        int textY = (int) (((float) y + 1.0f) * inverse);
        graphics.drawString(font, text, textX, textY, COLOR_CRAFTABLE, true);
        popOverlay(graphics);
    }

    private static void pushOverlay(GuiGraphics graphics) {
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        graphics.pose().pushPose();
    }

    private static void popOverlay(GuiGraphics graphics) {
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }
}
