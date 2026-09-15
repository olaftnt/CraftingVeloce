package com.craftingveloce.client.gui;

import com.craftingveloce.network.ExtractorSetFilterPKT;
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
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class VeloceFilterPickerScreen extends VeloceCreativeScreen {

    private final BlockPos extractorPos;
    private final int filterIndex;

    @Nullable
    private GameType modeBeforeOpen;

    private Map<Item, Long> networkCounts = new HashMap<>();

    public void updateNetworkCounts(Map<Item, Long> counts) {
        this.networkCounts = new HashMap<>(counts);
    }

    private static Field slotWrapperTargetField;

    static {
        try {
            Class<?> wrapperClass = Class.forName("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper");
            for (Field f : wrapperClass.getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    slotWrapperTargetField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public VeloceFilterPickerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos extractorPos, int filterIndex) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.extractorPos = extractorPos;
        this.filterIndex = filterIndex;
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
        super.init();
        suppressHotbarSlots();
    }

    private void suppressHotbarSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) return;
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (isPlayerInventorySlot(s)) {
                // Replace with a dummy inactive slot offscreen
                final Slot orig = s;
                this.menu.slots.set(i, new Slot(orig.container, orig.getContainerSlot(), -10000, -10000) {
                    @Override
                    public boolean isActive() {
                        return false;
                    }
                    @Override
                    public boolean isHighlightable() {
                        return false;
                    }
                });
            }
        }
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
        // Draw count overlay if this item is available in the network
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
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
        graphics.drawString(font, text, textX, textY, 0x55FF55, true);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Blank out the bottom hotbar area (x: 8 to 170, y: 111 to 130) with solid GUI gray
        int x1 = this.leftPos + 8;
        int y1 = this.topPos + 111;
        int x2 = this.leftPos + 170;
        int y2 = this.topPos + 130;
        graphics.fill(x1, y1, x2, y2, 0xFFC6C6C6);
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
            // Selected this item as filter!
            ItemStack filterItem = item.copy();
            filterItem.setCount(1);
            PacketDistributor.sendToServer(new ExtractorSetFilterPKT(extractorPos, filterIndex, filterItem));

            // WROC DO EKSTRAKTORA, a nie do gry.
            //
            // Bylo tu this.onClose(), ktore zamyka ekran calkowicie - gracz
            // wybieral item i ladowal w swiecie zamiast wrocic do klocka.
            com.craftingveloce.client.ClientTerminalHelper.reopenExtractorScreen(extractorPos);
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
