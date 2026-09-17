package com.craftingveloce.compat.jade;

import com.craftingveloce.compat.VeloceMods;

/**
 * Gate for the Jade integration (tooltips under the crosshair).
 *
 * <p><b>Isolation.</b> Loaded ALWAYS, even without Jade - zero Jade types in
 * fields and signatures. The plugin ({@link VeloceJadePlugin}) has foreign types
 * in its signatures, but Jade itself loads it, by scanning for the
 * {@code @WailaPlugin} annotation - without Jade there is no one to do that.
 *
 * <p>The entry in {@link VeloceMods} is used for the presence log and for the
 * build check; the plugin itself is discovered by Jade, so the core does not
 * call it.
 */
public final class JadeCompat {

    private JadeCompat() {
    }

    /** Whether Jade is present. Safe also without Jade. */
    public static boolean isPresent() {
        return VeloceMods.JADE.isLoaded();
    }
}
