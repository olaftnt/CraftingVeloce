import java.lang.reflect.Method;

/**
 * L1/L2: czy mod dziala BEZ obcych modow i CZY je wykrywa, gdy sa?
 *
 * Sprawdza sama istote izolacji klas, bez uruchamiania Minecrafta:
 *  1. klasa-bramka (XCompat) MUSI dac sie zaladowac - takze bez obcego moda -
 *     i zwrocic poprawne isPresent(),
 *  2. klasa modulu (XRecipeFamily) moze wymagac obcego moda (ma obce typy
 *     w polach) albo nie (ma je tylko w cialach metod); w OBU przypadkach
 *     wazne jest to, ze NIC jej nie laduje, dopoki bramka mowi "brak" -
 *     dlatego wynik jest tu informacja, a nie kryterium,
 *  3. klasa glowna moda nie moze zginac z powodu BRAKU OBCEGO MODA
 *     (poza gra moze zginac na braku bootstrapu rejestrow - to artefakt
 *     uruchamiania poza Minecraftem i nie jest bledem izolacji).
 *
 * UWAGA: ten test NIE wystarcza do pilnowania sygnatur. HotSpot rozwiazuje
 * typy leniwie, wiec klasa z NIEUZYWANYM polem obcego typu zaladuje sie bez
 * obcego moda i wywali sie dopiero przy dotknieciu tego pola. Dlatego
 * sygnatur klas ladowanych zawsze pilnuje statyczna kontrola w build.py
 * (validate_compat_gates).
 *
 * Uruchomienie:
 *   (build.py robi to samo automatycznie w kroku 4)
 *   java -cp .:BUILD_OUT:cp.txt:toms:rs L1Test
 *   java -cp .:BUILD_OUT:cp.txt:toms:rs:obce-jary L1Test --with-mods
 */
public class L1Test {

    static final String[][] GATES = {
            {"create", "com.craftingveloce.compat.create.CreateCompat",
                    "com.craftingveloce.compat.create.CreateRecipeFamily"},
            {"alchemistry", "com.craftingveloce.compat.alchemistry.AlchemistryCompat",
                    "com.craftingveloce.compat.alchemistry.AlchemistryRecipeFamily"},
            {"mekanism", "com.craftingveloce.compat.mekanism.MekanismCompat",
                    "com.craftingveloce.compat.mekanism.MekanismRecipeFamily"},
    };

    /** Nazwy obcych pakietow - po nich poznajemy, ze blad dotyczy izolacji. */
    static final String[] FOREIGN = {"com.simibubi.create", "net.createmod",
            "com.smashingmods", "mekanism", "registrate", "flywheel", "ponder"};

    static int fails;

    public static void main(String[] args) throws Exception {
        boolean withMods = args.length > 0 && "--with-mods".equals(args[0]);
        System.out.println(withMods ? "=== L2: z obcymi modami" : "=== L1: bez obcych modow");
        if (withMods && !fmlAvailable()) {
            // Poza gra loader nie prowadzi listy modow, wiec isPresent() zawsze
            // powie "brak". To nie blad izolacji - L2 trzeba zrobic w grze.
            System.out.println("SKIP - poza Minecraftem nie ma listy modow (ModList=null);");
            System.out.println("       L2 sprawdz w grze: log '[Veloce][COMPAT] <mod>: obecny'.");
            return;
        }

        for (String[] row : GATES) {
            checkGate(row[0], row[1], row[2], withMods);
        }
        checkMainClass();

        System.out.println();
        System.out.println(fails == 0
                ? (withMods ? "OK - L2: bramki wykrywaja mody i moduly sie laduja"
                            : "OK - L1: mod dziala bez obcych modow")
                : fails + " BLEDOW");
        if (fails != 0) System.exit(1);
    }

    static boolean fmlAvailable() {
        try {
            Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Object list = loading.getMethod("get").invoke(null);
            if (list != null) {
                return true;
            }
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            return modList.getMethod("get").invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static void checkGate(String mod, String gateName, String familyName, boolean withMods)
            throws Exception {
        Class<?> gate;
        try {
            gate = Class.forName(gateName);
        } catch (Throwable t) {
            fail(mod + ": bramka nie laduje sie bez obcego moda: " + t);
            return;
        }
        ok(mod + ": bramka laduje sie bez obcego moda");

        boolean present;
        try {
            Method m = gate.getMethod("isPresent");
            present = (Boolean) m.invoke(null);
        } catch (Throwable t) {
            fail(mod + ": isPresent() rzucilo " + t);
            return;
        }
        if (present == withMods) {
            ok(mod + ": isPresent()=" + present + " (poprawnie)");
        } else {
            fail(mod + ": isPresent()=" + present + ", a mod jest "
                    + (withMods ? "obecny" : "nieobecny"));
        }

        Throwable familyError = null;
        try {
            Class.forName(familyName);
        } catch (Throwable t) {
            familyError = t;
        }
        String how = familyError == null
                ? "laduje sie bez obcego moda (obce typy tylko w cialach metod)"
                : "wymaga obcego moda (" + familyError.getClass().getSimpleName() + ")";
        ok(mod + ": klasa modulu " + how);
        if (familyError != null && !mentionsForeign(familyError)) {
            fail(mod + ": klasa modulu padla z innego powodu niz obcy mod: " + familyError);
        }
    }

    static void checkMainClass() {
        try {
            Class.forName("com.craftingveloce.CraftingVeloceMod");
            ok("CraftingVeloceMod: klasa laduje sie bez obcych modow");
            return;
        } catch (Throwable t) {
            if (mentionsForeign(t)) {
                fail("CraftingVeloceMod zalezy od obcego moda: " + root(t));
                return;
            }
            ok("CraftingVeloceMod: poza gra pada na bootstrapie rejestrow ("
                    + root(t) + ") - to nie blad izolacji");
        }
    }

    /** Czy lancuch przyczyn wskazuje na brak klasy obcego moda. */
    static boolean mentionsForeign(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String text = String.valueOf(c);
            for (String foreign : FOREIGN) {
                if (text.contains(foreign)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    static void ok(String msg) {
        System.out.println("OK   " + msg);
    }

    static void fail(String msg) {
        fails++;
        System.out.println("FAIL " + msg);
    }
}
