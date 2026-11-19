package com.mortylovelly.minecraftai.client;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Small client API for mods that draw HUD panels controlled by the AI Agent free cursor.
 * All coordinates are already converted to Minecraft's scaled-window coordinate space.
 *
 * Event arrays:
 * mouse button: [x, y, button, action]
 * cursor move:  [x, y]
 * mouse scroll: [x, y, horizontal, vertical]
 */
public final class AiCursorEvents {
    private static final CopyOnWriteArrayList<Consumer<double[]>> BUTTON_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Consumer<double[]>> MOVE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Consumer<double[]>> SCROLL_LISTENERS = new CopyOnWriteArrayList<>();

    private AiCursorEvents() {
    }

    public static void addMouseButtonListener(Consumer<double[]> listener) {
        if (listener != null && !BUTTON_LISTENERS.contains(listener)) BUTTON_LISTENERS.add(listener);
    }

    public static void addCursorMoveListener(Consumer<double[]> listener) {
        if (listener != null && !MOVE_LISTENERS.contains(listener)) MOVE_LISTENERS.add(listener);
    }

    public static void addMouseScrollListener(Consumer<double[]> listener) {
        if (listener != null && !SCROLL_LISTENERS.contains(listener)) SCROLL_LISTENERS.add(listener);
    }

    public static void dispatchMouseButton(double x, double y, int button, int action) {
        double[] event = {x, y, button, action};
        for (Consumer<double[]> listener : BUTTON_LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // A third-party overlay must not break Minecraft's mouse handler.
            }
        }
    }

    public static void dispatchCursorMove(double x, double y) {
        double[] event = {x, y};
        for (Consumer<double[]> listener : MOVE_LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // A third-party overlay must not break Minecraft's mouse handler.
            }
        }
    }

    public static void dispatchMouseScroll(double x, double y, double horizontal, double vertical) {
        double[] event = {x, y, horizontal, vertical};
        for (Consumer<double[]> listener : SCROLL_LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // A third-party overlay must not break Minecraft's mouse handler.
            }
        }
    }
}
