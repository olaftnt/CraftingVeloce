package com.craftingveloce.client.gui;

import com.craftingveloce.network.CraftingTableToggleItemPKT;
import com.craftingveloce.network.CraftingTableCycleRecipePKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GUI auto-craftera (Veloce Crafting Table).
 *
 * <p>Rozszerza creative inventory, ale z waznymi roznicami:
 * <ul>
 *   <li><b>Pokazuje tylko itemy craftowalne</b> - bez energii, paliwa, XP.
 *       Item bez receptury nie pojawia sie wcale (w poprzedniej wersji byl
 *       fioletowy overlay; teraz jest po prostu ukryty).</li>
 *   <li><b>Kolor tla</b> pokazuje stan auto-craftingu:
 *       czerwony = ma recepture, wylaczony; zielony = wlaczony.</li>
 *   <li><b>Shift+scroll</b> na itemie z wieloma recepturami przelacza aktywna
 *       recepture (ta, ktora crafter uzyje jako pierwsza).</li>
 *   <li><b>Tooltip</b> pokazuje kazda recepture w osobnej linii, z liczba sztuk
 *       mozliwych do zrobienia z tego, co jest w sieci.</li>
 * </ul>
 */
public class VeloceCraftingTableScreen extends CreativeModeInventoryScreen {

    private final BlockPos tablePos;
    private Set<Item> enabledItems;
    private Map<Item, ResourceLocation> preferredRecipes;

    @Nullable
    private GameType modeBeforeOpen;

    private static Field slotWrapperTargetField;

    /** Cache: item -> lista receptur (id + wynik + skladniki). */
    private Map<Item, List<ClientRecipe>> craftableItems = null;

    /** Indeks itemu pod kursorem przy ostatnim shift+scrollu (do cyklowania). */
    private Item lastScrolledItem = null;

    /** Receptura widziana po stronie klienta (do tooltipow i cyklowania). */
    private record ClientRecipe(ResourceLocation id, ItemStack result, List<List<ItemStack>> options) {
        int resultCount() {
            return Math.max(1, result.getCount());
        }
    }

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

