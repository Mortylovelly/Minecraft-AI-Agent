package com.mortylovelly.minecraftai.mixin.client;

import com.mortylovelly.minecraftai.client.AiLogOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.DoubleBuffer;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void minecraftAiAgent$onMouseButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !AiLogOverlay.isOpen()) {
            return;
        }

        // Always release a captured HUD interaction, even if the cursor mode changed mid-drag.
        if (action == GLFW.GLFW_RELEASE) {
            if (AiLogOverlay.mouseReleased(0.0, 0.0, button)) {
                ci.cancel();
            }
            return;
        }

        if (action != GLFW.GLFW_PRESS
                || client.currentScreen != null
                || client.player == null
                || client.world == null
                || !isCursorAvailable(window)) {
            return;
        }

        Window w = client.getWindow();
        double[] cursor = getRawCursorPosition(window);
        double mouseX = scaledX(cursor[0], w);
        double mouseY = scaledY(cursor[1], w);

        // While the HUD is active, do not let a click pass through to attack/use the world.
        AiLogOverlay.mouseClicked(mouseX, mouseY, button, w.getScaledWidth(), w.getScaledHeight());
        ci.cancel();
    }

    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void minecraftAiAgent$onCursorPos(long window, double cursorX, double cursorY, CallbackInfo ci) {
        if (client.currentScreen != null
                || client.player == null
                || client.world == null
                || !AiLogOverlay.isOpen()
                || !isCursorAvailable(window)) {
            return;
        }

        Window w = client.getWindow();
        AiLogOverlay.mouseDragged(
                scaledX(cursorX, w),
                scaledY(cursorY, w),
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                w.getScaledWidth(),
                w.getScaledHeight()
        );

        // The external cursor toggle owns the pointer. Do not feed its movement into camera-look.
        ci.cancel();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void minecraftAiAgent$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (client.currentScreen != null
                || client.player == null
                || client.world == null
                || !AiLogOverlay.isOpen()
                || !isCursorAvailable(window)) {
            return;
        }

        Window w = client.getWindow();
        double[] cursor = getRawCursorPosition(window);
        AiLogOverlay.mouseScrolled(
                scaledX(cursor[0], w),
                scaledY(cursor[1], w),
                vertical,
                w.getScaledWidth(),
                w.getScaledHeight()
        );

        // Do not switch the selected hotbar slot while the pointer is being used on the HUD.
        ci.cancel();
    }

    private static boolean isCursorAvailable(long window) {
        return GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_DISABLED;
    }

    private static double[] getRawCursorPosition(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer cursorX = stack.mallocDouble(1);
            DoubleBuffer cursorY = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(window, cursorX, cursorY);
            return new double[]{cursorX.get(0), cursorY.get(0)};
        }
    }

    private static double scaledX(double rawX, Window window) {
        return rawX * window.getScaledWidth() / (double) window.getWidth();
    }

    private static double scaledY(double rawY, Window window) {
        return rawY * window.getScaledHeight() / (double) window.getHeight();
    }
}
