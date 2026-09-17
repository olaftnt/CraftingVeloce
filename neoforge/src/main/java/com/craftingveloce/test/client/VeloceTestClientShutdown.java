package com.craftingveloce.test.client;

/**
 * Quits the game client after an automated test run.
 *
 * <p><b>Why this class exists separately.</b> It touches
 * {@link net.minecraft.client.Minecraft}, which is a client-only class. It must
 * therefore never be loaded on a dedicated server - the caller checks the
 * distribution first and only then references this class, so the JVM resolves it
 * lazily and a server never touches it.
 *
 * <p><b>Why {@code server.halt()} is not enough.</b> In singleplayer the client
 * and the server are the same process. Halting the server stops the world but
 * leaves the client running, showing "Connection lost" and waiting for input
 * forever. The Gradle task then never returns, and a run that actually passed
 * looks like a hang. Measured: a module test sat on that screen for over six
 * minutes until it was killed.
 */
public final class VeloceTestClientShutdown {

    private VeloceTestClientShutdown() {
    }

    /** Shuts the client down cleanly, which also ends the process. */
    public static void quit() {
        net.minecraft.client.Minecraft.getInstance().stop();
    }
}
