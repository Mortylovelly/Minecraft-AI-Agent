package com.mortylovelly.minecraftai.client;

import net.fabricmc.loader.api.FabricLoader;

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

public final class AiLogFile {
    private static final Object LOCK = new Object();
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Path LOG_DIRECTORY = FabricLoader.getInstance().getGameDir()
            .resolve("logs")
            .resolve("minecraft-ai-agent");
    private static final Path CURRENT_LOG = LOG_DIRECTORY.resolve("latest.log");
    private static final Path PREVIOUS_LOG = LOG_DIRECTORY.resolve("latest.log.1");
    private static final Path OLDEST_LOG = LOG_DIRECTORY.resolve("latest.log.2");
    private static final Path LEGACY_LOG = FabricLoader.getInstance().getConfigDir()
            .resolve("minecraft_ai_agent.log");
    private static final Path LEGACY_OLD_LOG = FabricLoader.getInstance().getConfigDir()
            .resolve("minecraft_ai_agent.log.old");
    private static final Path LEGACY_IMPORT_MARKER = LOG_DIRECTORY.resolve(".legacy-imported");

    private AiLogFile() {}

    public static void initialize() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                importLegacyLogs();
            } catch (IOException | SecurityException exception) {
                System.err.println("[Minecraft AI Agent] Could not initialize log archive: " + exception.getMessage());
            }
        }
    }

    public static String redactSecrets(String message) {
        if (message == null || message.isEmpty()) return "";
        return message
                .replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret|password)\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}\\b", "[REDACTED_API_KEY]")
                .replaceAll("\\bAIza[0-9A-Za-z_-]{20,}\\b", "[REDACTED_API_KEY]");
    }

    public static void appendBatch(List<AiLogClient.LogLine> entries) {
        if (entries == null || entries.isEmpty()) return;
        StringBuilder batch = new StringBuilder();
        for (AiLogClient.LogLine entry : entries) {
            if (entry == null) continue;
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp()).atZone(ZoneId.systemDefault()));
            String logger = entry.logger().replace('\r', ' ').replace('\n', ' ');
            String[] messageLines = redactSecrets(entry.message()).replace("\\r\\n", "\\n")
                    .replace('\r', '\n').split("\\n", -1);
            for (int index = 0; index < messageLines.length; index++) {
                batch.append('[').append(time).append("] [")
                        .append(entry.level()).append("] [")
                        .append(logger).append("] ");
                if (entry.custom()) batch.append("[CUSTOM] ");
                if (index > 0) batch.append("    ");
                batch.append(messageLines[index]).append(System.lineSeparator());
            }
        }
        if (batch.length() == 0) return;
        byte[] bytes = batch.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                importLegacyLogs();
                rotateIfNeeded(bytes.length);
                Files.write(CURRENT_LOG, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | SecurityException exception) {
                System.err.println("[Minecraft AI Agent] Could not append log archive: " + exception.getMessage());
            }
        }
    }

    public static String readAll() {
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                importLegacyLogs();
                StringBuilder all = new StringBuilder();
                Path[] files = {OLDEST_LOG, PREVIOUS_LOG, CURRENT_LOG};
                for (Path file : files) {
                    if (!Files.exists(file) || Files.size(file) == 0L) continue;
                    if (all.length() > 0) all.append(System.lineSeparator());
                    all.append("========== ").append(file.getFileName()).append(" ==========")
                            .append(System.lineSeparator());
                    all.append(Files.readString(file, StandardCharsets.UTF_8));
                }
                return all.toString();
            } catch (IOException | SecurityException exception) {
                System.err.println("[Minecraft AI Agent] Could not read log archive: " + exception.getMessage());
                return "";
            }
        }
    }

    private static void importLegacyLogs() throws IOException {
        if (Files.exists(LEGACY_IMPORT_MARKER)) return;
        StringBuilder legacy = new StringBuilder();
        Path[] oldFiles = {LEGACY_OLD_LOG, LEGACY_LOG};
        for (Path file : oldFiles) {
            if (!Files.exists(file) || Files.size(file) == 0L) continue;
            legacy.append("========== imported from ")
                    .append(file.getFileName()).append(" ==========")
                    .append(System.lineSeparator());
            legacy.append(redactSecrets(Files.readString(file, StandardCharsets.UTF_8)));
            if (!legacy.toString().endsWith(System.lineSeparator())) legacy.append(System.lineSeparator());
        }
        if (legacy.length() > 0) {
            byte[] bytes = legacy.toString().getBytes(StandardCharsets.UTF_8);
            rotateIfNeeded(bytes.length);
            Files.write(CURRENT_LOG, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(LEGACY_IMPORT_MARKER, "Legacy Minecraft AI Agent logs imported once.",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void rotateIfNeeded(long incomingBytes) throws IOException {
        if (!Files.exists(CURRENT_LOG) || Files.size(CURRENT_LOG) + incomingBytes <= MAX_FILE_BYTES) return;
        Files.deleteIfExists(OLDEST_LOG);
        if (Files.exists(PREVIOUS_LOG)) {
            Files.move(PREVIOUS_LOG, OLDEST_LOG, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(CURRENT_LOG, PREVIOUS_LOG, StandardCopyOption.REPLACE_EXISTING);
    }
}
