package lagfinder.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import lagfinder.client.gui.GuiLagFinder;

public final class KeyHandler {

    public static final KeyMapping OPEN = new KeyMapping(
        "key.lagfinder.open",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_L,
        "key.categories.lagfinder");

    public static final KeyMapping WORLD_MS = new KeyMapping(
        "key.lagfinder.world_ms",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_KP_0,
        "key.categories.lagfinder");

    private KeyHandler() {}

    static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
        event.register(WORLD_MS);
    }

    public static String keyName() {
        return OPEN.getTranslatedKeyMessage()
            .getString();
    }

    /** Whether the key is the one opening the window (to close it again with the same key). */
    public static boolean isOpenKey(int keyCode) {
        return OPEN.matches(keyCode, 0);
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(GuiLagFinder.toOpen());
            }
        }
        while (WORLD_MS.consumeClick()) {
            if (mc.screen == null) {
                WorldMsOverlay.INSTANCE.toggle();
            }
        }
    }
}
