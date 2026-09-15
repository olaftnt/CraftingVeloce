package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceVelocityFurnaceMenu;
import com.craftingveloce.network.OpenFilterPKT;
import com.craftingveloce.network.SetFilterPKT;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI Velocity Furnace.
 *
 * <p><b>Uklad (zgodnie z ustaleniem):</b> plomyk jak w zwyklym piecu + szesc
 * slotow filtra (jak w ekstraktorze) na dopuszczalne paliwa, plus realny slot
 * paliwa, ktore piec wlasnie spala.
 *
 * <p><b>Plomyk pokazuje BUFOR CIEPLA</b>, a nie "postep przepalania". Ten piec
 * nie ma jednego przepalanego przedmiotu - pali sie bez przerwy, a kazde
 * przepalenie dla craftera zjada od razu porcje tego buforu. Wysokosc plomyka
 * to wiec {@code burnTicksRemaining / burnTicksTotal}, czyli dokladnie to, ile
 * ciepla zostalo do rozdania.
 *
 * <p>Filtry sa WIDMAMI: nie da sie ich wypelnic przeciaganiem. Wybiera sie je
 * z listy itemow (lewy klik na pustym slocie), a trzyma je block entity.
 */
public class VeloceVelocityFurnaceScreen
        extends AbstractContainerScreen<VeloceVelocityFurnaceMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/velocity_furnace.png");

    /** Tekstura waniliowego pieca - z niej bierzemy WYGASZONY obrys plomienia. */
    private static final ResourceLocation VANILLA_FURNACE = ResourceLocation
            .withDefaultNamespace("textures/gui/container/furnace.png");

    /**
     * ZAPALONA czesc plomienia - waniliowy sprite (osobny plik od 1.20.2).
     *
     * <p><b>BUG, ktory to naprawia.</b> Wczesniej bralem plomien ze starej
     * tekstury panelu pod (176, 0). W 1.21 wanilia przeniosla ten sprite do
     * {@code textures/gui/sprites/container/furnace/lit_progress.png} i pod
     * (176, 0) w panelu nie ma NIC - rysowala sie wiec PUSTKA, a jedynym
     * sladem plomienia byl tekst w tooltipie. Teraz idziemy ta sama droga co
     * waniliowy piec: sprite {@code minecraft:container/furnace/lit_progress}.
     */
    private static final ResourceLocation LIT_PROGRESS_SPRITE = ResourceLocation
            .withDefaultNamespace("container/furnace/lit_progress");

    /** Wygaszony obrys plomienia w teksturze waniliowego pieca (jak w wanilii). */
    private static final int EMPTY_FLAME_U = 56;
    private static final int EMPTY_FLAME_V = 36;

    /** Plomien: 14x14 - dokladnie jak w waniliowym piecu. */
    private static final int FLAME_W = 14;
    private static final int FLAME_H = 14;

    private final List<ItemStack> clientFilters =
            new ArrayList<>(java.util.Collections.nCopies(
                    VeloceVelocityFurnaceMenu.FILTER_SLOTS, ItemStack.EMPTY));

    /** Bufor ciepla z ostatniej aktualizacji - plomyk nie moze migac. */
    private long burnRemaining;
    private long burnTotal;

    public VeloceVelocityFurnaceScreen(VeloceVelocityFurnaceMenu menu,
                                       Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // Wymiary i etykiety DOKLADNIE jak w ekstraktorze - oba ekrany maja
        // wygladac jak jedna rodzina, a nie jak dwa rozne mody.
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;
        for (int i = 0; i < clientFilters.size(); i++) {
            clientFilters.set(i, menu.getFilter(i));
        }
        if (menu.getFurnace() != null) {
            this.burnRemaining = menu.getFurnace().getBurnTicksRemaining();
            this.burnTotal = menu.getFurnace().getBurnTicksTotal();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        renderFlame(graphics);
        renderFilterIcons(graphics);
    }

    /**
     * Plomyk: najpierw wygaszony, potem zapalona czesc od DOLU.
     *
     * <p>Dokladnie tak rysuje to waniliowy piec - dlatego sciezka "od dolu"
     * jest wazna: plomien ma gasnac z gory, bo tak wyglada ogien.
     */
    private void renderFlame(GuiGraphics graphics) {
        int x = this.leftPos + FLAME_X;
        int y = this.topPos + FLAME_Y;

        // 1. Wygaszony obrys - z tekstury waniliowego pieca, dokladnie ten
        //    fragment, nad ktorym wanilia rysuje zapalona czesc.
        graphics.blit(VANILLA_FURNACE, x, y, EMPTY_FLAME_U, EMPTY_FLAME_V,
                FLAME_W, FLAME_H);

        // 2. Zapalona czesc - waniliowy sprite, przycinany OD GORY.
        //
        // Wzor jest przepisany z AbstractFurnaceScreen:
        //     lit = floor(progress * 13) + 1
        //     blitSprite(sprite, 14, 14, 0, 14 - lit, x, y + 14 - lit, 14, lit)
        // Dlatego plomien opada z gory, a nie rosnie od dolu - tak gasnie ogien.
        if (burnTotal > 0 && burnRemaining > 0) {
            int lit = net.minecraft.util.Mth.floor(
                    Math.min(1.0f, (float) burnRemaining / (float) burnTotal) * 13.0F) + 1;
            lit = net.minecraft.util.Mth.clamp(lit, 1, FLAME_H);
            graphics.blitSprite(LIT_PROGRESS_SPRITE, FLAME_W, FLAME_H,
                    0, FLAME_H - lit, x, y + FLAME_H - lit, FLAME_W, lit);
        }
    }

    /** Ikony filtrow - rysujemy je sami, bo sloty sa widmami. */
    private void renderFilterIcons(GuiGraphics graphics) {
        for (int i = 0; i < clientFilters.size(); i++) {
            ItemStack filter = clientFilters.get(i);
            if (filter.isEmpty()) {
                continue;
            }
            int sx = this.leftPos + filterX(i);
            int sy = this.topPos + filterY(i);
            graphics.renderFakeItem(filter, sx, sy);
            // UWAGA: nie ma tu juz fioletowych paskow nad i pod ikona.
            //
            // BUG, ktory to naprawia: rysowalem je od sx-1 do sx+17, czyli
            // o piksel POZA slot - nachodzily na sasiednia kratke i wygladaly
            // jak przypadkowe fioletowe podkreslenie (dokladnie to zglosil
            // uzytkownik). Sam wybrany item jest wystarczajaco widoczny.
        }
    }

    // Polozenie pol - JEDNO zrodlo, uzywane i do rysowania, i do klikania.
    private static final int FILTER_X = 63;
    private static final int FILTER_Y = 18;
    // Plomien NAD slotem paliwa - uklad: filtry 3x2 | (plomien / paliwo).
    // Pozycja musi sie zgadzac z menu i z gen_furnace_gui.py (sprawdza build.py).
    private static final int FLAME_X = 135;
    private static final int FLAME_Y = 19;

    private static int filterX(int index) {
        return FILTER_X + (index % 3) * 18;
    }

    private static int filterY(int index) {
        return FILTER_Y + (index / 3) * 18;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHeatTooltip(graphics, mouseX, mouseY);
        renderFilterTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Na plomyku - TYLKO ile tickow palenia zostalo.
     *
     * <p>Wczesniej byl tu opis z liczba przepalen i podpowiedzia; uzytkownik
     * chcial dokladnie jednej liczby. Plomien jest ikonka waniliowa, wiec
     * tooltip ma tylko dopowiedziec to, czego z ikonki nie widac.
     */
    private void renderHeatTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(FLAME_X, FLAME_Y, FLAME_W, FLAME_H, mouseX, mouseY)) {
            return;
        }
        // Sam stosunek: ile tickow ZOSTALO z tego, co dodal ten itemek.
        // Bez zadnego "left to burn" - tak prosil uzytkownik.
        graphics.renderTooltip(this.font,
                Component.translatable("gui.craftingveloce.furnace.ticks",
                        burnRemaining, burnTotal),
                mouseX, mouseY);
    }

    private void renderFilterTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = 0; i < clientFilters.size(); i++) {
            if (!isHovering(filterX(i), filterY(i), 16, 16, mouseX, mouseY)) {
                continue;
            }
            ItemStack filter = clientFilters.get(i);
            List<Component> lines = new ArrayList<>();
            if (filter.isEmpty()) {
                // Numer slotu, jak w ekstraktorze - gracz wie, ktory filtr ustawia.
                lines.add(Component.translatable("gui.craftingveloce.furnace.filterEmpty", i + 1));
            } else {
                lines.add(filter.getHoverName());
                lines.add(Component.translatable("gui.craftingveloce.furnace.filterClear")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            lines.add(Component.translatable("gui.craftingveloce.furnace.filterHint")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            return;
        }
    }

    /**
     * Klikniecia w filtry.
     *
     * <p>Zachowanie takie samo jak w ekstraktorze, bo to ten sam wybor:
     * <ul>
     *   <li>kursor z itemem -> ustaw ten item jako filtr (nie zabierajac go),</li>
     *   <li>lewy klik na zajetym -> skasuj filtr,</li>
     *   <li>lewy klik na pustym -> otworz wybor itemu.</li>
     * </ul>
     * Prawy klik nie jest tu do niczego potrzebny - piec nie ma per-filtr
     * przelacznika auto-craftingu, wiec nie udajemy, ze ma.
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        int filterIndex = filterIndexFor(slot);
        if (filterIndex >= 0) {
            ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()) {
                // Z RĘKI: filtr ma sens tylko dla paliwa. Item, ktorego nie da
                // sie przepalic, NIE zostaje przyjety - akcja po prostu sie nie
                // dzieje (filtr bez zmian, item zostaje na kursorze).
                if (com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity
                        .isUnusableFuelFilter(carried)) {
                    return;
                }
                setFilter(filterIndex, carried);
                return;
            }
            if (!clientFilters.get(filterIndex).isEmpty()) {
                setFilter(filterIndex, ItemStack.EMPTY);
                return;
            }
            if (mouseButton == 0) {
                PacketDistributor.sendToServer(
                        new OpenFilterPKT(this.menu.getPos(), filterIndex));
            }
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    /** Numer filtra dla slotu-widma, albo -1 gdy to nie slot filtra. */
    private int filterIndexFor(Slot slot) {
        if (slot == null || slot.container == this.minecraft.player.getInventory()) {
            return -1;
        }
        return slot.index >= 0 && slot.index < VeloceVelocityFurnaceMenu.FILTER_SLOTS
                ? slot.index
                : -1;
    }

    private void setFilter(int index, ItemStack stack) {
        ItemStack single = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        clientFilters.set(index, single);
        PacketDistributor.sendToServer(
                new SetFilterPKT(this.menu.getPos(), index, single));
    }

    /**
     * Odswieza plomyk i filtry z block entity.
     *
     * <p><b>Dlaczego bez osobnego pakietu.</b> Menu trzyma block entity
     * klienta, a piec wysyla swoj stan na biezaco w blokowym pakiecie
     * aktualizacji - wiec ekran ma pelne dane POD RECE. Osobny pakiet
     * "zaktualizuj plomyk" bylby druga, rownolegla droga po te same liczby,
     * czyli dokladnie tym, co sie rozjezdza.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        var be = this.menu.getFurnace();
        if (be == null) {
            return;
        }
        this.burnRemaining = be.getBurnTicksRemaining();
        this.burnTotal = be.getBurnTicksTotal();
        for (int i = 0; i < clientFilters.size(); i++) {
            clientFilters.set(i, be.getFuelFilter(i));
        }
    }
}
