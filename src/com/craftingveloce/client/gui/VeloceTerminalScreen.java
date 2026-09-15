package com.craftingveloce.client.gui;

import com.craftingveloce.network.TerminalPullItemPKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VeloceTerminalScreen extends VeloceCreativeScreen {

    private final BlockPos terminalPos;
    private Map<Item, Long> networkCounts = new HashMap<>();

    /** Ile sztuk da sie dorobic auto-craftingiem (zolta liczba "+N"). */
    private Map<Item, Long> craftableCounts = new HashMap<>();

    @Nullable

    private static Method selectTabMethod;
    private static Field selectedTabField;

    static {
        try {
            for (Method m : CreativeModeInventoryScreen.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == net.minecraft.world.item.CreativeModeTab.class) {
                    m.setAccessible(true);
                    selectTabMethod = m;
                    break;
                }
            }
            for (Field f : CreativeModeInventoryScreen.class.getDeclaredFields()) {
                if (f.getType() == net.minecraft.world.item.CreativeModeTab.class) {
                    f.setAccessible(true);
                    selectedTabField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public VeloceTerminalScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos terminalPos) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.terminalPos = terminalPos;
    }

    public void updateNetworkCounts(Map<Item, Long> counts) {
        updateNetworkCounts(counts, Map.of());
    }

    public void updateNetworkCounts(Map<Item, Long> counts, Map<Item, Long> craftable) {
        this.networkCounts = new HashMap<>(counts);
        this.craftableCounts = new HashMap<>(craftable);
    }

    /** Odpowiedz serwera z liczbami "ile da sie dorobic" dla widocznych itemow. */
    public void updateCraftableCounts(Map<Item, Long> craftable) {
        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "received craftable counts for %d item(s)", craftable.size());
        this.craftableCounts = new HashMap<>(craftable);
    }

    /**
     * Liczby craftowalnosci przychodza w pakiecie sync razem ze stockiem.
     *
     * <p>Serwer utrzymuje je w cache w tle (patrz VeloceCraftingCache) i wysyla
     * gotowe. Klient NIE pyta o nic i nie liczy nic sam, wiec otwarcie GUI jest
     * natychmiastowe i nie zalezy od liczby receptur w paczce modow.
     */
    @Override
    public void containerTick() {
        // Nic nie robimy - liczby przychodza z serwera.
    }


    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        super.renderSlot(graphics, slot);

        if (isShopSlot(slot) && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            long count = networkCounts.getOrDefault(stack.getItem(), 0L);
            if (count > 0) {
                drawCountOverlay(graphics, this.font, count, slot.x, slot.y);
            }
            // Liczba sztuk, ktore da sie dorobic auto-craftingiem.
            // Pokazywana jako "+N" w lewym gornym rogu - zolta, zeby odroznic
            // od zielonego stocku. Zero nie jest rysowane.
            long craftable = craftableCounts.getOrDefault(stack.getItem(), 0L);
            if (craftable > 0) {
                drawCraftableOverlay(graphics, this.font, craftable, slot.x, slot.y);
            }
        }
    }

    private static final DecimalFormat FORMAT_1_DEC;
    static {
        DecimalFormatSymbols sym = new DecimalFormatSymbols();
        sym.setDecimalSeparator('.');
        FORMAT_1_DEC = new DecimalFormat(".#;0.#", sym);
    }

    public static String formatCount(long number) {
        if (number < 1000) return Long.toString(number);
        if (number < 1000000) return FORMAT_1_DEC.format(number / 1000.0) + "K";
        if (number < 1000000000) return FORMAT_1_DEC.format(number / 1000000.0) + "M";
        return FORMAT_1_DEC.format(number / 1000000000.0) + "B";
    }

    private void drawCountOverlay(GuiGraphics graphics, Font font, long count, int x, int y) {
        float scaleFactor = 0.6f;
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        String text = formatCount(count);
        graphics.pose().pushPose();
        graphics.pose().scale(scaleFactor, scaleFactor, scaleFactor);
        graphics.pose().translate(0, 0, 450);
        float inverseScale = 1.0f / scaleFactor;
        int textX = (int) (((float) x + 16.0f - font.width(text) * scaleFactor) * inverseScale);
        int textY = (int) (((float) y + 16.0f - 7.0f * scaleFactor) * inverseScale);
        graphics.drawString(font, text, textX, textY, 0x55FF55, true); // bright green
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    /**
     * Rysuje liczbe mozliwych do wycraftowania sztuk (np. "+12") w lewym gornym
     * rogu slotu. Pokazywane tylko gdy auto-crafting danego itemu jest wlaczony
     * i faktycznie da sie cos dorobic.
     */
    private void drawCraftableOverlay(GuiGraphics graphics, Font font, long craftable, int x, int y) {
        if (craftable <= 0) {
            return;
        }
        float scaleFactor = 0.6f;
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        String text = "+" + formatCount(craftable);
        graphics.pose().pushPose();
        graphics.pose().scale(scaleFactor, scaleFactor, scaleFactor);
        graphics.pose().translate(0, 0, 450);
        float inverseScale = 1.0f / scaleFactor;
        int textX = (int) (((float) x + 1.0f) * inverseScale);
        int textY = (int) (((float) y + 1.0f) * inverseScale);
        // Zolty = dorobione auto-craftingiem (odroznienie od zielonego stocku).
        graphics.drawString(font, text, textX, textY, 0xFFD700, true);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        return super.getTooltipFromContainerItem(stack);
    }

    private boolean isPlayerSlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) {
            return false;
        }
        if (this.isInventoryOpen() && this.indexOfSlotIn(this.minecraft.player.inventoryMenu, slot) >= 0) {
            return true;
        }
        return slot.container == this.minecraft.player.getInventory();
    }

    private boolean isShopSlot(Slot slot) {
        if (slot == null || this.isPlayerSlot(slot)) {
            return false;
        }
        // Trash / sell slot
        return slot.x != 173 || slot.y != 112;
    }

    private void clickViaInventoryMenu(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.gameMode == null) {
            return;
        }
        LocalPlayer player = this.minecraft.player;
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;

        int inventorySlotId;
        if (slot == null) {
            inventorySlotId = slotId;
        } else {
            int resolved = this.resolveInventoryMenuIndex(slot);
            if (resolved < 0) {
                return;
            }
            inventorySlotId = resolved;
        }

        if (inventorySlotId != -999 && (inventorySlotId < 0 || inventorySlotId >= inventoryMenu.slots.size())) {
            return;
        }

        AbstractContainerMenu previous = player.containerMenu;
        try {
            player.containerMenu = inventoryMenu;
            this.minecraft.gameMode.handleInventoryMouseClick(
                    inventoryMenu.containerId, inventorySlotId, mouseButton, clickType, player);
        } finally {
            player.containerMenu = previous;
        }
    }

    private int resolveInventoryMenuIndex(Slot slot) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return -1;
        }
        LocalPlayer player = this.minecraft.player;
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;

        if (this.isInventoryOpen()) {
            int byIdentity = this.indexOfSlotIn(inventoryMenu, slot);
            if (byIdentity >= 0) {
                return byIdentity;
            }
            int containerSlot = slot.getContainerSlot();
            return (containerSlot >= 0 && containerSlot < inventoryMenu.slots.size()) ? containerSlot : -1;
        }

        if (slot.container == player.getInventory()) {
            int containerSlot = slot.getContainerSlot();
            if (containerSlot < 0 || containerSlot > 8) {
                return -1;
            }
            return 36 + containerSlot;
        }

        return -1;
    }

    private int indexOfSlotIn(AbstractContainerMenu menu, Slot slot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) == slot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }

        // 1) Player inventory slots
        if (this.isPlayerSlot(slot)) {
            this.clickViaInventoryMenu(slot, slotId, mouseButton, clickType);
            return;
        }

        // Clicks outside the window
        if (slot == null) {
            this.clickViaInventoryMenu(null, slotId, mouseButton, clickType);
            return;
        }

        // 2) Shop grid slots
        if (!this.isShopSlot(slot)) {
            return;
        }

        AbstractContainerMenu menu = this.menu;
        if (menu != null && !menu.getCarried().isEmpty()) {
            return;
        }

        ItemStack item = slot.getItem();
        if (item.isEmpty()) {
            return;
        }

        int count = 1;
        if (clickType == ClickType.QUICK_MOVE) {
            count = item.getMaxStackSize();
        }

        // Send pull packet to server! Never touch cursor on client to prevent ghost items
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "player clicked %s in terminal (count=%d, click=%s) - sending pull packet",
                item.getItem(), count, clickType);
        PacketDistributor.sendToServer(new TerminalPullItemPKT(terminalPos, item, count));
    }

    @Override
    public void removed() {
        if (this.minecraft != null && this.minecraft.player != null && this.menu != null
                && !this.menu.getCarried().isEmpty()) {
            ItemStack carried = this.menu.getCarried();
            this.menu.setCarried(ItemStack.EMPTY);
            if (!this.minecraft.player.getInventory().add(carried)) {
                this.minecraft.player.drop(carried, false);
            }
        }
        super.removed();

    }
}
