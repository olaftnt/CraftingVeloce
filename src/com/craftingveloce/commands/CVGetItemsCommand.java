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
 * {@code /cv getitems <item>} - wklada do skrzynki, na ktora patrzysz, WSZYSTKIE
 * skladniki potrzebne do wytworzenia podanego itemu.
 *
 * <p><b>Po co.</b> Zeby sprawdzic recepture w grze, trzeba ja najpierw znac
 * (JEI, wiki) i wyklikac skladniki recznie. Przy testowaniu automatyzacji to
 * dziesiatki powtorzen: patrzysz na skrzynke, mowisz "zrob mi z tego chest"
 * i masz komplet materialow w jednym miejscu.
 *
 * <p><b>Skad skladniki.</b> Z tego samego indeksu receptur, ktorego uzywa
 * auto-crafter ({@link VeloceRecipeRegistry}) - czyli dokladnie ta receptura,
 * ktora automat naprawde wykona. Gdy item powstaje WYLACZNIE w piecu, bierzemy
 * recepture pieca (i mowimy o tym wprost, bo w skrzyni wyladuje surowiec, a nie
 * gotowy item).
 *
 * <p><b>Czego komenda NIE robi.</b> Nie liczy calego drzewa receptur
 * (deski -> klody) i nie dropuje niczego na ziemie. Daje jeden poziom
 * skladnikow, a to, co sie nie zmiescilo, zglasza w czacie - cicha zguba
 * itemow bylaby gorsza niz brak komendy.
 */
public final class CVGetItemsCommand {

    private CVGetItemsCommand() {
    }

