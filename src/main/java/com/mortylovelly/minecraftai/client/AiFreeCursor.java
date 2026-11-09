package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Owns the optional in-game free-cursor mode.
 *
 * The state survives temporary Screen changes (for example, opening the pause menu).
 * The active Screen is allowed to manage its own cursor; when the player returns to
 * the world, the free cursor is unlocked again.
 */
public final class AiFreeCursor {
    private static boolean enabled;

    private AiFreeCursor() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(MinecraftClient client) {
        if (client.player == null || client.world == null || client.currentScreen != null) {
            return;
        }

        enabled = !enabled;
        if (enabled) {
            client.mouse.unlockCursor();
            client.player.sendMessage(Text.translatable("message.minecraft_ai_agent.free_cursor_enabled"), true);
        } else {
            client.mouse.lockCursor();
            client.player.sendMessage(Text.translatable("message.minecraft_ai_agent.free_cursor_disabled"), true);
        }
        AiUiSound.playClick();
    }

    public static void tick(MinecraftClient client) {
        if (!enabled) {
            return;
        }

        if (client.player == null || client.world == null) {
            enabled = false;
            return;
        }

        // Do not fight vanilla screens: pause/options/inventory screens own their mouse.
        // When the screen closes, Minecraft may grab the cursor again; restore free mode.
        if (client.currentScreen == null) {
            long window = client.getWindow().getHandle();
            if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_DISABLED) {
                client.mouse.unlockCursor();
            }
        }
    }

    public static void reset() {
        enabled = false;
    }
}
