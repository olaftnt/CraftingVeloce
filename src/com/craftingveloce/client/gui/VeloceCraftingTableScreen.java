package com.craftingveloce.client.gui;

import com.craftingveloce.network.CraftingTableToggleItemPKT;
import com.craftingveloce.network.CraftingTableCycleRecipePKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.network.PacketDistributor;

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
 * <p>Zasady:
 * <ul>
 *   <li><b>Pokazuje tylko itemy craftowalne.</b> Itemy bez receptury sa
 *       <b>usuwane z listy</b>, wiec nie zostawiaja pustych dziur - reszta
 *       zsuwa sie na ich miejsce.</li>
 *   <li><b>Lewy klik</b> wlacza/wylacza auto-crafting (czerwony = off,
 *       zielony = on).</li>
 *   <li><b>Prawy klik</b> przelacza recepture, jesli item ma ich wiecej;
 *       jesli ma tylko jedna, nic sie nie dzieje.</li>
 *   <li><b>Tooltip</b> pokazuje skladniki kazdej receptury po nazwie.</li>
 * </ul>
 */
public class VeloceCraftingTableScreen extends VeloceCreativeScreen {

    private final BlockPos tablePos;
    private Set<Item> enabledItems;
    private Map<Item, ResourceLocation> preferredRecipes;

    /** Cache: item -> lista receptur (id + wynik + skladniki). */
    private Map<Item, List<ClientRecipe>> craftableItems = null;

    /** Receptura widziana po stronie klienta (do tooltipow i cyklowania). */
    private record ClientRecipe(ResourceLocation id, ItemStack result, List<List<ItemStack>> options) {
        int resultCount() {
            return Math.max(1, result.getCount());
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

    // ------------------------------------------------------------------
    // Filtrowanie zawartosci
    // ------------------------------------------------------------------

    /**
     * Przepuszczamy tylko itemy z receptura wykonywalna bez energii/paliwa.
     * Reszta jest usuwana z listy, wiec nie zostawia dziur.
     */
    @Override
    protected boolean acceptItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        List<ClientRecipe> recipes = getCraftableItems().get(stack.getItem());
        return recipes != null && !recipes.isEmpty();
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

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot) || isTrashSlot(slot)) {
            return;
        }
        super.renderSlot(graphics, slot);

        if (!slot.hasItem()) {
            return;
        }

        Item item = slot.getItem().getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(item);
        boolean craftable = recipes != null && !recipes.isEmpty();

        int overlayColor;
        if (!craftable) {
            // Czarne tlo = item, ktorego crafter nie zrobi.
            // (acceptItem i tak je usuwa, ale gdyby sie pojawilo - jest czytelne.)
            overlayColor = 0xAA000000;
        } else if (enabledItems.contains(item)) {
            overlayColor = 0x7700AA00;   // zielony: auto-crafting ON
        } else {
            overlayColor = 0x77AA0000;   // czerwony: receptura jest, ale OFF
        }

        RenderSystem.disableDepthTest();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlayColor);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();

    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawHotbarCover(graphics, 0xFFC6C6C6);
        renderRecipeTooltip(graphics, mouseX, mouseY);
        renderTabTooltip(graphics, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    /**
     * Nadpisujemy tooltip vanilla, zeby pozbyc sie linii, ktore creative
     * inventory dodaje w zakladkach CATEGORY i SEARCH: nazwy kategorii,
     * surowych tagow itemu i listy zakladek zawierajacych item.
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

    /** Tooltip: kazda receptura + jej skladniki po nazwie. */
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
            lines.add(Component.literal(color + marker + r.resultCount() + "x "
                    + r.result().getHoverName().getString()));

            String ing = describeIngredients(r);
            if (!ing.isEmpty()) {
                lines.add(Component.literal("§8     " + ing));
            }
        }

        lines.add(Component.empty());
        if (recipes.size() > 1) {
            lines.add(Component.literal("§7Prawy klik §8zmienia recepturę"));
        }
        lines.add(Component.literal("§7Lewy klik: " + (enabledItems.contains(item)
                ? "§cWYŁĄCZ §7auto-crafting"
                : "§aWŁĄCZ §7auto-crafting")));
        lines.add(Component.literal("§8Prawy klik na ikonce kategorii: cała kategoria"));

        graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * Skladniki receptury jako "2x Oak Planks, 1x Stick".
     * Bez surowych id i bez informacji o alternatywach.
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
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Przelaczanie calej kategorii (prawy klik na ikonke zakladki)
    // ------------------------------------------------------------------

    /** W crafterze mozna przelaczac tylko itemy z receptura. */
    @Override
    protected boolean isToggleable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        List<ClientRecipe> recipes = getCraftableItems().get(stack.getItem());
        return recipes != null && !recipes.isEmpty();
    }

    @Override
    protected boolean isToggledOn(Item item) {
        return enabledItems.contains(item);
    }

    @Override
    protected void applyToggle(Item item) {
        if (enabledItems.contains(item)) {
            enabledItems.remove(item);
        } else {
            enabledItems.add(item);
        }
        PacketDistributor.sendToServer(new CraftingTableToggleItemPKT(tablePos, item));
    }

    // ------------------------------------------------------------------
    // Interakcja
    // ------------------------------------------------------------------

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) return;
        if (slot == null || isPlayerInventorySlot(slot) || isTrashSlot(slot)) return;

        ItemStack item = slot.getItem();
        if (item.isEmpty()) return;

        Item clickedItem = item.getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(clickedItem);
        if (recipes == null || recipes.isEmpty()) return;

        // Prawy klik = zmiana receptury (gdy jest wiecej niz jedna).
        if (mouseButton == 1) {
            cycleRecipe(clickedItem, recipes);
            return;
        }

        // Lewy klik = wlacz/wylacz auto-crafting.
        if (enabledItems.contains(clickedItem)) {
            enabledItems.remove(clickedItem);
        } else {
            enabledItems.add(clickedItem);
        }
        PacketDistributor.sendToServer(new CraftingTableToggleItemPKT(tablePos, clickedItem));
    }

    /**
     * Przelacza aktywna recepture na nastepna. Gdy item ma tylko jedna
     * recepture, nie robi nic (nie ma na co przelaczac).
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
     * W creative inventory scroll bywa przechwytywany przez liste, wiec
     * podstawowa metoda jest prawy klik.
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
}
