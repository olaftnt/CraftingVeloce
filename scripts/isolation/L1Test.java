import java.lang.reflect.Method;

/**
 * L1/L2: does the mod work WITHOUT the foreign mods, and DOES it detect them when they are present?
 *
 * Checks the very essence of class isolation, without launching Minecraft:
 *  1. a gate class (XCompat) MUST be loadable - also without the foreign mod -
 *     and return a correct isPresent(),
 *  2. the module class (XRecipeFamily) may require the foreign mod (it has foreign
 *     types in fields) or may not (it has them only in method bodies); in BOTH cases
 *     what matters is that NOTHING loads it while the gate says "missing" -
 *     which is why the result here is information, not a criterion,
 *  3. the mod's main class must not die because of a MISSING FOREIGN MOD
 *     (outside the game it may die on missing registry bootstrap - that is an
 *     artefact of running outside Minecraft and is not an isolation error).
 *
 * NOTE: this test is NOT enough to police signatures. HotSpot resolves types
 * lazily, so a class with an UNUSED field of a foreign type will load without
 * the foreign mod and only blow up when that field is touched. That is why the
 * signatures of always-loaded classes are policed by the static check in
 * build.py (validate_compat_gates).
 *
 * Running:
 *   (build.py does the same automatically in step 4)
 *   java -cp .:BUILD_OUT:cp.txt:toms:rs L1Test
 *   java -cp .:BUILD_OUT:cp.txt:toms:rs:foreign-jars L1Test --with-mods
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

    /** Foreign package names - they tell us that an error is about isolation. */
    static final String[] FOREIGN = {"com.simibubi.create", "net.createmod",
            "com.smashingmods", "mekanism", "registrate", "flywheel", "ponder"};

    static int fails;

    public static void main(String[] args) throws Exception {
        boolean withMods = args.length > 0 && "--with-mods".equals(args[0]);
        System.out.println(withMods ? "=== L2: with the foreign mods" : "=== L1: without the foreign mods");
        if (withMods && !fmlAvailable()) {
            // Outside the game the loader keeps no mod list, so isPresent() always
            // says "missing". That is not an isolation error - L2 has to be done in game.
            System.out.println("SKIP - outside Minecraft there is no mod list (ModList=null);");
            System.out.println("       check L2 in game: log '[Veloce][COMPAT] <mod>: present'.");
            return;
        }

        for (String[] row : GATES) {
            checkGate(row[0], row[1], row[2], withMods);
        }
        checkMainClass();

        System.out.println();
        System.out.println(fails == 0
                ? (withMods ? "OK - L2: gates detect the mods and the modules load"
                            : "OK - L1: the mod works without the foreign mods")
                : fails + " ERRORS");
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
            fail(mod + ": the gate does not load without the foreign mod: " + t);
            return;
        }
        ok(mod + ": the gate loads without the foreign mod");

        boolean present;
        try {
            Method m = gate.getMethod("isPresent");
            present = (Boolean) m.invoke(null);
        } catch (Throwable t) {
            fail(mod + ": isPresent() threw " + t);
            return;
        }
        if (present == withMods) {
            ok(mod + ": isPresent()=" + present + " (correct)");
        } else {
            fail(mod + ": isPresent()=" + present + ", but the mod is "
                    + (withMods ? "present" : "absent"));
        }

        Throwable familyError = null;
        try {
            Class.forName(familyName);
        } catch (Throwable t) {
            familyError = t;
        }
        String how = familyError == null
                ? "loads without the foreign mod (foreign types only in method bodies)"
                : "requires the foreign mod (" + familyError.getClass().getSimpleName() + ")";
        ok(mod + ": the module class " + how);
        if (familyError != null && !mentionsForeign(familyError)) {
            fail(mod + ": the module class failed for a reason other than the foreign mod: " + familyError);
        }
    }

    static void checkMainClass() {
        try {
            Class.forName("com.craftingveloce.CraftingVeloceMod");
            ok("CraftingVeloceMod: the class loads without the foreign mods");
            return;
        } catch (Throwable t) {
            if (mentionsForeign(t)) {
                fail("CraftingVeloceMod depends on a foreign mod: " + root(t));
                return;
            }
            ok("CraftingVeloceMod: outside the game it fails on the registry bootstrap ("
                    + root(t) + ") - that is not an isolation error");
        }
    }

    /** Whether the cause chain points at a missing foreign mod class. */
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
