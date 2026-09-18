package com.craftingveloce.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps the game RUNNING while an automated test script is playing.
 *
 * <p><b>The bug this exists for.</b> Every automated run measured a frozen world. The
 * driver's {@code wait:} looked like it worked - the script moved on - but a script that
 * took two readings of {@code /time query gametime} two hundred ticks apart got the SAME
 * number both times, and a machine placed against a spinning shaft read 0 RPM forever.
 * The cause is vanilla's {@code pauseOnLostFocus}: the test window never holds focus, so
 * singleplayer pauses itself, and a paused client does not tick its integrated server.
 * Create propagates rotation on a tick, so with no ticks nothing ever turns - and the
 * driver's own comment in {@code VeloceTestScript} ("real game time and not wall-clock
 * time that a paused server would skip") shows the author knew paused servers were a
 * hazard without doing anything about it.
 *
 * <p><b>Why the whole suite, not just the kinetic test.</b> Any script step that expects
 * the world to have moved on - a furnace burning, a pipe delivering, a machine spinning -
 * was silently reading the state of the world as it was when the command was issued. The
 * readings were not wrong about the game, they were answers about a game that had not
 * been allowed to run.
 *
 * <p><b>Why it is installed only for automated runs.</b> The property is checked once, at
 * install time: with no script configured this never registers a listener, so a person
 * playing the game normally keeps the pause behaviour they expect. Taking the pause away
 * from a human is not a side effect any test fix is allowed to have.
 */
public final class VeloceTestPauseGuard {

    private static boolean reported;

    private VeloceTestPauseGuard() {
    }

    /** Installs the guard if - and only if - an automated script is configured. */
    public static void install() {
        if (System.getProperty(com.craftingveloce.test.VeloceTestDriver.SCRIPT_PROPERTY) == null) {
            return;
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                ClientTickEvent.Post.class, event -> keepRunning(Minecraft.getInstance()));
    }

    private static void keepRunning(Minecraft minecraft) {
        // The OPTION, not just the current pause. Vanilla re-pauses on every focus loss,
        // so clearing a pause once would only fix the first one - and the window loses
        // focus repeatedly in an unattended run.
        if (minecraft.options.pauseOnLostFocus) {
            minecraft.options.pauseOnLostFocus = false;
            if (!reported) {
                reported = true;
                com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                        "[Veloce][TEST] pauseOnLostFocus turned off for the automated run: a "
                                + "paused client does not tick its server, so every `wait:` in a "
                                + "script would otherwise measure a world that is standing still");
            }
        }
        if (minecraft.isPaused()) {
            minecraft.pauseGame(false);
        }
    }
}
