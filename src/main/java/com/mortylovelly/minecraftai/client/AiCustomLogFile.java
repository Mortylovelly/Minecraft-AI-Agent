package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class AiCustomLogFile {
    private static final Object LOCK = new Object();
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Path LOG_DIRECTORY = FabricLoader.getInstance().getGameDir()
            .resolve("logs").resolve("minecraft-ai-agent").resolve("custom");
    private static final Path CURRENT_LOG = LOG_DIRECTORY.resolve("latest.log");
    private static final Path PREVIOUS_LOG = LOG_DIRECTORY.resolve("latest.log.1");
    private static final Path OLDEST_LOG = LOG_DIRECTORY.resolve("latest.log.2");

    private AiCustomLogFile() {}

    public static void initialize() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
            } catch (IOException | SecurityException exception) {
                System.err.println("[Minecraft AI Agent] Could not initialize custom log archive: " + exception.getMessage());
            }
        }
    }

    public static boolean openDirectory() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                if (!Desktop.isDesktopSupported()) return false;
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.OPEN)) return false;
                desktop.open(LOG_DIRECTORY.toFile());
                return true;
            } catch (Exception exception) {
                System.err.println("[Minecraft AI Agent] Could not open custom log directory: " + exception.getMessage());
                return false;
            }
        }
    }

    public static void appendBatch(List<AiLogClient.LogLine> entries) {
        if (entries == null || entries.isEmpty()) return;
        StringBuilder batch = new StringBuilder();
        for (AiLogClient.LogLine entry : entries) {
            if (entry == null || !entry.custom()) continue;
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp()).atZone(ZoneId.systemDefault()));
            String logger = safeField(entry.logger());
            String[] messageLines = AiLogFile.redactSecrets(entry.message())
                    .replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (int index = 0; index < messageLines.length; index++) {
                batch.append('[').append(time).append("] [")
                        .append(entry.level()).append("] [").append(logger).append("] [CUSTOM] ");
                if (index > 0) batch.append("    ");
                batch.append(messageLines[index]).append(System.lineSeparator());
            }
        }
        appendText(batch.toString());
    }

    /** Writes compact custom telemetry or a local diagnostic event to the custom-only archive. */
    public static void appendRaw(String level, String logger, String message) {
        String time = TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()));
        String safeMessage = AiLogFile.redactSecrets(message == null ? "" : message)
                .replace('\r', ' ').replace('\n', ' ');
        String line = "[" + time + "] [" + normalizeLevel(level) + "] [" + safeField(logger)
                + "] [CUSTOM] " + safeMessage + System.lineSeparator();
        appendText(line);
    }

    private static void appendText(String text) {
        if (text == null || text.isEmpty()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                rotateIfNeeded(bytes.length);
                Files.write(CURRENT_LOG, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | SecurityException exception) {
                System.err.println("[Minecraft AI Agent] Could not append custom log archive: " + exception.getMessage());
            }
        }
    }

    public static JsonObject search(String query, String level, int requestedLimit, int requestedOffset) {
        String safeQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        String safeLevel = level == null ? "all" : level.trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "error", "warning", "info", "debug", "trace").contains(safeLevel)) safeLevel = "all";
        int limit = Math.max(1, Math.min(40, requestedLimit));
        int offset = Math.max(0, Math.min(100000, requestedOffset));
        JsonArray matches = new JsonArray();
        long matchedLines = 0L;
        long scannedLines = 0L;
        int scannedFiles = 0;

        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                Path[] files = {CURRENT_LOG, PREVIOUS_LOG, OLDEST_LOG};
                for (Path file : files) {
                    if (!Files.exists(file) || Files.size(file) == 0L) continue;
                    scannedFiles++;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    scannedLines += lines.size();
                    for (int index = lines.size() - 1; index >= 0; index--) {
                        String line = lines.get(index);
                        if (!matchesLevel(line, safeLevel)) continue;
                        if (!safeQuery.isEmpty() && !line.toLowerCase(Locale.ROOT).replace('ё', 'е').contains(safeQuery)) continue;
                        if (matchedLines >= offset && matches.size() < limit) {
                            matches.add(AiLogFile.redactSecrets(line));
                        }
                        matchedLines++;
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("source", "Minecraft AI Agent custom diagnostic archive");
                result.addProperty("order", "newest_first");
                result.addProperty("query", safeQuery);
                result.addProperty("level", safeLevel);
                result.addProperty("custom_only", true);
                result.addProperty("offset", offset);
                result.addProperty("retained_files_scanned", scannedFiles);
                result.addProperty("scanned_lines", scannedLines);
                result.addProperty("matched_lines", matchedLines);
                result.addProperty("returned_lines", matches.size());
                result.addProperty("more_available", matchedLines > (long) offset + matches.size());
                result.add("lines", matches);
                return result;
            } catch (IOException | SecurityException exception) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Не удалось прочитать архив кастомных логов: " + exception.getMessage());
                return result;
            }
        }
    }

    private static boolean matchesLevel(String line, String level) {
        return switch (level) {
            case "error" -> line.contains("[ERROR]") || line.contains("[FATAL]");
            case "warning" -> line.contains("[WARN]") || line.contains("[WARNING]");
            case "info" -> line.contains("[INFO]");
            case "debug" -> line.contains("[DEBUG]");
            case "trace" -> line.contains("[TRACE]");
            default -> true;
        };
    }

    private static void rotateIfNeeded(long incomingBytes) throws IOException {
        if (!Files.exists(CURRENT_LOG) || Files.size(CURRENT_LOG) + incomingBytes <= MAX_FILE_BYTES) return;
        Files.deleteIfExists(OLDEST_LOG);
        if (Files.exists(PREVIOUS_LOG)) {
            Files.move(PREVIOUS_LOG, OLDEST_LOG, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(CURRENT_LOG, PREVIOUS_LOG, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) return "INFO";
        String value = level.trim().toUpperCase(Locale.ROOT);
        if (value.contains("FATAL")) return "FATAL";
        if (value.contains("ERROR")) return "ERROR";
        if (value.contains("WARN")) return "WARN";
        if (value.contains("DEBUG")) return "DEBUG";
        if (value.contains("TRACE")) return "TRACE";
        return "INFO";
    }

    private static String safeField(String value) {
        if (value == null || value.isBlank()) return "Minecraft-AI-Agent";
        return value.replace('\r', '_').replace('\n', '_').replace(']', '_');
    }
}
