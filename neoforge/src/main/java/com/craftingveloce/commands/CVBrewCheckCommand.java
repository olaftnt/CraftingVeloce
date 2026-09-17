package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Block;

/**
 * Brews a potion in a real brewing stand and checks what came out.
 *
 * <p><b>Why this exists next to the module test.</b> {@code /cv testmodule} drives
 * the TERMINAL, and the terminal's auto-crafting goes through
 * {@code VeloceAutoCrafter}, which inserts {@code recipe.primaryResult()} itself and
 * never touches the stand's own brewing code. A stand that swaps the potion for the
 * ingredient therefore passed every terminal test ever run against it, while brewing
 * <em>by hand</em> - the thing a player actually does - produced redstone instead of
 * a potion.
 *
 * <p>That is not hypothetical: vanilla declares
 * {@code hasMix(input, ingredient)} but {@code mix(ingredient, input)}, with the
 * argument order INVERTED between the two, and {@code mix} reads the bottle's
 * potion contents from its second argument. The stand called
 * {@code mix(bottle, ingredient)}, found no potion contents on the redstone, and got
 * the redstone back.
 *
 * <p>So this command exercises the stand and nothing else: it puts a real potion and
 * a real ingredient into the machine, lets the machine's own tick run, and reads the
 * bottle slot afterwards. The expected result is computed independently, from the
 * same game API the stand is supposed to be calling.
 */
public final class CVBrewCheckCommand {

    /**
     * Ticks between priming the stand and reading the bottle slot.
     *
     * <p>Long enough for the stand's own 400-tick brew to finish. Shortening the
     * timer from a scheduled task was tried first and made the check lie: the stand
     * compares the ingredient it started with against the ingredient in the slot, so
     * poking brewTime from outside raced that bookkeeping and the brew aborted with
     * the bottle untouched. The machine is simply allowed to take as long as it takes.
     */
    private static final int SETTLE_TICKS = 460;

    private static String lastVerdict = "none";

    private CVBrewCheckCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("brewcheck")
                        .then(Commands.literal("result").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(lastVerdict), false);
                            return 1;
                        }))
                        .then(Commands.argument("potion", StringArgumentType.string())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        BuiltInRegistries.POTION.keySet().stream()
                                                .map(ResourceLocation::toString).toList(), builder))
                                .then(Commands.argument("ingredient", StringArgumentType.string())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                BuiltInRegistries.ITEM.keySet().stream()
                                                        .map(ResourceLocation::toString).toList(), builder))
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "potion"),
                                                StringArgumentType.getString(ctx, "ingredient")))))));
    }

    private static int run(CommandSourceStack source, String potionId, String ingredientId) {
        ServerLevel level = source.getLevel();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/cv brewcheck needs a player"));
            return 0;
        }

        var potionOpt = BuiltInRegistries.POTION.getOptional(ResourceLocation.parse(potionId));
        if (potionOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cNo such potion: " + potionId));
            return 0;
        }
        var itemOpt = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(ingredientId));
        if (itemOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cNo such item: " + ingredientId));
            return 0;
        }
        Item ingredientItem = itemOpt.get();

        ItemStack bottle = PotionContents.createItemStack(Items.POTION,
                BuiltInRegistries.POTION.wrapAsHolder(potionOpt.get()));
        ItemStack ingredient = new ItemStack(ingredientItem);

        // The expected answer, taken from the game rather than from a table here.
        // mix() takes (ingredient, input) - see the class comment; getting this call
        // the wrong way round would make the test agree with the bug it checks for.
        ItemStack expected = level.potionBrewing().mix(ingredient, bottle);

        BlockPos pos = player.blockPosition().offset(1, 0, 0);
        clearSpot(level, pos);
        level.setBlock(pos, VeloceRegistry.BREWING_STAND.get().defaultBlockState(), Block.UPDATE_ALL);

        if (!(level.getBlockEntity(pos) instanceof VeloceBrewingStandBlockEntity stand)) {
            source.sendFailure(Component.literal("§cNo brewing stand block entity at " + pos));
            return 0;
        }

        stand.setItem(0, bottle.copy());
        stand.setItem(3, ingredient.copy());
        stand.energy = VeloceBrewingStandBlockEntity.ENERGY_CAPACITY;

        var server = level.getServer();
        server.tell(new TickTask(server.getTickCount() + SETTLE_TICKS,
                () -> verdict(level, pos, expected, potionId, ingredientId)));

        source.sendSuccess(() -> Component.literal("§6[brewcheck] §7stand placed at §f" + pos
                + "§7, " + potionId + " + " + ingredientId
                + "§7, expecting §f" + describe(expected)), false);
        return 1;
    }

    /** Removes whatever stands where the check is about to build. */
    private static void clearSpot(ServerLevel level, BlockPos pos) {
        level.removeBlock(pos, false);
    }

    private static void verdict(ServerLevel level, BlockPos pos, ItemStack expected,
                                String potionId, String ingredientId) {
        if (!(level.getBlockEntity(pos) instanceof VeloceBrewingStandBlockEntity stand)) {
            lastVerdict = "FAIL brewcheck: the stand disappeared from " + pos;
            return;
        }
        ItemStack got = stand.getItem(0);
        if (got.isEmpty()) {
            lastVerdict = "FAIL brewcheck: " + potionId + " + " + ingredientId
                    + " - the stand left the bottle slot empty (did it brew at all?)";
            return;
        }
        if (samePotion(expected, got)) {
            lastVerdict = "PASS brewcheck " + potionId + " + " + ingredientId
                    + " -> " + describe(got);
        } else {
            lastVerdict = "FAIL brewcheck: " + potionId + " + " + ingredientId
                    + " should give " + describe(expected) + " but the stand produced " + describe(got);
        }
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER, "[brewcheck] %s", lastVerdict);
    }

    /** Do these two stacks carry the same potion (or the same non-potion item)? */
    private static boolean samePotion(ItemStack a, ItemStack b) {
        ResourceLocation pa = potionId(a);
        ResourceLocation pb = potionId(b);
        return pa != null || pb != null
                ? java.util.Objects.equals(pa, pb)
                : a.getItem() == b.getItem();
    }

    private static ResourceLocation potionId(ItemStack stack) {
        if (stack.getItem() != Items.POTION) return null;
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion()
                .flatMap(holder -> holder.unwrapKey().map(key -> key.location()))
                .orElse(null);
    }

    private static String describe(ItemStack stack) {
        ResourceLocation id = potionId(stack);
        return id != null ? id.toString() : stack.getItem().toString();
    }
}
