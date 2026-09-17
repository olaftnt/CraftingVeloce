package com.craftingveloce.commands;

import com.craftingveloce.crafting.ProcessingEntry;
import com.craftingveloce.crafting.VeloceRecipeFinder;
import com.craftingveloce.crafting.VeloceRecipeRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /cv getitems <item>} - puts into the chest you are looking at ALL the
 * ingredients needed to craft the given item.
 *
 * <p><b>Why.</b> To check a recipe in game you first have to know it (JEI, wiki)
 * and click the ingredients together by hand. When testing automation that is
 * dozens of repetitions: you look at a chest, say "make me a chest out of this"
 * and you have the full set of materials in one place.
 *
 * <p><b>Where the ingredients come from.</b> From the same recipe index that the
 * auto-crafter uses ({@link VeloceRecipeRegistry}) - that is, exactly the recipe
 * the machine will really execute. When an item is made ONLY in a furnace, we take
 * the furnace recipe (and we say so plainly, because what lands in the chest is
 * the raw material, not the finished item).
 *
 * <p><b>What the command does NOT do.</b> It does not compute the whole recipe
 * tree (planks -> logs) and it does not drop anything on the ground. It gives one
 * level of ingredients, and whatever did not fit it reports in the chat - a silent
 * loss of items would be worse than not having the command at all.
 */
public final class CVGetItemsCommand {

    private CVGetItemsCommand() {
    }

