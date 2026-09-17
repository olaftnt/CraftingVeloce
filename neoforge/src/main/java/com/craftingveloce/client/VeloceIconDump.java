package com.craftingveloce.client;

import com.craftingveloce.client.gui.VeloceIconDumpScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Drives {@link VeloceIconDumpScreen} over a queue of items, one per screen.
 *
 * <p>Each icon needs one frame to be drawn and the next to be captured, so the items are
 * done in sequence rather than all at once - and a queue is also what makes "dump
 * everything" a single action instead of forty-four.
 *
 * <p><b>Started by a system property.</b> {@code -Dveloce.dumpIcons=all} begins the dump
 * as soon as the player is in a world. The command exists too, but a tool that has to be
 * typed cannot be run from a test script, and this one has to run while somebody is
 * looking at the result.
 */
public final class VeloceIconDump {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-icon-dump");

    /** Where the PNGs go: the repo's asset tree, so they can be committed as art. */
    private static final Path OUTPUT = Path.of("..", "..", "assets", "craftingveloce", "icon-dump");

    private static final Deque<ItemStack> QUEUE = new ArrayDeque<>();
    private static boolean autoStarted;

    private VeloceIconDump() {
    }

    /** Queues the empty casing plus every machine the casing can hold. */
    public static int queueAll() {
        QUEUE.clear();
        add(com.craftingveloce.init.VeloceRegistry.VELOCE_INTEGRALE_ITEM.get());
        for (com.craftingveloce.block.VeloceCaseContents.Entry entry
                : com.craftingveloce.block.VeloceCaseContents.all()) {
            add(entry.content().get().asItem());
        }
        // The mapping is written next to the pictures, because the composition of the
        // icons needs to know WHICH machine goes into WHICH casing and that pairing lives
        // only in VeloceCaseContents. Reading it back out of Java with a regex would be a
        // second, drifting copy of the table.
        StringBuilder map = new StringBuilder();
        for (com.craftingveloce.block.VeloceCaseContents.Entry entry
                : com.craftingveloce.block.VeloceCaseContents.all()) {
            map.append(BuiltInRegistries.BLOCK.getKey(entry.machine().get()).getPath())
                    .append('=')
                    .append(BuiltInRegistries.ITEM.getKey(entry.content().get().asItem()).getPath())
                    .append('\n');
        }
        try {
            java.nio.file.Files.createDirectories(OUTPUT);
            java.nio.file.Files.writeString(OUTPUT.resolve("_map.txt"), map.toString());
        } catch (java.io.IOException e) {
            LOG.error("[Veloce] icon dump: could not write the map", e);
        }
        LOG.info("[Veloce] icon dump queued: {} item(s)", QUEUE.size());
        return QUEUE.size();
    }

    public static int queue(String itemId) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(
                itemId.contains(":") ? itemId : "minecraft:" + itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return 0;
        }
        QUEUE.clear();
        add(item);
        return 1;
    }

    private static void add(Item item) {
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            QUEUE.add(new ItemStack(item));
        }
    }

    /**
     * One step of the queue. Called every client tick.
     *
     * <p>Waits while a screen is open - the dump screen closes itself when it has written
     * its file, so the next item only starts after the previous one finished.
     */
    public static void tick(Minecraft mc) {
        if (!autoStarted && Boolean.getBoolean("veloce.dumpIconsAll")) {
            autoStarted = true;
            queueAll();
        }
        if (QUEUE.isEmpty() || mc.player == null || mc.level == null) {
            return;
        }
        if (mc.screen instanceof VeloceIconDumpScreen) {
            return;      // the current one is still working; it closes itself when done
        }
        // Deliberately does NOT wait for a free screen. It used to, and the dump then
        // never ran: the client keeps A screen open at some point on the way into a world,
        // and "wait until nothing is open" waits forever. The queue is only ever filled by
        // an explicit request, so replacing the screen here is the point.
        ItemStack next = QUEUE.poll();
        String name = BuiltInRegistries.ITEM.getKey(next.getItem()).getPath();
        LOG.info("[Veloce] icon dump: opening for {} ({} left)", name, QUEUE.size());
        mc.setScreen(new VeloceIconDumpScreen(next, OUTPUT.resolve(name + ".png")));
    }

    /** What the command prints, so a script can assert on it. */
    public static Component report() {
        return Component.literal("icon dump queued: " + QUEUE.size() + " item(s) -> " + OUTPUT);
    }
}