    /**
     * Zasieg patrzenia. Wiekszy niz zasieg gracza (4.5-5.5 klocka), bo to
     * komenda diagnostyczna - gracz stoi przy skrzyni, a nie musi w nia
     * "celowac" z dokladnoscia do pol klocka.
     */
    private static final double LOOK_REACH = 16.0D;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext) {
        dispatcher.register(
            CVCommandRoot.root()
                .then(Commands.literal("getitems")
                    // ItemArgument daje podpowiadanie id itemow (jak /give),
                    // wiec nie trzeba ich znac na pamiec.
                    .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .executes(context -> run(context, 1))
                        // Item moze miec kilka receptur (np. deski z kazdego
                        // gatunku klody) - numer wybiera, ktora wziac.
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
                    "§c[CraftingVeloce] Ta komenda dziala tylko dla gracza - trzeba na cos patrzec."));
            return 0;
        }
        Item item = ItemArgument.getItem(context, "item").getItem();
        ServerLevel level = player.serverLevel();

        BlockPos target = lookedAtBlock(player);
        if (target == null) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] Nie patrzysz na zaden blok - wyceluj w skrzynke."));
            return 0;
        }
        IItemHandler handler = itemHandlerAt(level, target);
        if (handler == null) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] Blok, na ktory patrzysz, nie jest magazynem "
                            + "(brak ItemHandler)."));
            return 0;
        }

        List<ProcessingEntry> recipes = recipesFor(level, item);
        if (recipes.isEmpty()) {
            source.sendFailure(Component.literal("§c[CraftingVeloce] Nie ma receptury na "
                    + new ItemStack(item).getHoverName().getString()
                    + " (§7" + itemId(item) + "§c)."));
            return 0;
        }
        if (recipeNumber > recipes.size()) {
            source.sendFailure(Component.literal("§c[CraftingVeloce] Ten item ma tylko §f"
                    + recipes.size() + "§c receptur(y) - wybierz numer od 1 do "
                    + recipes.size() + "."));
            return 0;
        }
        ProcessingEntry recipe = recipes.get(recipeNumber - 1);
        Kit kit = ingredientsOf(recipe);
        Map<Item, Integer> needed = kit.items();
        if (needed.isEmpty()) {
            source.sendFailure(Component.literal(
                    "§c[CraftingVeloce] Ta receptura nie ma zadnych skladnikow."));
            return 0;
        }
        if (kit.emptyIngredients() > 0) {
            source.sendSuccess(() -> Component.literal("§cUwaga: §e"
                    + kit.emptyIngredients() + " skladnik(ow) nie ma zadnego itemu "
                    + "(pusty tag) - zestaw bedzie niepelny."), false);
        }

        report(source, player, item, recipe, recipes.size());
        Map<Item, Integer> leftovers = insertAll(handler, needed);
        reportResult(source, target, needed, leftovers);

        // Zmiana zawartosci skrzyni musi byc widoczna dla gry i sieci.
        level.updateNeighborsAt(target, level.getBlockState(target).getBlock());
        return 1;
    }

    /**
     * Receptury, z ktorych liczymy skladniki.
     *
     * <p>Bierzemy je ze WSZYSTKICH zrodel ({@link VeloceRecipeFinder}):
     * crafting table, rodziny modulow (Create/Alchemistry/Mekanism) i piec.
     *
     * <p><b>BUG, ktory to naprawia (zgloszenie gracza).</b> Wczesniej komenda
     * pytala tylko indeks waniliowy, wiec dla itemu powstajacego w maszynie
     * modulu (np. crushing wheel z Create, robiony wylacznie mechanical
     * craftingiem) mowila "nie ma receptury" - mimo ze receptura istnieje.
     * Brak informacji udawal informacje, a to najgorszy rodzaj bledu.
     *
     * <p>To CELOWO nie patrzy na maszyny i prad: gracz pyta "jak sie to robi",
     * a nie "czy moge to zrobic w tej sieci".
     */
    private static List<ProcessingEntry> recipesFor(ServerLevel level, Item item) {
        return VeloceRecipeFinder.all(level, item);
    }

    /**
     * Skladniki receptury: "item -> ile sztuk" plus liczba skladnikow, ktore
     * nie maja ZADNEJ opcji (pusty tag).
     *
     * <p>Z kazdego skladnika bierzemy PIERWSZA dostepna opcje (receptura moze
     * akceptowac kilka itemow, np. kazdy gatunek desek), a liczbe sztuk
     * przemnazamy przez liczbe wymaganych sztuk danego skladnika - tak samo
     * liczy to planer auto-craftera.
     *
     * <p>Pusty tag to nie "pusty slot": slot siatki bez skladnika pomijamy
     * cicho (tak dziala receptura), ale skladnik, ktory NIE MA zadnego itemu,
     * musi byc zgloszony - inaczej gracz dostalby niepelny zestaw i nie
     * wiedzialby dlaczego.
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
                // Slot siatki bez skladnika ma "pusta" liste opcji i to jest
                // normalne; odrozniamy go od skladnika bez opcji po tym, ze
                // receptura wymienia go jako skladnik niepusty.
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
     * Czy skladnik na tej pozycji jest "prawdziwy" (nie pusty slot siatki).
     *
     * <p>Vanilla reprezentuje pusty slot siatki jako {@code Ingredient.EMPTY}
     * - nie ma on zadnych itemow i tyle samo zwraca dla pustego tagu. Rozrozniamy
     * je po tozsamosci z {@code Ingredient.EMPTY}, bo tylko ten drugi przypadek
     * jest bledem danych.
     */
    private static boolean isRealIngredient(ProcessingEntry recipe, int index) {
        return recipe.ingredients().get(index) != Ingredient.EMPTY;
    }

    /**
     * Wklada wszystko do magazynu. Zwraca liczbe pozycji, ktore sie NIE zmiescily.
     *
     * <p>Nic nie jest dropowane: gracz dostaje w czacie liste brakow, a nie
     * przedmioty na ziemi (komenda ma przygotowac skrzynke, a nie zasmiecac
     * swiat).
     */
    private static Map<Item, Integer> insertAll(IItemHandler handler,
                                                Map<Item, Integer> needed) {
        Map<Item, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            // Wkladamy POJEDYNCZYMI stosami (max stack size), a nie jedna
            // wielka liczba: receptury mechaniczne Create maja po kilkadziesiat
            // sztuk jednego skladnika, a ItemStack wiekszy niz stack size bywa
            // odrzucany albo obcinany przez magazyny.
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
                    break;   // magazyn pelny - nie krecimy sie w kolko
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
            kind = "pieca";
        } else if (VeloceRecipeFinder.isModuleRecipe(recipe)) {
            // Rodzina z innego moda - mowimy WPROST, ze potrzebna jest maszyna
            // modulu, a nie crafting table.
            kind = "maszyny: §f" + VeloceRecipeFinder.typeName(recipe.type()) + "§7";
        } else {
            kind = "craftingu";
        }
        source.sendSuccess(() -> Component.literal(
                "§6[CraftingVeloce] Skladniki na §f" + name + " §7(" + itemId(item) + ")"), false);
        int perCraft = Math.max(1, recipe.primaryResult().getCount());
        source.sendSuccess(() -> Component.literal(
                "§7Receptura " + kind + ": §f" + recipe.id()
                        + (recipeCount > 1 ? " §7(1 z " + recipeCount + ")" : "")
                        + " §7- jedno wykonanie daje §f" + perCraft + "x "
                        + nameOf(item)), false);
        if (recipe.isFurnace()) {
            source.sendSuccess(() -> Component.literal(
                    "§eTo receptura PIECA - w skrzyni laduje surowiec, nie gotowy item."), false);
        }
    }

    /**
     * Podsumowanie: co dokladnie weszlo (z liczbami i nazwami) i co sie nie
     * zmiescilo.
     *
     * <p>Nazwy sa wazne przy skladnikach z tagiem (np. "dowolne deski"): gracz
     * musi widziec, ktora opcje komenda wybrala, bo tylko ta trafila do skrzyni.
     * Dlatego mowimy to WPROST, a nie "wlozylem 3 rodzaje skladnikow".
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
        source.sendSuccess(() -> Component.literal("§aWlozylem do §f["
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]§a: "
                + String.join("§7, §r", inserted)), false);
        if (!missed.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§cNie zmiescilo sie: §r"
                    + String.join("§7, §r", missed)), false);
        }
    }

    private static String nameOf(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    /** Blok, na ktory patrzy gracz (albo {@code null}, gdy patrzy w powietrze). */
    private static BlockPos lookedAtBlock(ServerPlayer player) {
        HitResult hit = player.pick(LOOK_REACH, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((BlockHitResult) hit).getBlockPos();
    }

    /** Magazyn (ItemHandler) w danym bloku - z kazdej strony, jaka wystawia. */
    private static IItemHandler itemHandlerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state,
                level.getBlockEntity(pos), null);
    }

    private static String itemId(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
