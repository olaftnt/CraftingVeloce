package com.craftingveloce.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class VeloceKeyMappings {
    private VeloceKeyMappings() {}

    public static final String KEY_CATEGORY_CRAFTINGVELOCE = "key.categories.craftingveloce";

    public static final KeyMapping OPEN_TABLET_KEY = new KeyMapping(
            "key.craftingveloce.open_tablet",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            KEY_CATEGORY_CRAFTINGVELOCE
    );
}
