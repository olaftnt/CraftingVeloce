package com.craftingveloce.block;

/**
 * Obudowa, ktora gracz BUDUJE element po elemencie.
 *
 * <p><b>Jak to dziala w grze.</b> Gracz stawia pusta obudowe
 * ({@code veloce_integrale}), a potem doklada do niej klocki bazowe:
 * jedno kolo mlynskie na klik, jedno oczko mechanical craftera na klik.
 * Pierwszy klik ZAMIENIA obudowe na maszyne (patrz
 * {@code VeloceIntegraleConversions}), a kazdy nastepny doklada kolejny
 * element - dlatego maszyna jest pusta, dopoki gracz czegos nie wlozy.
 *
 * <p>Bez tego interfejsu maszyna postawiona z zakladki kreatywnej pokazywalaby
 * gotowy klocek w srodku (a gracz: "jak postawie z creative'a, to po prostu
 * jest jeden render klocka, tak jak zwykly crafting - to jest blad").
 */
public interface VeloceCaseBuildable {

    /**
     * Dokłada jeden element do wnetrza obudowy.
     *
     * @return {@code true} gdy element sie zmiescil (wtedy wolajacy zabiera
     *         przedmiot z reki gracza)
     */
    boolean addPart();
}
