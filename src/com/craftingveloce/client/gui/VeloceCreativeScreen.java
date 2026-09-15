package com.craftingveloce.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wspolna baza dla "fake creative" GUI Veloce (terminal, filter picker,
 * crafting table, controller).
 *
 * <p>Zbiera w jednym miejscu to, co wszystkie te ekrany robia tak samo,
 * zamiast powtarzac to w kazdej klasie:
 *
 * <ul>
 *   <li>wymuszenie trybu creative na czas otwarcia i przywrocenie po zamknieciu</li>
 *   <li>wylaczenie slotow gracza (i tak sie ich nie uzywa) i zamalowanie hotbara</li>
 *   <li><b>filtrowanie listy itemow</b> - pozwala usunac itemy, ktorych nie
 *       chcemy pokazywac, zeby nie zostawialy pustych dziur w siatce</li>
 *   <li><b>ukrycie niechcianych zakladek</b> (np. Operator Utilities,
 *       Saved Hotbars) - nadpisywane w podklasach przez {@link #tabFilter}</li>
 * </ul>
 */
public abstract class VeloceCreativeScreen extends CreativeModeInventoryScreen {

    /** Pole z lista itemow biezacej zakladki (zawiera tez puste wpisy). */
    private static Field itemsField;

    /** Pole itemow w SlotWrapper (do rozpoznania slotu gracza). */
    private static Field slotWrapperTargetField;

    static {
        try {
            itemsField = Class.forName(
                            "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$ItemPickerMenu")
                    .getDeclaredField("items");
            itemsField.setAccessible(true);
        } catch (Throwable t) {
            itemsField = null;
        }
        try {
            Class<?> wrapperClass = Class.forName(
                    "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper");
            for (Field f : wrapperClass.getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    slotWrapperTargetField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            slotWrapperTargetField = null;
        }
    }

    @Nullable
    private GameType modeBeforeOpen;

    protected VeloceCreativeScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                   boolean displayOperatorCreativeTab) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
    }

    // ------------------------------------------------------------------
    // Filtrowanie zawartosci - do nadpisania w podklasach
    // ------------------------------------------------------------------

    /**
     * Czy pokazac ten item w siatce. Zwrocenie false <b>usuwa item z listy</b>,
     * wiec pozostale zsuwaja sie i nie ma pustych dziur.
     */
    protected boolean acceptItem(ItemStack stack) {
        return true;
    }

    /**
     * Zakladki ukrywane we wszystkich naszych GUI.
     * Operator Utilities i Saved Hotbars sluza do administracji creative,
     * nie do sieci Veloce - gracz ich tu nie potrzebuje.
     */
    private static final Set<String> HIDDEN_TABS = Set.of(
            "operator", "hotbar", "saved_hotbars", "op_blocks", "op_items"
    );

    /**
     * Czy pokazac te zakladke creative. Domyslnie ukrywamy zakladki
     * administracyjne (operator utilities, saved hotbars).
     */
    protected boolean acceptTab(net.minecraft.world.item.CreativeModeTab tab) {
        try {
            var key = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (key != null && HIDDEN_TABS.contains(key.getPath())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Cykl zycia
    // ------------------------------------------------------------------

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
        suppressPlayerSlots();
        applyItemFilter();
    }

    @Override
    public void containerTick() {
        // Nie odswiezamy zawartosci automatycznie - lista ma byc stabilna.
        applyItemFilter();
    }

    /**
     * Usuwa z listy itemy odrzucone przez {@link #acceptItem}.
     *
     * <p>Vanilla buduje siatke z pol listy, wiec jesli pozostawimy w niej
     * odrzucone wpisy jako puste stosy, powstaną dziury. Kompaktujemy liste,
     * a ogon dopelniamy pustymi stosami - inaczej vanilla probowalby czytac
     * nieistniejace indeksy.
     */
    protected void applyItemFilter() {
        if (itemsField == null || this.menu == null) {
            return;
        }
        try {
            Object raw = itemsField.get(this.menu);
            if (!(raw instanceof NonNullList<?> list)) {
                return;
            }
            int total = list.size();
            List<ItemStack> kept = new ArrayList<>(total);
            for (Object o : list) {
                if (o instanceof ItemStack st && !st.isEmpty() && acceptItem(st)) {
                    kept.add(st);
                }
            }
            @SuppressWarnings("unchecked")
            NonNullList<ItemStack> items = (NonNullList<ItemStack>) list;
            for (int i = 0; i < total; i++) {
                items.set(i, i < kept.size() ? kept.get(i) : ItemStack.EMPTY);
            }
        } catch (Throwable ignored) {
            // Refleksja moze sie nie udac przy zmianie wersji - wtedy po prostu
            // nie filtrujemy, GUI dalej dziala (z dziurami).
        }
    }

    /**
     * Ukrywa zakladki odrzucone przez {@link #acceptTab}.
     *
     * <p>Robimy to przez nadpisanie {@link #renderTabButton} (nie rysujemy
     * odrzuconych) oraz {@link #checkTabClicked} (ignorujemy klikniecia).
     * Nie da sie tego zrobic przez usuniecie z listy, bo
     * {@code CreativeModeTabs.tabs()} zwraca liste niemodyfikowalna.
     */
    @Override
    protected void renderTabButton(net.minecraft.client.gui.GuiGraphics graphics,
                                   net.minecraft.world.item.CreativeModeTab tab) {
        if (!acceptTab(tab)) {
            return;
        }
        super.renderTabButton(graphics, tab);
    }

    @Override
    protected boolean checkTabClicked(net.minecraft.world.item.CreativeModeTab tab,
                                      double mouseX, double mouseY) {
        if (!acceptTab(tab)) {
            return false;
        }
        return super.checkTabClicked(tab, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // Sloty gracza
    // ------------------------------------------------------------------

    protected boolean isPlayerInventorySlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) {
            return false;
        }
        if (slot.container == this.minecraft.player.getInventory()) {
            return true;
        }
        if (slotWrapperTargetField != null) {
            try {
                Object target = slotWrapperTargetField.get(slot);
                if (target instanceof Slot ts && ts.container == this.minecraft.player.getInventory()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Zastepuje sloty gracza nieaktywnymi slotami poza ekranem. */
    protected void suppressPlayerSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (isPlayerInventorySlot(s)) {
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

    /** Czy to slot kosza (prawy dolny rog creative inventory). */
    protected static boolean isTrashSlot(Slot slot) {
        return slot != null && slot.x == 173 && slot.y == 112;
    }

    /** Zamalowuje pasek hotbara (jest nieuzywany w tych GUI). */
    protected void drawHotbarCover(net.minecraft.client.gui.GuiGraphics graphics, int color) {
        graphics.fill(this.leftPos + 8, this.topPos + 111,
                this.leftPos + 170, this.topPos + 130, color);
    }

    protected static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public void removed() {
        if (this.minecraft != null && this.minecraft.player != null && this.menu != null
                && !this.menu.getCarried().isEmpty()) {
            this.menu.setCarried(ItemStack.EMPTY);
        }
        super.removed();
        if (this.modeBeforeOpen != null && this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.setLocalMode(this.modeBeforeOpen);
            this.modeBeforeOpen = null;
        }
    }
}
