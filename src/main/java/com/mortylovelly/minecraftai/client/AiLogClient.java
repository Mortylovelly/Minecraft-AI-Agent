package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AiLogClient {
    public static final int FILTER_ALL = 0;
    public static final int FILTER_CUSTOM = 1;
    public static final int FILTER_ERRORS = 2;

    private static final int MAX_LINES = 5000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<LogLine> LINES = new ArrayDeque<>(MAX_LINES);

    private static long totalCount;
    private static long warningCount;
    private static long errorCount;
    private static long revision;
    private static String connectionStatus = "Подключение к серверным логам...";
    private static boolean connected;

    private AiLogClient() {}

    public record LogLine(long timestamp, String time, String level, String logger, String message, boolean custom) {
        public boolean isWarning() {
            return "WARN".equals(level) || "WARNING".equals(level);
        }

        public boolean isError() {
            return "ERROR".equals(level) || "FATAL".equals(level);
        }

        public int color() {
            if (isError()) return 0xFFFF6868;
            if (isWarning()) return 0xFFFFD36A;
            return 0xFF79D98C;
        }
    }

    public static synchronized void resetForWorldJoin() {
        LINES.clear();
        totalCount = 0;
        warningCount = 0;
        errorCount = 0;
        revision++;
        connected = false;
        connectionStatus = "Запрашиваю доступ к логам сервера...";
    }

    public static synchronized void onDisconnect() {
        LINES.clear();
        totalCount = 0;
        warningCount = 0;
        errorCount = 0;
        revision++;
        connected = false;
        connectionStatus = "Не подключено к миру";
    }

    public static synchronized void setConnectionStatus(String status, boolean active) {
        connectionStatus = status == null ? "" : status;
        connected = active;
        revision++;
    }

    public static synchronized String getConnectionStatus() {
        return connectionStatus;
    }

    public static synchronized boolean isConnected() {
        return connected;
    }

    public static synchronized long getTotalCount() {
        return totalCount;
    }

    public static synchronized long getWarningCount() {
        return warningCount;
    }

    public static synchronized long getErrorCount() {
        return errorCount;
    }

    public static synchronized long getRevision() {
        return revision;
    }

    public static synchronized List<LogLine> snapshot(int filter) {
        List<LogLine> result = new ArrayList<>();
        for (LogLine line : LINES) {
            if (filter == FILTER_CUSTOM && !line.custom()) continue;
            if (filter == FILTER_ERRORS && !line.isError()) continue;
            result.add(line);
        }
        return result;
    }

    public static synchronized void acceptRemoteBatch(String rawJson) {
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            JsonArray entries = root.getAsJsonArray("lines");
            if (entries == null) return;
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                long timestamp = item.has("timestamp") ? item.get("timestamp").getAsLong() : System.currentTimeMillis();
                String level = string(item, "level", "INFO").toUpperCase(Locale.ROOT);
                String logger = string(item, "logger", "Minecraft");
                String message = string(item, "message", "");
                boolean custom = item.has("custom") && item.get("custom").getAsBoolean();
                append(timestamp, level, logger, message, custom);
            }
        } catch (RuntimeException exception) {
            AiAgentLog.warn("Не удалось разобрать пакет серверных логов: " + exception.getMessage());
        }
    }

    public static synchronized void addLocal(String level, String logger, String message) {
        append(System.currentTimeMillis(), level, logger, message, true);
    }

    private static void append(long timestamp, String level, String logger, String message, boolean custom) {
        String normalizedLevel = level == null ? "INFO" : level.toUpperCase(Locale.ROOT);
        String safeMessage = message == null ? "" : message;
        if (safeMessage.length() > 1800) safeMessage = safeMessage.substring(0, 1800) + " …";
        String safeLogger = logger == null ? "Minecraft" : logger;
        if (safeLogger.length() > 96) safeLogger = safeLogger.substring(0, 96);

        String time = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
        LogLine line = new LogLine(timestamp, time, normalizedLevel, safeLogger, safeMessage, custom);
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();

        totalCount++;
        if (line.isWarning()) warningCount++;
        if (line.isError()) errorCount++;
        revision++;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : fallback;
    }
}
