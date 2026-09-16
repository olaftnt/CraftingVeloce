package com.craftingveloce.item;

import com.craftingveloce.block.VeloceIntegraleConversions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Item klatki Veloce Integrale - z podpowiedzia, co z czego powstaje.
 *
 * <p><b>Po co podpowiedz.</b> Klatka to obudowa: prawy klik odpowiednim
 * waniliowym klockiem (pulpit, dozownik, obserwator, stol craftingu, piec)
 * zamienia ja na nasza maszyne. Bez tej listy gracz nie ma jak sie o tym
 * dowiedziec - a sama tabela jest w JEDNYM miejscu
 * ({@link VeloceIntegraleConversions}), wiec podpowiedz nie moze sie z nia
 * rozjechac: jest z niej generowana.
 *
 * <p>Nazwy bierzemy z rejestru ({@code Block.getName()}), wiec tlumaczenia
 * dzialaja same i nie trzeba wypisywac ich drugi raz.
 */
public class VeloceIntegraleItem extends BlockItem {

    /** Naglowek listy przepisan. */
    public static final String CONVERT_KEY = "gui.craftingveloce.integrale.convert";

    public VeloceIntegraleItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(CONVERT_KEY).withStyle(ChatFormatting.GRAY));
        for (VeloceIntegraleConversions.Conversion conversion : VeloceIntegraleConversions.all()) {
            // Klucz to ID (blok z innego moda moze jeszcze nie istniec w chwili
            // rejestracji wpisu), wiec nazwe rozwiazujemy dopiero tutaj.
            Block input = BuiltInRegistries.BLOCK.get(conversion.inputId());
            if (input == net.minecraft.world.level.block.Blocks.AIR) {
                continue;
            }
            tooltip.add(Component.literal(" ")
                    .append(input.getName())
                    .append(Component.literal(" -> "))
                    .append(conversion.resultBlock().getName())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
