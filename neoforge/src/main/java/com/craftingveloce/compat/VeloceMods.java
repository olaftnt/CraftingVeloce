package com.craftingveloce.compat;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

/**
 * The mods with which Veloce has optional integration.
 *
 * <p><b>Why an enum instead of a bare string in every place.</b> The mod
 * identifier used to be typed by hand wherever presence had to be checked. An
 * enum gives one place with the list and one place with the question "is it
 * there?".
 *
 * <p><b>Why {@code LoadingModList} before {@code ModList}.</b> Some decisions
 * (e.g. whether to register classes with a foreign type at all) are made very
 * early, when {@code ModList} does not exist yet. {@code LoadingModList} works
 * from the start of the loader. Once {@code ModList} is available we use it - it
 * is a layer above and does not require reading mod files.
 *
 * <p><b>This class MUST NOT contain foreign mod types.</b> It is loaded ALWAYS
 * (also without those mods), so any foreign type in a field or a signature would
 * take the mod down at class loading itself - {@code NoClassDefFoundError} is
 * thrown during linking, before any {@code try/catch} gets a chance to work.
 */
public enum VeloceMods {

    /** Create - kinetic machines (mill, saw, press, basin, crusher). */
    CREATE("create"),

    /** Alchemistry - chemical machines powered by FE (compactor, combiner...). */
    ALCHEMISTRY("alchemistry"),

    /** Mekanism - item machines powered by FE (crusher, enrichment...). */
    MEKANISM("mekanism"),

    /**
     * JEI - recipe viewer; our blocks as category catalysts.
     *
     * <p>It is the only one on this list that is NOT called from
     * {@code CraftingVeloceMod}: JEI finds our plugin by itself via the
     * {@code @JeiPlugin} annotation, and that file is created at JEI start-up.
     * The entry is here so that the mod list is complete and so that the log
     * shows whether JEI is present.
     */
    JEI("jei"),

    /**
     * Jade - the tooltip under the crosshair; it describes our machines.
     *
     * <p>Just like JEI: the plugin ({@code compat/jade/VeloceJadePlugin}) is
     * found by Jade itself via the {@code @WailaPlugin} annotation, so the core
     * does not call it. The entry is here for the presence log and for a build
     * check.
     */
    JADE("jade"),

    /**
     * Refined Storage - an alternative storage network the terminal and the pipes
     * can read.
     *
     * <p><b>Why this entry had to exist.</b> RS was the one integration without a
     * gate: {@code RefinedStorageHelper} was called straight from
     * {@code VelocePipeBlock.canConnectDirection}, {@code ConnectedEndpointInfo}
     * and {@code VelocePipeNetworkManager}. Its own signatures carry no RS type,
     * which is why it looked safe - but linking the class still needs the RS types
     * its code refers to, and that happens the first time a method of it is
     * executed. With RS absent the result was
     * {@code NoClassDefFoundError: .../resource/ResourceKey} thrown in the caller,
     * before any {@code try/catch} inside the helper could run.
     *
     * <p>Observed for real: placing a pipe with Refined Storage not installed
     * crashed the server tick loop. The gates below never evaluate the helper when
     * RS is missing, so the class is never resolved.
     */
    REFINED_STORAGE("refinedstorage");

    private final String id;

    VeloceMods(String id) {
        this.id = id;
    }

    /** The mod identifier as the loader understands it, e.g. {@code "create"}. */
    public String id() {
        return id;
    }

    /** Whether this mod is present in this game instance. */
    public boolean isLoaded() {
        LoadingModList loading = LoadingModList.get();
        if (loading != null) {
            return loading.getModFileById(id) != null;
        }
        ModList list = ModList.get();
        return list != null && list.isLoaded(id);
    }

    /**
     * Runs an action only when the mod is present.
     *
     * <p>NOTE: this does NOT protect against {@code NoClassDefFoundError} in the
     * action class (see the class comment). The action is allowed to reference
     * classes with a foreign type, but the decision itself must be made BEFORE
     * calling it - that is why the caller checks {@link #isLoaded()} in a gate
     * class without foreign types, and only then reaches for the integration
     * class.
     */
    public static void executeIfInstalled(VeloceMods mod, Runnable action) {
        if (mod.isLoaded()) {
            action.run();
        }
    }
}