    public VeloceCraftingTableScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                     boolean displayOperatorCreativeTab, BlockPos tablePos,
                                     Set<Item> enabledItems,
                                     Map<Item, ResourceLocation> preferredRecipes) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.tablePos = tablePos;
        this.enabledItems = new HashSet<>(enabledItems);
        this.preferredRecipes = new HashMap<>(preferredRecipes);
    }

    public void updateEnabledItems(Set<Item> items, Map<Item, ResourceLocation> prefs) {
        this.enabledItems = new HashSet<>(items);
        this.preferredRecipes = new HashMap<>(prefs);
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

    /**
     * Buduje indeks itemow craftowalnych z receptur dostepnych po stronie klienta.
     * Uzywamy tylko typow "bez infrastruktury" - tak samo jak serwerowy rejestr.
     */
    private Map<Item, List<ClientRecipe>> getCraftableItems() {
        if (craftableItems != null) {
            return craftableItems;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Collections.emptyMap();
        }
        Map<Item, List<ClientRecipe>> out = new LinkedHashMap<>();
        var registries = mc.level.registryAccess();

        collectType(mc, RecipeType.CRAFTING, registries, out);
        collectType(mc, RecipeType.STONECUTTING, registries, out);
        collectType(mc, RecipeType.SMITHING, registries, out);

        craftableItems = out;
        return craftableItems;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectType(Minecraft mc, RecipeType<?> type,
                             net.minecraft.core.HolderLookup.Provider registries,
                             Map<Item, List<ClientRecipe>> out) {
        List<net.minecraft.world.item.crafting.RecipeHolder<?>> holders = new ArrayList<>();
        try {
            holders.addAll(mc.level.getRecipeManager().getAllRecipesFor((RecipeType) type));
        } catch (Throwable t) {
            return;
        }
        for (var holder : holders) {
            var recipe = holder.value();
            if (recipe.isSpecial()) {
                continue;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(registries);
            } catch (Throwable t) {
                continue;
            }
            if (result.isEmpty()) {
                continue;
            }
            NonNullList<Ingredient> ings = recipe.getIngredients();
            List<List<ItemStack>> options = new ArrayList<>();
            boolean any = false;
            for (Ingredient ing : ings) {
                ItemStack[] items = ing.getItems();
                if (items.length == 0) {
                    continue;
                }
                any = true;
                List<ItemStack> list = new ArrayList<>(items.length);
                for (ItemStack s : items) {
                    list.add(s.copy());
                }
                options.add(list);
            }
            if (!any) {
                continue;
            }
            out.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                    .add(new ClientRecipe(holder.id(), result.copy(), options));
        }
    }

    private boolean isPlayerInventorySlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) return false;
        if (slot.container == this.minecraft.player.getInventory()) return true;
        if (slotWrapperTargetField != null) {
            try {
                Object target = slotWrapperTargetField.get(slot);
                if (target instanceof Slot ts && ts.container == this.minecraft.player.getInventory()) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void suppressHotbarSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) return;
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (isPlayerInventorySlot(s)) {
                final Slot orig = s;
                this.menu.slots.set(i, new Slot(orig.container, orig.getContainerSlot(), -10000, -10000) {
                    @Override
                    public boolean isActive() { return false; }
                    @Override
                    public boolean isHighlightable() { return false; }
                });
            }
        }
    }

    @Override
    public void containerTick() {
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        if (slot.x == 173 && slot.y == 112) {
            return;
        }

        if (!slot.hasItem()) {
            super.renderSlot(graphics, slot);
            return;
        }

        ItemStack stack = slot.getItem();
        Item item = stack.getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(item);

        // Nie pokazujemy itemow bez receptury craftowalnej - w tym GUI nie maja sensu.
        if (recipes == null || recipes.isEmpty()) {
            return;
        }

        int overlayColor = enabledItems.contains(item)
                ? 0x7700AA00   // zielony: auto-crafting WLACZONY
                : 0x77AA0000;  // czerwony: ma recepture, ale wylaczony

        super.renderSlot(graphics, slot);

        RenderSystem.disableDepthTest();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlayColor);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();

        // Znacznik wielu receptur: maly symbol "⌥N" w prawym gornym rogu.
        // Wczesniej byla tam sama cyfra, co bylo niejasne - teraz wiadomo,
        // ze to liczba receptur, a nie sztuk.
        if (recipes.size() > 1) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 250);
            String badge = "§e" + recipes.size() + "r";
            graphics.drawString(this.font, badge, slot.x + 16 - this.font.width(badge),
                    slot.y + 1, 0xFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int x1 = this.leftPos + 8;
        int y1 = this.topPos + 111;
        int x2 = this.leftPos + 170;
        int y2 = this.topPos + 130;
        graphics.fill(x1, y1, x2, y2, 0xFFC6C6C6);

        renderRecipeTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Nadpisujemy tooltip vanilla, zeby pozbyc sie trzech rzeczy, ktore
     * pojawiaja sie w creative inventory w zakladkach CATEGORY i SEARCH:
     *
     * <ol>
     *   <li>nazwa kategorii creative (np. "Building Blocks") - niebieska linia</li>
     *   <li>tagi itemu, czyli surowe id blokow (np. "minecraft:planks") -
     *       pojawiaja sie tylko gdy wlaczone sa zaawansowane tooltipy</li>
     *   <li>lista zakladek zawierajacych item - pojawia sie w zakladce lupy (SEARCH)</li>
     * </ol>
     *
     * W tym GUI pokazujemy wlasny, czysty tooltip (patrz renderRecipeTooltip),
     * wiec zwracamy tylko standardowe linie itemu.
     */
    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return super.getTooltipFromContainerItem(stack);
        }
        return stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(this.minecraft.level),
                this.minecraft.player,
                this.minecraft.options.advancedItemTooltips
                        ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                        : net.minecraft.world.item.TooltipFlag.Default.NORMAL
        );
    }

    /** Tooltip: kazda receptura w osobnej linii + ile sztuk da sie zrobic. */
    private void renderRecipeTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Slot slot = getSlotUnderMouse();
        if (slot == null || isPlayerInventorySlot(slot) || !slot.hasItem()) {
            return;
        }
        Item item = slot.getItem().getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(item);
        if (recipes == null || recipes.isEmpty()) {
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(slot.getItem().getHoverName());

        ResourceLocation pref = preferredRecipes.get(item);
        for (ClientRecipe r : recipes) {
            boolean active = pref != null ? pref.equals(r.id()) : recipes.indexOf(r) == 0;
            String marker = active ? "▶ " : "  ";
            String color = active ? "§a" : "§7";
            String name = r.result().getHoverName().getString();
            lines.add(Component.literal(color + marker + r.resultCount() + "x " + name));

            // Skladniki receptury - nazwy itemow, nie surowe id.
            String ing = describeIngredients(r);
            if (!ing.isEmpty()) {
                lines.add(Component.literal("§8     z: §7" + ing));
            }
        }

        lines.add(Component.empty());
        if (recipes.size() > 1) {
            lines.add(Component.literal("§e" + recipes.size() + " receptury"));
            lines.add(Component.literal("§7Shift + prawy klik §8zmienia recepturę"));
        }
        lines.add(Component.literal("§7Klik: " + (enabledItems.contains(item)
                ? "§cWYLACZ §7auto-crafting"
                : "§aWŁACZ §7auto-crafting")));

        graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * Opis skladnikow receptury - nazwy itemow (np. "1x Oak Log, 2x Stick").
     * Dla skladnikow z wieloma alternatywami pokazuje pierwsza opcje i liczbe
     * pozostalych w nawiasie.
     */
    private static String describeIngredients(ClientRecipe r) {
        StringBuilder sb = new StringBuilder();
        for (List<ItemStack> options : r.options()) {
            if (options.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("§8, §7");
            }
            sb.append("1x ").append(options.get(0).getHoverName().getString());
            if (options.size() > 1) {
                sb.append("§8(+").append(options.size() - 1).append(")§7");
            }
        }
        return sb.toString();
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) return;
        if (slot == null || isPlayerInventorySlot(slot)) return;
        if (slot.x == 173 && slot.y == 112) return;

        ItemStack item = slot.getItem();
        if (item.isEmpty()) return;

        Item clickedItem = item.getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(clickedItem);
        if (recipes == null || recipes.isEmpty()) return;

        // Shift + prawy klik = zmiana aktywnej receptury (dla itemow z wieloma).
        // Uzywamy tego zamiast shift+scroll, bo creative inventory przechwytuje
        // scroll gdy lista sie przewija (canScroll), wiec scroll jest zawodny.
        if (mouseButton == 1 && net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            cycleRecipe(clickedItem, recipes);
            return;
        }

        if (enabledItems.contains(clickedItem)) {
            enabledItems.remove(clickedItem);
        } else {
            enabledItems.add(clickedItem);
        }
        PacketDistributor.sendToServer(new CraftingTableToggleItemPKT(tablePos, clickedItem));
    }

    /**
     * Przelacza aktywna recepture na nastepna i wysyla wybor do serwera.
     * Cyklowanie robimy lokalnie (natychmiastowy feedback), serwer zapisuje
     * preferencje w block entity.
     */
    private void cycleRecipe(Item item, List<ClientRecipe> recipes) {
        if (recipes == null || recipes.size() <= 1) {
            return;
        }
        List<ResourceLocation> ids = new ArrayList<>(recipes.size());
        for (ClientRecipe r : recipes) {
            ids.add(r.id());
        }
        ResourceLocation current = preferredRecipes.get(item);
        int idx = current == null ? -1 : ids.indexOf(current);
        int next = (int) (((idx + 1) % ids.size() + ids.size()) % ids.size());
        ResourceLocation chosen = ids.get(next);
        preferredRecipes.put(item, chosen);
        PacketDistributor.sendToServer(
                new CraftingTableCycleRecipePKT(tablePos, item, chosen));
    }

    /**
     * Shift+scroll - dodatkowy sposob zmiany receptury.
     *
     * <p>Uwaga: w creative inventory scroll jest przechwytywany przez vanilla,
     * gdy lista sie przewija, wiec ten gest bywa zawodny. Podstawowa metoda
     * jest {@code shift + prawy klik} (patrz slotClicked).
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && net.minecraft.client.gui.screens.Screen.hasShiftDown()
                && this.minecraft != null && this.minecraft.player != null) {
            Slot slot = getSlotUnderMouse();
            if (slot != null && !isPlayerInventorySlot(slot) && slot.hasItem()) {
                Item item = slot.getItem().getItem();
                List<ClientRecipe> recipes = getCraftableItems().get(item);
                if (recipes != null && recipes.size() > 1) {
                    cycleRecipe(item, recipes);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
