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
    /** Compatibility filter for callers that still explicitly request custom-only entries. */
    public static final int FILTER_CUSTOM = 1;
    public static final int FILTER_ERRORS = 2;
    public static final int FILTER_WARNINGS = 3;

    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<LogLine> NORMAL_LINES = new ArrayDeque<>(MAX_LINES);
    private static final ArrayDeque<LogLine> CUSTOM_LINES = new ArrayDeque<>(MAX_LINES);
    private static final LinkedHashSet<Long> REMOTE_SEQUENCES = new LinkedHashSet<>();
    private static final int MAX_REMOTE_SEQUENCE_CACHE = 8192;

    private static long normalTotalCount;
    private static long normalWarningCount;
    private static long normalErrorCount;
    private static long customTotalCount;
    private static long customWarningCount;
    private static long customErrorCount;
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
        NORMAL_LINES.clear();
        REMOTE_SEQUENCES.clear();
        normalTotalCount = 0;
        normalWarningCount = 0;
        normalErrorCount = 0;
        revision++;
        connected = false;
        connectionStatus = "Поток логов выключен. Открой Logs HUD для просмотра.";
    }

    public static synchronized void onDisconnect() {
        NORMAL_LINES.clear();
        REMOTE_SEQUENCES.clear();
        normalTotalCount = 0;
        normalWarningCount = 0;
        normalErrorCount = 0;
        revision++;
        connected = false;
        connectionStatus = "Не подключено к миру";
    }

    public static synchronized void setConnectionStatus(String status, boolean active) {
        connectionStatus = status == null ? "" : status;
        connected = active;
        revision++;
    }

    /** Invalidates the HUD cache when the selected log source changes. */
    public static synchronized void sourceChanged() {
        revision++;
    }

    public static synchronized String getConnectionStatus() {
        return connectionStatus;
    }

    public static synchronized boolean isConnected() {
        return connected;
    }

    private static boolean customSourceSelected() {
        return AiClientConfig.isCustomLogsEnabled();
    }

    public static synchronized long getTotalCount() {
        return customSourceSelected() ? customTotalCount : normalTotalCount;
    }

    public static synchronized long getWarningCount() {
        return customSourceSelected() ? customWarningCount : normalWarningCount;
    }

    public static synchronized long getErrorCount() {
        return customSourceSelected() ? customErrorCount : normalErrorCount;
    }

    public static synchronized long getRevision() {
        return revision;
    }

    /**
     * The visible WARN/ERROR filters are applied to the active source. Normal and
     * custom buffers are kept separately so changing the settings switch never
     * mixes their history.
     */
    public static synchronized List<LogLine> snapshot(int filter) {
        boolean forceCustom = filter == FILTER_CUSTOM;
        List<LogLine> source = (forceCustom || customSourceSelected()) ? CUSTOM_LINES : NORMAL_LINES;
        int effectiveFilter = forceCustom ? FILTER_ALL : filter;

        List<LogLine> result = new ArrayList<>();
        for (LogLine line : source) {
            if (effectiveFilter == FILTER_ERRORS && !line.isError()) continue;
            if (effectiveFilter == FILTER_WARNINGS && !line.isWarning()) continue;
            result.add(line);
        }
        return result;
    }

    public static synchronized void acceptRemoteBatch(String rawJson) {
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            JsonArray entries = root.getAsJsonArray("lines");
            if (entries == null) return;

            List<LogLine> normalArchive = new ArrayList<>();
            List<LogLine> customArchive = new ArrayList<>();
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
                LogLine line = append(timestamp, level, logger, message, custom);
                normalArchive.add(line);
                if (custom) customArchive.add(line);
            }

            // The regular archive always receives the complete server stream.
            AiLogFile.appendBatch(normalArchive);
            // The separate custom archive receives only explicitly tagged project logs.
            AiCustomLogFile.appendBatch(customArchive);
        } catch (RuntimeException exception) {
            AiAgentLog.warn("Не удалось разобрать пакет серверных логов: " + exception.getMessage());
        }
    }

    public static synchronized void addLocal(String level, String logger, String message) {
        LogLine line = append(System.currentTimeMillis(), level, logger, message, true);
        // Preserve the existing regular archive while also writing the dedicated custom archive.
        AiLogFile.appendBatch(List.of(line));
        AiCustomLogFile.appendBatch(List.of(line));
    }

    /**
     * Exports the newest physical lines from the currently selected source and tab.
     * The other source's archive is never included.
     */
    public static synchronized String exportFilteredLogs(int filter, int requestedMaxLines) {
        int maxLines = Math.max(1, Math.min(MAX_LINES, requestedMaxLines));
        ArrayDeque<String> tail = new ArrayDeque<>(maxLines);

        for (LogLine line : snapshot(filter)) {
            String[] messageLines = line.message().replace('\r', '\n').split("\n", -1);
            for (int index = 0; index < messageLines.length; index++) {
                String prefix = index == 0
                        ? "[" + line.time() + "] [" + line.level() + "] [" + line.logger() + "] "
                        : "    ";
                tail.addLast(prefix + messageLines[index]);
                while (tail.size() > maxLines) tail.removeFirst();
            }
        }
        return String.join(System.lineSeparator(), tail);
    }

    private static LogLine append(long timestamp, String level, String logger, String message, boolean custom) {
        String normalizedLevel = normalizeLevel(level);
        String safeMessage = AiLogFile.redactSecrets(message == null ? "" : message);
        if (safeMessage.length() > 1800) safeMessage = safeMessage.substring(0, 1800) + " …";
        String safeLogger = logger == null ? "Minecraft" : logger;
        if (safeLogger.length() > 96) safeLogger = safeLogger.substring(0, 96);

        String time = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
        LogLine line = new LogLine(timestamp, time, normalizedLevel, safeLogger, safeMessage, custom);

        NORMAL_LINES.addLast(line);
        while (NORMAL_LINES.size() > MAX_LINES) NORMAL_LINES.removeFirst();
        normalTotalCount += physicalLines(safeMessage);
        if (line.isWarning()) normalWarningCount += physicalLines(safeMessage);
        if (line.isError()) normalErrorCount += physicalLines(safeMessage);

        if (custom) {
            CUSTOM_LINES.addLast(line);
            while (CUSTOM_LINES.size() > MAX_LINES) CUSTOM_LINES.removeFirst();
            customTotalCount += physicalLines(safeMessage);
            if (line.isWarning()) customWarningCount += physicalLines(safeMessage);
            if (line.isError()) customErrorCount += physicalLines(safeMessage);
        }

        revision++;
        return line;
    }

    private static long physicalLines(String message) {
        long count = 1L;
        for (int index = 0; index < message.length(); index++) {
            if (message.charAt(index) == '\n') count++;
        }
        return count;
    }

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) return "INFO";
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("FATAL") || normalized.contains("CRITICAL")) return "FATAL";
        if (normalized.contains("ERROR") || normalized.equals("ERR") || normalized.equals("SEVERE")) return "ERROR";
        if (normalized.contains("WARN")) return "WARN";
        if (normalized.contains("DEBUG")) return "DEBUG";
        if (normalized.contains("TRACE")) return "TRACE";
        if (normalized.contains("INFO")) return "INFO";
        return normalized;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : fallback;
    }
}
