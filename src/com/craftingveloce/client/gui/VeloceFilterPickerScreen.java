package com.craftingveloce.client.gui;

import com.craftingveloce.network.SetFilterPKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class VeloceFilterPickerScreen extends VeloceCreativeScreen {

    private final BlockPos extractorPos;
    private final int filterIndex;

    @Nullable
    private GameType modeBeforeOpen;

    /**
     * Czy wybieramy filtr do PIECA PALIWOWEGO (wtedy liczy sie tylko paliwo).
     *
     * <p>Rozpoznajemy po bloku-gospodarzu, a nie po osobnym pakiecie: selektor
     * jest JEDEN dla ekstraktora, pieca i czujnika, wiec dokladanie do niego
     * "rodzaju bloku" znaczyloby trzy miejsca do zsynchronizowania przy kazdym
     * nowym bloku z filtrem.
     */
    private final boolean fuelOnly;

    private Map<Item, Long> networkCounts = new HashMap<>();

    public void updateNetworkCounts(Map<Item, Long> counts) {
        this.networkCounts = new HashMap<>(counts);
    }

    public VeloceFilterPickerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos extractorPos, int filterIndex) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.extractorPos = extractorPos;
        this.filterIndex = filterIndex;
        this.fuelOnly = isFuelOnlyHost(player, extractorPos);
    }

    /** Czy blok pod ta pozycja to piec paliwowy (filtr = filtr paliwa). */
    private static boolean isFuelOnlyHost(LocalPlayer player, BlockPos pos) {
        if (player == null || player.level() == null) {
            return false;
        }
        return player.level().getBlockState(pos).getBlock()
                instanceof com.craftingveloce.block.VeloceVelocityFurnaceBlock;
    }

    /** Czy ten stos nadaje sie na filtr (w trybie paliwa: tylko paliwo). */
    private boolean acceptable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // fuelOnly: paliwo. Wspolna regula z ekranem pieca - patrz
        // VeloceVelocityFurnaceBlockEntity.isUnusableFuelFilter (z kanarkiem).
        return !fuelOnly
                || !com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity
                        .isUnusableFuelFilter(stack);
    }

    @Override
    protected void init() {
        if (this.minecraft == null || this.minecraft.gameMode == null) {
            super.init();
            return;
        }
        if (!this.minecraft.gameMode.hasInfiniteItems()) {
            if (this.modeBeforeOpen == null) {
                this.modeBeforeOpen = this.minecraft.gameMode.getPlayerMode();
            }
            this.minecraft.gameMode.setLocalMode(GameType.CREATIVE);
        }
        // Uwaga: NIE ukrywamy tu slotow gracza po raz drugi.
        //
        // Bylo tu wlasne suppressHotbarSlots() z anonimowym Slotem, ktore
        // robilo dokladnie to samo co suppressPlayerSlots() z klasy bazowej
        // (i to po nim), a dodatkowo nie rozpoznawalo juz ukrytego slotu -
        // przez co przy kazdym init() zawijalo slot w nowy wrapper.
        super.init();
    }


    @Override
    public void containerTick() {
        // MUSI byc super. Baza (VeloceCreativeScreen) utrzymuje w tym miejscu
        // filtr listy itemow i ukrywanie zakladek administracyjnych. Wczesniej
        // ta metoda byla pusta, wiec po zmianie zakladki w tym oknie lista nie
        // byla juz filtrowana - picker pokazywal itemy, ktorych nie powinien
        // (i tracil spojnosc z reszta naszych GUI).
        super.containerTick();
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        super.renderSlot(graphics, slot);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();

            // W trybie "tylko paliwo" itemy, ktorych nie da sie przepalic, sa
            // zaznaczone NA CZERWONO - gracz widzi od razu, czego nie wybierze
            // (klik na taki item nic nie robi, patrz slotClicked).
            if (fuelOnly && !acceptable(stack)) {
                RenderSystem.disableDepthTest();
                graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x77AA0000);
                RenderSystem.enableDepthTest();
            }

            // Draw count overlay if this item is available in the network
            long count = networkCounts.getOrDefault(stack.getItem(), 0L);
            if (count > 0) {
                drawCountOverlay(graphics, this.font, count, slot.x, slot.y);
            }
        }
    }

    private void drawCountOverlay(GuiGraphics graphics, Font font, long count, int x, int y) {
        float scaleFactor = 0.6f;
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        String text = VeloceTerminalScreen.formatCount(count);
        graphics.pose().pushPose();
        graphics.pose().scale(scaleFactor, scaleFactor, scaleFactor);
        graphics.pose().translate(0, 0, 450);
        float inverseScale = 1.0f / scaleFactor;
        int textX = (int) (((float) x + 16.0f - font.width(text) * scaleFactor) * inverseScale);
        int textY = (int) (((float) y + 16.0f - 7.0f * scaleFactor) * inverseScale);
        // Bialy = ile jest na stanie (spojnie z terminalem).
        graphics.drawString(font, text, textX, textY, 0xFFFFFF, true);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Pasek hotbara jest nieuzywany - zamalowujemy go wspolnym helperem
        // z klasy bazowej, zeby geometria nie rozjechala sie z reszta GUI.
        drawHotbarCover(graphics, 0xFFC6C6C6);
    }

    /**
     * Wybor filtra NIE pamieta zakladki.
     *
     * <p>Ma sie zawsze otwierac na pierwszej zakladce (lewy gorny rog) -
     * gracz szuka tam konkretnego itemu, a nie wraca do miejsca sprzed
     * poprzedniego otwarcia.
     */
    @Override
    protected boolean rememberTab() {
        return false;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }

        // If clicking a player slot or outside, ignore
        if (slot == null || isPlayerInventorySlot(slot)) {
            return;
        }

        // Slot smieci - uzywamy wspolnego helpera z klasy bazowej, zamiast
        // powtarzac tu te same wspolrzedne (rozjechalyby sie przy zmianie ukladu).
        if (isTrashSlot(slot)) {
            return;
        }

        ItemStack item = slot.getItem();
        if (!item.isEmpty()) {
            // Nie nadaje sie na filtr w tym oknie (w piecu paliwowym: to nie
            // jest paliwo). NIC nie robimy - gracz zostaje w selektorze, ekran
            // sie nie zmienia, a filtr zostaje jaki byl.
            if (!acceptable(item)) {
                return;
            }
            // Selected this item as filter!
            ItemStack filterItem = item.copy();
            filterItem.setCount(1);
            PacketDistributor.sendToServer(new SetFilterPKT(extractorPos, filterIndex, filterItem));

            // WROC DO EKSTRAKTORA, a nie do gry.
            //
            // Bylo tu this.onClose(), ktore zamyka ekran calkowicie - gracz
            // wybieral item i ladowal w swiecie zamiast wrocic do klocka.
            com.craftingveloce.client.ClientTerminalHelper.reopenFilterHostScreen(extractorPos);
        }
    }

    @Override
    public void removed() {
        // Trzymany stos obsluguje teraz klasa bazowa (oddaje do ekwipunku
        // albo upuszcza). Wczesniej tutaj byl skasowany.
        super.removed();
        if (this.modeBeforeOpen != null && this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.setLocalMode(this.modeBeforeOpen);
            this.modeBeforeOpen = null;
        }
    }
}
