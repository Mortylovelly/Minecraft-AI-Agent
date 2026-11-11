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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class AiLogClient {
    public static final int FILTER_ALL = 0;
    public static final int FILTER_CUSTOM = 1;
    public static final int FILTER_ERRORS = 2;

    private static final int MAX_LINES = 5000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<LogLine> LINES = new ArrayDeque<>(MAX_LINES);
    private static final LinkedHashSet<Long> REMOTE_SEQUENCES = new LinkedHashSet<>();
    private static final int MAX_REMOTE_SEQUENCE_CACHE = 8192;

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
            return switch (normalizeLevel(level)) {
                case "ERROR", "FATAL" -> 0xFFFF6666;
                case "WARN" -> 0xFFFFC857;
                case "DEBUG" -> 0xFF80BCEB;
                case "TRACE" -> 0xFF9AA4B0;
                case "INFO" -> 0xFF8DDC9B;
                default -> 0xFFD0D6DE;
            };
        }
    }

    public static synchronized void resetForWorldJoin() {
        LINES.clear();
        REMOTE_SEQUENCES.clear();
        totalCount = 0;
        warningCount = 0;
        errorCount = 0;
        revision++;
        connected = false;
        connectionStatus = "Поток логов выключен. Открой Logs HUD для просмотра.";
    }

    public static synchronized void onDisconnect() {
        LINES.clear();
        REMOTE_SEQUENCES.clear();
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
            List<LogLine> persistedLines = new ArrayList<>();
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                long sequence = item.has("sequence") ? item.get("sequence").getAsLong() : 0L;
                if (sequence > 0L) {
                    if (!REMOTE_SEQUENCES.add(sequence)) continue;
                    while (REMOTE_SEQUENCES.size() > MAX_REMOTE_SEQUENCE_CACHE) {
                        REMOTE_SEQUENCES.remove(REMOTE_SEQUENCES.iterator().next());
                    }
                }
                long timestamp = item.has("timestamp") ? item.get("timestamp").getAsLong() : System.currentTimeMillis();
                String level = string(item, "level", "INFO").toUpperCase(Locale.ROOT);
                String logger = string(item, "logger", "Minecraft");
                String message = string(item, "message", "");
                boolean custom = item.has("custom") && item.get("custom").getAsBoolean();
                persistedLines.add(append(timestamp, level, logger, message, custom));
            }
            AiLogFile.appendBatch(persistedLines);
        } catch (RuntimeException exception) {
            AiAgentLog.warn("Не удалось разобрать пакет серверных логов: " + exception.getMessage());
        }
    }

    public static synchronized void addLocal(String level, String logger, String message) {
        AiLogFile.appendBatch(List.of(append(System.currentTimeMillis(), level, logger, message, true)));
    }

    public static synchronized String exportAllLogs() {
        String savedLogs = AiLogFile.readAll();
        if (savedLogs != null && !savedLogs.isBlank()) return savedLogs;
        StringBuilder fallback = new StringBuilder();
        for (LogLine line : LINES) {
            fallback.append('[').append(line.time()).append("] [")
                    .append(line.level()).append("] [")
                    .append(line.logger()).append("] ")
                    .append(line.message()).append(System.lineSeparator());
        }
        return fallback.toString();
    }

    private static LogLine append(long timestamp, String level, String logger, String message, boolean custom) {
        String normalizedLevel = normalizeLevel(level);
        String safeMessage = AiLogFile.redactSecrets(message == null ? "" : message);
        if (safeMessage.length() > 1800) safeMessage = safeMessage.substring(0, 1800) + " …";
        String safeLogger = logger == null ? "Minecraft" : logger;
        if (safeLogger.length() > 96) safeLogger = safeLogger.substring(0, 96);

        String time = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
        LogLine line = new LogLine(timestamp, time, normalizedLevel, safeLogger, safeMessage, custom);
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();

        long physicalLines = 1L;
        for (int index = 0; index < safeMessage.length(); index++) {
            if (safeMessage.charAt(index) == '\n') physicalLines++;
        }
        totalCount += physicalLines;
        if (line.isWarning()) warningCount += physicalLines;
        if (line.isError()) errorCount += physicalLines;
        revision++;
        return line;
    }

    private static String normalizeLevel(String level) {
        if(level==null||level.isBlank())return "INFO";
        String normalized=level.trim().toUpperCase(Locale.ROOT);
        if(normalized.contains("FATAL")||normalized.contains("CRITICAL"))return "FATAL";
        if(normalized.contains("ERROR")||normalized.equals("ERR")||normalized.equals("SEVERE"))return "ERROR";
        if(normalized.contains("WARN"))return "WARN";
        if(normalized.contains("DEBUG"))return "DEBUG";
        if(normalized.contains("TRACE"))return "TRACE";
        if(normalized.contains("INFO"))return "INFO";
        return normalized;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : fallback;
    }
}
