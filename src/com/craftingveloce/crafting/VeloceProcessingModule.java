package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;
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

    /**
     * Receptury tego modulu, ktore wytwarzaja dany item.
     *
     * <p><b>Po co.</b> Sama lista "co umiem zrobic" ({@link #producible}) nie
     * wystarcza planerowi - on potrzebuje konkretnych receptur ze skladnikami
     * i liczbami sztuk, zeby policzyc, ile da sie zrobic z tego, co jest
     * w sieci. Moduly z innych modow maja wlasne modele receptur, wiec to one
     * tlumacza je na wspolny {@link ProcessingEntry}.
     *
     * <p>Domyslnie pusto: modul, ktory tylko doklada gotowe receptury do
     * wspolnego indeksu (jak piec), nie musi nic implementowac.
     *
     * <p>Modul dostaje siec, bo o tym, ktore receptury sa wykonalne, decyduja
     * MASZYNY stojace w sieci (i to, czy maja prad) - a modul moze obslugiwac
     * kilka rodzin naraz (np. kruszarka i pila), kazda z wlasna maszyna.
     */
    default List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                             Item item) {
        return List.of();
    }

    /**
     * Receptury tego modulu na dany item BEZ patrzenia na maszyny i zasilanie.
     *
     * <p><b>Po co osobna metoda.</b> {@link #recipesFor} odpowiada na pytanie
     * planera: "co moge zrobic TERAZ" - wiec wymaga maszyny w sieci i pradu.
     * Narzedzia (np. komenda {@code /cv getitems}) pytaja o cos innego: "JAK
     * sie to w ogole robi" - i musza dostac recepture takze wtedy, gdy gracz
     * nie ma jeszcze maszyny. Bez tego rozroznienia komenda twierdzila, ze
     * item nie ma receptury, choc receptura istnieje (np. mechanical crafting
     * Create) - a to najgorszy rodzaj bledu: brak informacji udajacy informacje.
     */
    default List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        return List.of();
    }

    /**
     * Czysci pamiec modulu (indeksy receptur).
     *
     * <p>Wolane przy zmianie swiata / przeladowaniu danych. Domyslnie nic -
     * modul bez pamieci nie ma czego czyscic. Rdzen nie musi znac zadnego
     * modulu, zeby to wywolac.
     */
    default void invalidate() {
    }
}
