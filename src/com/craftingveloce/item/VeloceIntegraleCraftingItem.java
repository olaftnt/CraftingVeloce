package com.craftingveloce.item;

import com.craftingveloce.block.VeloceCraftingTableBlock;
import com.craftingveloce.block.VeloceIntegraleFrame;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Stol craftingu stojacy w KLATCE Veloce Integrale - jeden przedmiot.
 *
 * <p><b>Skad sie bierze.</b> Gracz stawia klatke, prawym klikiem stolem
 * craftingu podmienia ja na stacje (patrz {@code VeloceIntegraleBlock}),
 * a przy zbiciu odzyskuje cala rzecz jako TEN przedmiot: rama + stol w jednym.
 *
 * <p><b>Po co osobny item, a nie sam blok stolu.</b> Ikona i nazwa mowia
 * graczowi (i modom od receptur), ze to nie jest zwykly stol, tylko stanowisko
 * craftingu w obudowie - w srodku widac stol. Nazwa jest wiec nazwa KLATKI,
 * dlatego nadpisujemy {@link #getDescriptionId()} (BlockItem domyslnie
 * pokazywalby nazwe bloku stolu).
 *
 * <p><b>Postawienie.</b> Ten sam blok co zwykly stol, ale ze stanem
 * {@code facade = true} - inaczej postawiony przedmiot zamienilby sie
 * w zwykly stol i gracz zgubilby ramę.
 */
public class VeloceIntegraleCraftingItem extends BlockItem {

    /** Klucz nazwy: nazwa KLATKI, nie bloku stolu. */
    public static final String NAME_KEY = "block.craftingveloce.veloce_integrale_crafting";

    /** Jedna linia opisu - zeby bylo jasne, ze w srodku jest dzialajacy stol. */
    public static final String TOOLTIP_KEY = "gui.craftingveloce.integrale.crafting.tooltip";

    public VeloceIntegraleCraftingItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public String getDescriptionId() {
        return NAME_KEY;
    }

    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) {
            return null;
        }
        // Fasada + zaslepki okien od razu na miejscu: nowy blok ma byc gotowa
        // klatka, a nie otwartym szkieletem, ktory domknie sie dopiero przy
        // nastepnej zmianie sasiada.
        return VeloceIntegraleFrame.withPlacementClosures(context.getLevel(),
                context.getClickedPos(), state.setValue(VeloceCraftingTableBlock.FACADE, true));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(TOOLTIP_KEY).withStyle(ChatFormatting.GRAY));
    }
}
