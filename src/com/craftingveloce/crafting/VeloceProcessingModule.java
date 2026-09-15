package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Set;

/**
 * JEDEN sposob przetwarzania rzeczy w sieci.
 *
 * <p><b>Po co ten interfejs.</b> Do tej pory rdzen mial wpisane na sztywno dwa
 * sposoby: crafter (receptury craftingowe) i piec (przepalanie). Wystarczylo
 * wyjac crafter z sieci, zeby siec z samym piecem przestala umiec zrobic szklo
 * z piasku - mimo ze szklo powstaje WYLACZNIE w piecu i crafter nie jest do
 * tego potrzebny. Ta sama sztywnosc blokowalaby kazdy kolejny modul (Create,
 * Alchemistry, Mekanism, enchanting, smithing...).
 *
 * <p>Teraz rdzen zna wylacznie ten interfejs i pyta KAZDY zarejestrowany
 * modul o to samo:
 * <ul>
 *   <li>{@link #available} - czy w sieci stoi maszyna tego modulu,</li>
 *   <li>{@link #powered} - czy ta maszyna jest teraz zdolna do pracy,</li>
 *   <li>{@link #producible} - co ten modul potrafi zrobic.</li>
 * </ul>
 *
 * <p>Dodanie nowego modulu = jedna rejestracja w
 * {@link VeloceProcessingRegistry} (albo w {@code compat/*}) - bez dotykania
 * planera, liczenia liczb i kontrolera.
 */
public interface VeloceProcessingModule {

    /** Krotki identyfikator do logow i diagnostyki, np. {@code "crafting"}, {@code "furnace"}. */
    String id();

    /** Typy receptur obslugiwane przez ten modul (rodzina z {@link VeloceRecipeFamilies}). */
    Set<RecipeType<?>> recipeTypes();

    /**
     * Itemy, ktore ten modul POTRAFI zrobic - bez patrzenia na zasilanie.
     *
     * <p>Wynik moze zalezec od sieci (np. modul craftingowy odejmuje itemy
     * wylaczone w crafterze), dlatego dostaje level i siec.
     */
    Set<Item> producible(ServerLevel level, VelocePipeNetwork network);

    /** Czy w sieci stoi maszyna tego modulu - nawet bez paliwa/pradu. */
    boolean available(ServerLevel level, VelocePipeNetwork network);

    /** Czy maszyna tego modulu jest teraz zdolna wykonac operacje. */
    boolean powered(ServerLevel level, VelocePipeNetwork network);
}
