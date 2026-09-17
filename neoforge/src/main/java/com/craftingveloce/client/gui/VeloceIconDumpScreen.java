package com.craftingveloce.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.nio.file.Path;

/**
 * Renders one item's icon to a PNG - exactly the icon the hotbar draws.
 *
 * <p><b>Why this exists.</b> The casing icons are being rebuilt as pictures instead of as
 * models composed at bake time. Composing models could not survive the machines whose
 * models are not plain cubes (Create's OBJ wheels came out as the missing-texture
 * checkerboard) and put the content at the wrong size, and there is no way to see either
 * of those before looking at the result. A rendered PNG is something a person can look at
 * and cut up in an image editor, which is what this produces.
 *
 * <p><b>Why not an offscreen framebuffer.</b> Rendering into an FBO and reading it back
 * with glReadPixels needs its own projection, its own clear and its own flip - about two
 * hundred lines of raw GL that fail in ways nobody can see. This draws on the normal
 * screen against a colour key, takes the frame the game has already presented, and makes
 * the key transparent. Same file, and a mistake is visible in the picture itself.
 *
 * <p><b>The item is drawn the way the hotbar draws it</b> - {@code GuiGraphics.renderItem},
 * which is the GUI display context. That is the icon the player is asking for.
 */
public final class VeloceIconDumpScreen extends Screen {

    /**
     * The colour key. Every pixel of it becomes transparent in the saved file.
     *
     * <p>Magenta, because nothing in a machine model is that colour; a colour a model
     * actually uses would punch holes in it.
     */
    private static final int KEY = 0xFFFF00FF;

    private final ItemStack stack;
    private final Path output;

    /**
     * Frames drawn so far, and whether the picture has been taken.
     *
     * <p><b>The grab happens INSIDE the render, right after the item is drawn.</b> It used
     * to happen in {@code tick()}, reading the frame the game had already presented - and
     * that produced the wrong icon in groups: identical files came out for crafting_table,
     * lectern and the empty casing; for all five vanilla machine contents; for all five
     * Create ones. The reason is that a client does not draw a frame every tick, so between
     * two ticks the presented frame can still be the one from several screens ago. Reading
     * the target we have just drawn into removes the question.
     */
    private int frames;
    private boolean grabbed;
    private int ticksAfterGrab = -1;

    public VeloceIconDumpScreen(ItemStack stack, Path output) {
        super(Component.literal("Veloce icon dump"));
        this.stack = stack;
        this.output = output;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, KEY);
        // The largest whole-number scale that still fits, so the icon is as big as the
        // window allows - the geometry is re-rendered at that size rather than upscaled.
        int side = Math.min(this.width, this.height);
        int scale = Math.max(1, (int) (side * 0.8F / 16.0F));
        graphics.pose().pushPose();
        graphics.pose().translate(scale * 8.0F, scale * 8.0F, 0.0F);
        graphics.pose().scale(scale, scale, scale);
        graphics.renderItem(this.stack, -8, -8);
        graphics.pose().popPose();

        if (++frames >= 2 && !grabbed) {
            grabbed = true;
            try {
                grab();
            } catch (Throwable t) {
                com.craftingveloce.CraftingVeloceMod.LOGGER.error(
                        "[Veloce] icon dump failed for {}", this.stack, t);
            }
        }
    }

    @Override
    public void tick() {
        // Closing waits for the next tick, because a screen must not be replaced from
        // inside its own render pass.
        if (!grabbed || ticksAfterGrab < 0 || ++ticksAfterGrab < 2) {
            return;
        }
        Minecraft.getInstance().setScreen(null);
    }

    /** Takes the presented frame, cuts the icon out of it and writes the PNG. */
    private void grab() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        if (shot == null) {
            com.craftingveloce.CraftingVeloceMod.LOGGER.error("[Veloce] icon dump: no frame");
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < shot.getHeight(); y++) {
            for (int x = 0; x < shot.getWidth(); x++) {
                if (!isKey(shot.getPixelRGBA(x, y))) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            // Nothing but the key was drawn: the item has no model at all. Saying so is the
            // whole point - a blank file would look like a bad export.
            com.craftingveloce.CraftingVeloceMod.LOGGER.error(
                    "[Veloce] icon dump: {} rendered NOTHING (no model?) - no file written",
                    this.stack);
            shot.close();
            return;
        }

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        NativeImage icon = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = shot.getPixelRGBA(minX + x, minY + y);
                icon.setPixelRGBA(x, y, isKey(pixel) ? 0 : pixel);
            }
        }
        shot.close();

        File file = this.output.toFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        icon.writeToFile(this.output);
        icon.close();

        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce] icon dumped: {} -> {} ({}x{} at {},{})",
                this.stack, this.output, w, h, minX, minY);
    }

    /** The colour key test - alpha ignored, because the screen fills it opaque. */
    private static boolean isKey(int pixel) {
        return (pixel & 0x00FFFFFF) == (KEY & 0x00FFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