    /**
     * Look reach. Larger than the player's reach (4.5-5.5 blocks), because this is
     * a diagnostic command - the player stands next to the chest and does not have
     * to "aim" at it to within half a block.
     */
    private static final double LOOK_REACH = 16.0D;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext) {
        dispatcher.register(
            CVCommandRoot.root()
                .then(Commands.literal("getitems")
                    // ItemArgument gives item id suggestions (like /give), so you do
                    // not have to know them by heart.
                    .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .executes(context -> run(context, 1))
                        // An item may have several recipes (e.g. planks from every
                        // kind of log) - the number picks which one to take.
                        .then(Commands.argument("recipe",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(context -> run(context,
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "recipe"))))))
        );
    }

    private static int run(CommandContext<CommandSourceStack> context, int recipeNumber) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] This command only works for a player - you have to be looking at something."));
            return 0;
        }
        Item item = ItemArgument.getItem(context, "item").getItem();
        ServerLevel level = player.serverLevel();

        BlockPos target = lookedAtBlock(player);
        if (target == null) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] You are not looking at any block - aim at a chest."));
            return 0;
        }
        IItemHandler handler = itemHandlerAt(level, target);
        if (handler == null) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] The block you are looking at is not storage "
                            + "(no ItemHandler)."));
            return 0;
        }

        List<ProcessingEntry> recipes = recipesFor(level, item);
        if (recipes.isEmpty()) {
            source.sendFailure(Component.literal("§c[CraftingVeloce] There is no recipe for "
                    + new ItemStack(item).getHoverName().getString()
                    + " (§7" + itemId(item) + "§c)."));
            return 0;
        }
        if (recipeNumber > recipes.size()) {
            source.sendFailure(Component.literal("§c[CraftingVeloce] This item has only §f"
                    + recipes.size() + "§c recipe(s) - choose a number from 1 to "
                    + recipes.size() + "."));
            return 0;
        }
        ProcessingEntry recipe = recipes.get(recipeNumber - 1);
        Kit kit = ingredientsOf(recipe);
        Map<Item, Integer> needed = kit.items();
        if (needed.isEmpty()) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] This recipe has no ingredients."));
            return 0;
        }
        if (kit.emptyIngredients() > 0) {
            source.sendSuccess(() -> Component.literal("§cNote: §e"
                    + kit.emptyIngredients() + " ingredient(s) have no item at all "
                    + "(empty tag) - the set will be incomplete."), false);
        }

        report(source, player, item, recipe, recipes.size());
        Map<Item, Integer> leftovers = insertAll(handler, needed);
        reportResult(source, target, needed, leftovers);

        // The change of chest contents has to be visible to the game and the network.
        level.updateNeighborsAt(target, level.getBlockState(target).getBlock());
        return 1;
    }

    /**
     * The recipes we compute the ingredients from.
     *
     * <p>We take them from ALL sources ({@link VeloceRecipeFinder}): crafting
     * table, module families (Create/Alchemistry/Mekanism) and furnace.
     *
     * <p><b>The BUG this fixes (player report).</b> Previously the command only
     * asked the vanilla index, so for an item made in a module machine (e.g. a
     * crushing wheel from Create, made only by mechanical crafting) it said "there
     * is no recipe" - even though the recipe exists. A lack of information
     * pretended to be information, and that is the worst kind of bug.
     *
     * <p>This DELIBERATELY does not look at machines and power: the player asks
     * "how is this made", not "can I make this in this network".
     */
    private static List<ProcessingEntry> recipesFor(ServerLevel level, Item item) {
        return VeloceRecipeFinder.all(level, item);
    }

    /**
     * Recipe ingredients: "item -> how many pieces" plus the number of ingredients
     * that have NO option at all (empty tag).
     *
     * <p>From each ingredient we take the FIRST available option (a recipe may
     * accept several items, e.g. every kind of planks), and we multiply the piece
     * count by the number of pieces required of that ingredient - exactly how the
     * auto-crafter planner counts it.
     *
     * <p>An empty tag is not an "empty slot": a grid slot with no ingredient we
     * skip silently (that is how the recipe works), but an ingredient that has NO
     * item at all must be reported - otherwise the player would get an incomplete
     * set and would not know why.
     */
    private record Kit(Map<Item, Integer> items, int emptyIngredients) {
    }

    private static Kit ingredientsOf(ProcessingEntry recipe) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        int empty = 0;
        List<Ingredient> ingredients = recipe.ingredients();
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack[] options = ingredients.get(i).getItems();
            if (options.length == 0) {
                // A grid slot with no ingredient has an "empty" option list and that
                // is normal; we tell it apart from an ingredient with no options by
                // the fact that the recipe lists it as a non-empty ingredient.
                if (isRealIngredient(recipe, i)) {
                    empty++;
                }
                continue;
            }
            ItemStack chosen = options[0];
            int count = recipe.ingredientCount(i) * Math.max(1, chosen.getCount());
            out.merge(chosen.getItem(), count, Integer::sum);
        }
        return new Kit(out, empty);
    }

    /**
     * Whether the ingredient at this position is "real" (not an empty grid slot).
     *
     * <p>Vanilla represents an empty grid slot as {@code Ingredient.EMPTY} - it has
     * no items and returns just as many for an empty tag. We tell them apart by
     * identity with {@code Ingredient.EMPTY}, because only the second case is a
     * data error.
     */
    private static boolean isRealIngredient(ProcessingEntry recipe, int index) {
        return recipe.ingredients().get(index) != Ingredient.EMPTY;
    }

    /**
     * Inserts everything into the storage. Returns the number of positions that did
     * NOT fit.
     *
     * <p>Nothing is dropped: the player gets a list of the shortfalls in the chat,
     * not items on the ground (the command is meant to prepare a chest, not to
     * litter the world).
     */
    private static Map<Item, Integer> insertAll(IItemHandler handler,
                                                Map<Item, Integer> needed) {
        Map<Item, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            // We insert in SINGLE stacks (max stack size), not one big number:
            // Create mechanical recipes have a few dozen pieces of one ingredient,
            // and an ItemStack larger than the stack size is often rejected or
            // truncated by storage.
            int maxStack = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            int remainingCount = entry.getValue();
            while (remainingCount > 0) {
                int chunk = Math.min(remainingCount, maxStack);
                ItemStack remaining = new ItemStack(entry.getKey(), chunk);
                for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
                    remaining = handler.insertItem(slot, remaining, false);
                }
                int inserted = chunk - remaining.getCount();
                if (inserted <= 0) {
                    break;   // storage full - we do not spin in circles
                }
                remainingCount -= inserted;
            }
            if (remainingCount > 0) {
                left.put(entry.getKey(), remainingCount);
            }
        }
        return left;
    }

    private static void report(CommandSourceStack source, ServerPlayer player, Item item,
                               ProcessingEntry recipe, int recipeCount) {
        String name = new ItemStack(item).getHoverName().getString();
        String kind;
        if (recipe.isFurnace()) {
            kind = "furnace";
        } else if (VeloceRecipeFinder.isModuleRecipe(recipe)) {
            // A family from another mod - we say PLAINLY that a module machine is
            // needed, not a crafting table.
            kind = "machine: §f" + VeloceRecipeFinder.typeName(recipe.type()) + "§7";
        } else {
            kind = "crafting";
        }
        source.sendSuccess(() -> Component.literal(
                "§6[CraftingVeloce] Ingredients for §f" + name + " §7(" + itemId(item) + ")"), false);
        int perCraft = Math.max(1, recipe.primaryResult().getCount());
        source.sendSuccess(() -> Component.literal(
                "§7Recipe " + kind + ": §f" + recipe.id()
                        + (recipeCount > 1 ? " §7(1 of " + recipeCount + ")" : "")
                        + " §7- one execution yields §f" + perCraft + "x "
                        + nameOf(item)), false);
        if (recipe.isFurnace()) {
            source.sendSuccess(() -> Component.literal(
                    "§eThis is a FURNACE recipe - the raw material goes into the chest, not the finished item."), false);
        }
    }

    /**
     * Summary: what exactly went in (with numbers and names) and what did not fit.
     *
     * <p>Names matter for ingredients with a tag (e.g. "any planks"): the player has
     * to see which option the command chose, because only that one ended up in the
     * chest. That is why we say it PLAINLY instead of "I inserted 3 kinds of
     * ingredients".
     */
    private static void reportResult(CommandSourceStack source, BlockPos pos,
                                     Map<Item, Integer> needed,
                                     Map<Item, Integer> leftovers) {
        List<String> inserted = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int left = leftovers.getOrDefault(entry.getKey(), 0);
            int in = entry.getValue() - left;
            if (in > 0) {
                inserted.add("§f" + in + "x " + nameOf(entry.getKey()));
            }
            if (left > 0) {
                missed.add("§c" + left + "x " + nameOf(entry.getKey()));
            }
        }
        source.sendSuccess(() -> Component.literal("§aInserted into §f["
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]§a: "
                + String.join("§7, §r", inserted)), false);
        if (!missed.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§cDid not fit: §r"
                    + String.join("§7, §r", missed)), false);
        }
    }

    private static String nameOf(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    /** The block the player is looking at (or {@code null} when looking at air). */
    private static BlockPos lookedAtBlock(ServerPlayer player) {
        HitResult hit = player.pick(LOOK_REACH, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((BlockHitResult) hit).getBlockPos();
    }

    /** Storage (ItemHandler) in the given block - from every side it exposes. */
    private static IItemHandler itemHandlerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state,
                level.getBlockEntity(pos), null);
    }

    private static String itemId(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
