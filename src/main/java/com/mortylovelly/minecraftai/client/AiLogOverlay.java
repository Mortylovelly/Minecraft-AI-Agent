package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class AiLogOverlay {
    private static boolean open;
    private static boolean collapsed;
    private static boolean dragging;
    private static boolean resizing;

    private static int x = -1;
    private static int y = -1;
    private static int width = 430;
    private static int height = 310;
    private static int dragMouseX;
    private static int dragMouseY;
    private static int dragStartX;
    private static int dragStartY;
    private static int resizeStartWidth;
    private static int resizeStartHeight;

    private static int filter = AiLogClient.FILTER_ALL;
    private static int scrollOffset;
    private static long cachedRevision = -1L;
    private static int cachedWidth = -1;
    private static int cachedFilter = -1;
    private static final List<DisplayLine> CACHED_LINES = new ArrayList<>();

    private AiLogOverlay() {}

    private record DisplayLine(String text, int color) {}

    public static boolean isOpen() {
        return open;
    }

    public static void toggle() {
        open = !open;
        dragging = false;
        resizing = false;
        if (open) {
            collapsed = false;
            scrollOffset = 0;
            cachedRevision = -1L;
        }
    }

    public static void hide() {
        open = false;
        dragging = false;
        resizing = false;
    }

    public static void render(DrawContext context, TextRenderer renderer, int screenWidth, int screenHeight) {
        if (!open) return;
        ensureBounds(screenWidth, screenHeight);

        int panelHeight = collapsed ? 28 : height;
        int right = x + width;
        int bottom = y + panelHeight;

        context.fill(x, y, right, bottom, 0xF0181D25);
        context.fill(x, y, right, y + 1, 0xFF75849A);
        context.fill(x, y, x + 1, bottom, 0xFF566273);
        context.fill(right - 1, y, right, bottom, 0xFF566273);
        context.fill(x, bottom - 1, right, bottom, 0xFF566273);
        context.fill(x + 1, y + 25, right - 1, y + 26, 0xFF343D49);

        context.drawTextWithShadow(renderer, Text.literal("SERVER LOGS"), x + 9, y + 8, 0xFFFFFFFF);
        String status = AiLogClient.getConnectionStatus();
        int statusColor = AiLogClient.isConnected() ? 0xFF80E09A : 0xFFFFD36A;
        int statusX = x + 112;
        int statusMax = width - 166;
        if (statusMax > 20) {
            String clipped = renderer.trimToWidth(status, statusMax);
            context.drawTextWithShadow(renderer, Text.literal(clipped), statusX, y + 8, statusColor);
        }

        int collapseX = right - 43;
        int closeX = right - 23;
        drawHeaderButton(context, renderer, collapseX, y + 4, 16, collapsed ? "+" : "−");
        drawHeaderButton(context, renderer, closeX, y + 4, 16, "×");

        if (collapsed) return;

        updateCache(renderer, width - 18);
        int tabY = bottom - 22;
        int statsY = tabY - 22;
        int contentTop = y + 31;
        int contentBottom = statsY - 4;
        int lineHeight = 11;
        int visibleLines = Math.max(1, (contentBottom - contentTop) / lineHeight);

        int end = Math.max(0, CACHED_LINES.size() - scrollOffset);
        int start = Math.max(0, end - visibleLines);

        context.enableScissor(x + 5, contentTop - 1, right - 5, contentBottom + 1);
        int lineY = contentTop;
        if (CACHED_LINES.isEmpty()) {
            String empty = status.isBlank() ? "Ожидание логов..." : status;
            context.drawTextWithShadow(renderer, Text.literal(renderer.trimToWidth(empty, width - 20)), x + 9, lineY, 0xFFBEC6D0);
        } else {
            for (int i = start; i < end; i++) {
                DisplayLine line = CACHED_LINES.get(i);
                context.drawTextWithShadow(renderer, Text.literal(line.text()), x + 9, lineY, line.color());
                lineY += lineHeight;
            }
        }
        context.disableScissor();

        drawStats(context, renderer, statsY, width);
        drawTabs(context, renderer, tabY, width);
        drawScrollbar(context, contentTop, contentBottom, visibleLines, CACHED_LINES.size(), right - 5);

        // Diagonal resize grip. It remains inside the panel, so it cannot steal clicks from the screen behind it.
        int gx = right - 12;
        int gy = bottom - 12;
        context.fill(gx + 5, gy + 8, gx + 9, gy + 9, 0xFF9BA8B8);
        context.fill(gx + 7, gy + 5, gx + 9, gy + 6, 0xFF9BA8B8);
        context.fill(gx + 8, gy + 2, gx + 9, gy + 3, 0xFF9BA8B8);
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button, int screenWidth, int screenHeight) {
        if (!open || button != 0) return false;
        ensureBounds(screenWidth, screenHeight);

        int effectiveHeight = collapsed ? 28 : height;
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + effectiveHeight) return false;

        int right = x + width;
        if (mouseY <= y + 25) {
            if (mouseX >= right - 25) {
                hide();
                return true;
            }
            if (mouseX >= right - 46 && mouseX < right - 25) {
                collapsed = !collapsed;
                dragging = false;
                resizing = false;
                return true;
            }
            dragging = true;
            resizing = false;
            dragMouseX = (int) mouseX;
            dragMouseY = (int) mouseY;
            dragStartX = x;
            dragStartY = y;
            return true;
        }

        if (collapsed) return true;

        int tabY = y + height - 22;
        if (mouseY >= tabY && mouseY <= y + height - 3) {
            int tabWidth = (width - 16) / 3;
            int selected = Math.max(0, Math.min(2, ((int) mouseX - (x + 8)) / Math.max(1, tabWidth)));
            if (selected != filter) {
                filter = selected;
                scrollOffset = 0;
                cachedRevision = -1L;
            }
            return true;
        }

        if (mouseX >= right - 16 && mouseY >= y + height - 16) {
            resizing = true;
            dragging = false;
            dragMouseX = (int) mouseX;
            dragMouseY = (int) mouseY;
            resizeStartWidth = width;
            resizeStartHeight = height;
            return true;
        }

        return true;
    }

    public static boolean mouseDragged(double mouseX, double mouseY, int button, int screenWidth, int screenHeight) {
        if (!open || button != 0) return false;
        if (dragging) {
            x = dragStartX + (int) mouseX - dragMouseX;
            y = dragStartY + (int) mouseY - dragMouseY;
            ensureBounds(screenWidth, screenHeight);
            return true;
        }
        if (resizing) {
            width = resizeStartWidth + (int) mouseX - dragMouseX;
            height = resizeStartHeight + (int) mouseY - dragMouseY;
            ensureBounds(screenWidth, screenHeight);
            cachedWidth = -1;
            return true;
        }
        return false;
    }

    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0 || (!dragging && !resizing)) return false;
        dragging = false;
        resizing = false;
        return true;
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double amount, int screenWidth, int screenHeight) {
        if (!open || collapsed) return false;
        ensureBounds(screenWidth, screenHeight);
        int contentBottom = y + height - 51;
        if (mouseX < x || mouseX > x + width || mouseY < y + 27 || mouseY > contentBottom) return false;
        updateCache(MinecraftClient.getInstance().textRenderer, width - 18);
        scrollOffset += amount > 0 ? 3 : -3;
        int visible = Math.max(1, (contentBottom - (y + 31)) / 11);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, CACHED_LINES.size() - visible)));
        return true;
    }

    private static void ensureBounds(int screenWidth, int screenHeight) {
        int maxWidth = Math.max(260, Math.min(900, screenWidth - 12));
        int maxHeight = Math.max(150, Math.min(720, screenHeight - 12));
        width = Math.max(Math.min(320, maxWidth), Math.min(width, maxWidth));
        height = Math.max(Math.min(190, maxHeight), Math.min(height, maxHeight));
        if (x < 0) x = Math.max(6, screenWidth - width - 22);
        if (y < 0) y = Math.max(6, Math.min(88, screenHeight - height - 20));
        x = Math.max(4, Math.min(x, Math.max(4, screenWidth - width - 4)));
        y = Math.max(4, Math.min(y, Math.max(4, screenHeight - (collapsed ? 28 : height) - 4)));
    }

    private static void updateCache(TextRenderer renderer, int maxWidth) {
        long revision = AiLogClient.getRevision();
        if (cachedRevision == revision && cachedWidth == maxWidth && cachedFilter == filter) return;

        int oldLineCount = CACHED_LINES.size();
        boolean wasScrolled = scrollOffset > 0;
        CACHED_LINES.clear();
        List<AiLogClient.LogLine> lines = AiLogClient.snapshot(filter);
        int safeWidth = Math.max(90, maxWidth);

        for (AiLogClient.LogLine entry : lines) {
            String prefix = "[" + entry.time() + "] [" + entry.level() + "] [" + entry.logger() + "] ";
            String[] messageLines = entry.message().replace('\r', '\n').split("\n", -1);
            for (int index = 0; index < messageLines.length; index++) {
                String text = (index == 0 ? prefix : "    ") + messageLines[index];
                appendWrapped(renderer, text, safeWidth, entry.color());
                if (CACHED_LINES.size() >= 10000) break;
            }
            if (CACHED_LINES.size() >= 10000) break;
        }

        if (wasScrolled) scrollOffset += CACHED_LINES.size() - oldLineCount;
        cachedRevision = revision;
        cachedWidth = maxWidth;
        cachedFilter = filter;

        int contentBottom = y + height - 51;
        int visible = Math.max(1, (contentBottom - (y + 31)) / 11);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, CACHED_LINES.size() - visible)));
    }

    private static void appendWrapped(TextRenderer renderer, String raw, int maxWidth, int color) {
        String remaining = raw;
        if (remaining.isEmpty()) {
            CACHED_LINES.add(new DisplayLine("", color));
            return;
        }
        while (!remaining.isEmpty()) {
            String part = renderer.trimToWidth(remaining, maxWidth);
            if (part.isEmpty()) part = remaining.substring(0, 1);
            CACHED_LINES.add(new DisplayLine(part, color));
            if (part.length() >= remaining.length()) break;
            remaining = remaining.substring(part.length()).stripLeading();
            if (CACHED_LINES.size() >= 10000) break;
        }
    }

    private static void drawStats(DrawContext context, TextRenderer renderer, int statsY, int panelWidth) {
        int gap = 6;
        int cardWidth = (panelWidth - 16 - gap * 2) / 3;
        drawStat(context, renderer, 0, statsY, cardWidth, Items.EMERALD, AiLogClient.getTotalCount(), 0xFF81DEA0);
        drawStat(context, renderer, 1, statsY, cardWidth, Items.GLOWSTONE_DUST, AiLogClient.getWarningCount(), 0xFFFFD36A);
        drawStat(context, renderer, 2, statsY, cardWidth, Items.REDSTONE, AiLogClient.getErrorCount(), 0xFFFF6868);
    }

    private static void drawStat(DrawContext context, TextRenderer renderer, int index, int statsY,
                                 int cardWidth, net.minecraft.item.Item item, long count, int color) {
        int cardX = x + 8 + index * (cardWidth + 6);
        context.fill(cardX, statsY, cardX + cardWidth, statsY + 19, 0xFF252E39);
        context.fill(cardX, statsY, cardX + cardWidth, statsY + 1, 0xFF465363);
        context.drawItem(new ItemStack(item), cardX + 3, statsY + 2);
        String value = formatCount(count);
        context.drawTextWithShadow(renderer, Text.literal(value), cardX + 23, statsY + 6, color);
    }

    private static void drawTabs(DrawContext context, TextRenderer renderer, int tabY, int panelWidth) {
        String[] labels = {"Logs", "Custom logs", "Errors"};
        int tabWidth = (panelWidth - 16) / 3;
        for (int i = 0; i < labels.length; i++) {
            int tabX = x + 8 + i * tabWidth;
            boolean active = filter == i;
            int background = active ? 0xFF46586B : 0xFF252E39;
            context.fill(tabX, tabY, tabX + tabWidth - 3, tabY + 18, background);
            context.fill(tabX, tabY, tabX + tabWidth - 3, tabY + 1, active ? 0xFF8FB5D9 : 0xFF465363);
            int color = active ? 0xFFFFFFFF : 0xFFB5C0CC;
            int textWidth = renderer.getWidth(labels[i]);
            context.drawTextWithShadow(renderer, Text.literal(labels[i]),
                    tabX + Math.max(4, (tabWidth - 3 - textWidth) / 2), tabY + 5, color);
        }
    }

    private static void drawScrollbar(DrawContext context, int top, int bottom, int visible,
                                      int total, int barX) {
        if (total <= visible || bottom <= top) return;
        int trackHeight = bottom - top;
        int thumbHeight = Math.max(12, trackHeight * visible / Math.max(1, total));
        int maxOffset = Math.max(1, total - visible);
        int thumbY = top + (trackHeight - thumbHeight) * scrollOffset / maxOffset;
        context.fill(barX, top, barX + 2, bottom, 0xFF303A46);
        context.fill(barX, thumbY, barX + 2, thumbY + thumbHeight, 0xFF91A1B4);
    }

    private static void drawHeaderButton(DrawContext context, TextRenderer renderer, int buttonX,
                                         int buttonY, int buttonWidth, String label) {
        context.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 16, 0xFF2B3541);
        int color = label.equals("×") ? 0xFFFF7777 : 0xFFE0E7EF;
        int textWidth = renderer.getWidth(label);
        context.drawTextWithShadow(renderer, Text.literal(label),
                buttonX + (buttonWidth - textWidth) / 2, buttonY + 4, color);
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000L) return String.format(java.util.Locale.ROOT, "%.1fm", count / 1_000_000.0);
        if (count >= 10_000L) return String.format(java.util.Locale.ROOT, "%.1fk", count / 1_000.0);
        return Long.toString(count);
    }
}
