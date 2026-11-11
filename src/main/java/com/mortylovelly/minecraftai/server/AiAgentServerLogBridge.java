package com.mortylovelly.minecraftai.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AiAgentServerLogBridge {
    public static final Identifier LOG_BATCH = new Identifier("minecraft_ai_agent", "log_batch");
    public static final Identifier LOG_STATUS = new Identifier("minecraft_ai_agent", "log_status");
    public static final Identifier LOG_SNAPSHOT_DONE = new Identifier("minecraft_ai_agent", "log_snapshot_done");

    private static final String APPENDER_NAME = "MinecraftAiAgentServerLogBridge";
    private static final int MAX_HISTORY_LINES = 4096;
    private static final int HUD_SNAPSHOT_LINES = 500;
    private static final int DIAGNOSTIC_SNAPSHOT_LINES = 300;
    private static final int BATCH_SIZE = 4;
    private static final int MAX_PACKET_CHARS = 24000;
    private static final int MAX_LOG_MESSAGE_CHARS = 1600;
    private static final int MAX_LOGGER_CHARS = 96;
    private static final int MAX_BATCHES_PER_TICK = 8;

    // Collection is independent from transport: the appender always updates this
    // bounded history; the subscriber map controls only live network delivery.
    private static final Object HISTORY_LOCK = new Object();
    private static final ArrayDeque<LogLine> HISTORY = new ArrayDeque<>();
    private static final Map<UUID, Long> SUBSCRIBERS = new ConcurrentHashMap<>();

    private static long nextSequence;
    private static volatile MinecraftServer server;
    private static volatile LoggerContext loggerContext;
    private static volatile LoggerConfig rootLoggerConfig;
    private static volatile AgentAppender appender;

    private AiAgentServerLogBridge() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(AiAgentServerLogBridge::attach);
        ServerLifecycleEvents.SERVER_STOPPED.register(AiAgentServerLogBridge::detach);
        ServerTickEvents.END_SERVER_TICK.register(AiAgentServerLogBridge::flush);
    }

    public static void subscribe(ServerPlayerEntity player) {
        if (!player.hasPermissionLevel(2)) {
            sendStatus(player, "Для просмотра серверных логов нужны права OP 2.", false);
            return;
        }

        synchronized (HISTORY_LOCK) {
            long latest = nextSequence;
            LogLine oldestLine = HISTORY.peekFirst();
            long oldest = oldestLine == null ? latest + 1L : oldestLine.sequence();
            long firstToSend = Math.max(oldest, latest - HUD_SNAPSHOT_LINES + 1L);
            SUBSCRIBERS.put(player.getUuid(), firstToSend - 1L);
        }
        sendStatus(player, "Поток серверных логов включён.", true);
    }

    public static void unsubscribe(UUID playerId) {
        SUBSCRIBERS.remove(playerId);
    }

    // One-time bounded transfer for an AI diagnostic request; does not subscribe.
    public static void sendRecentSnapshot(ServerPlayerEntity player, String requestId) {
        if (requestId == null || requestId.isBlank()) return;
        if (!player.hasPermissionLevel(2)) {
            sendSnapshotDone(player, requestId, false, 0, "Для чтения серверных логов нужны права OP 2.");
            return;
        }

        List<LogLine> snapshot = snapshotTail(DIAGNOSTIC_SNAPSHOT_LINES);
        int sent = 0;
        boolean success = true;
        for (int start = 0; start < snapshot.size(); start += BATCH_SIZE) {
            int end = Math.min(snapshot.size(), start + BATCH_SIZE);
            if (!sendBatch(player, snapshot.subList(start, end))) {
                success = false;
                break;
            }
            sent += end - start;
        }
        String message = success
                ? "Получено свежих серверных записей: " + sent + "."
                : "Передача снимка логов прервалась после " + sent + " записей.";
        sendSnapshotDone(player, requestId, success, sent, message);
    }

    public static void sendRecentSnapshotDenied(ServerPlayerEntity player, String requestId, String reason) {
        sendSnapshotDone(player, requestId, false, 0, reason);
    }

    private static void attach(MinecraftServer currentServer) {
        detach(currentServer);
        server = currentServer;
        synchronized (HISTORY_LOCK) {
            HISTORY.clear();
            nextSequence = 0L;
        }

        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            LoggerConfig root = configuration.getRootLogger();
            AgentAppender bridgeAppender = new AgentAppender();
            bridgeAppender.start();
            root.addAppender(bridgeAppender, Level.ALL, null);
            context.updateLoggers();

            loggerContext = context;
            rootLoggerConfig = root;
            appender = bridgeAppender;
        } catch (Throwable throwable) {
            System.err.println("[Minecraft AI Agent] Could not attach server log appender: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static void detach(MinecraftServer stoppingServer) {
        if (server != null && stoppingServer != null && server != stoppingServer) return;
        AgentAppender activeAppender = appender;
        LoggerContext context = loggerContext;
        LoggerConfig root = rootLoggerConfig;

        if (activeAppender != null && root != null && context != null) {
            try {
                root.removeAppender(APPENDER_NAME);
                context.updateLoggers();
                activeAppender.stop();
            } catch (Throwable throwable) {
                System.err.println("[Minecraft AI Agent] Could not detach server log appender: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }

        appender = null;
        loggerContext = null;
        rootLoggerConfig = null;
        server = null;
        SUBSCRIBERS.clear();
        synchronized (HISTORY_LOCK) {
            HISTORY.clear();
            nextSequence = 0L;
        }
    }

    private static void flush(MinecraftServer currentServer) {
        if (SUBSCRIBERS.isEmpty()) return;
        try {
            for (Map.Entry<UUID, Long> subscription : SUBSCRIBERS.entrySet()) {
                UUID playerId = subscription.getKey();
                long previousSequence = subscription.getValue();
                ServerPlayerEntity player = currentServer.getPlayerManager().getPlayer(playerId);
                if (player == null || player.isDisconnected() || !player.hasPermissionLevel(2)) {
                    SUBSCRIBERS.remove(playerId);
                    continue;
                }

                HistorySlice slice = historyAfter(previousSequence, BATCH_SIZE * MAX_BATCHES_PER_TICK);
                long sentThrough = slice.cursor();
                boolean failed = false;
                List<LogLine> pending = slice.lines();
                for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
                    int end = Math.min(pending.size(), start + BATCH_SIZE);
                    if (!sendBatch(player, pending.subList(start, end))) {
                        failed = true;
                        break;
                    }
                    sentThrough = pending.get(end - 1).sequence();
                }

                if (failed) {
                    SUBSCRIBERS.remove(playerId);
                    sendStatus(player, "Поток логов остановлен: ошибка передачи пакета.", false);
                } else {
                    SUBSCRIBERS.replace(playerId, previousSequence, sentThrough);
                }
            }
        } catch (Throwable throwable) {
            // Never allow optional diagnostic transport to escape END_SERVER_TICK.
            System.err.println("[Minecraft AI Agent] Log stream tick failed safely: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static HistorySlice historyAfter(long sequence, int maxLines) {
        synchronized (HISTORY_LOCK) {
            long cursor = sequence;
            LogLine oldestLine = HISTORY.peekFirst();
            if (oldestLine != null && oldestLine.sequence() > cursor + 1L) {
                cursor = oldestLine.sequence() - 1L;
            }

            List<LogLine> result = new ArrayList<>(Math.min(maxLines, HISTORY.size()));
            for (LogLine line : HISTORY) {
                if (line.sequence() <= cursor) continue;
                result.add(line);
                if (result.size() >= maxLines) break;
            }
            return new HistorySlice(cursor, result);
        }
    }

    private static List<LogLine> snapshotTail(int limit) {
        synchronized (HISTORY_LOCK) {
            List<LogLine> result = new ArrayList<>(Math.min(limit, HISTORY.size()));
            int skip = Math.max(0, HISTORY.size() - limit);
            int index = 0;
            for (LogLine line : HISTORY) {
                if (index++ < skip) continue;
                result.add(line);
            }
            return result;
        }
    }

    private static boolean sendBatch(ServerPlayerEntity player, List<LogLine> lines) {
        if (lines == null || lines.isEmpty()) return true;
        JsonArray entries = new JsonArray();
        for (LogLine line : lines) entries.add(line.toJson());
        JsonObject packetJson = new JsonObject();
        packetJson.add("lines", entries);
        String encodedBatch = packetJson.toString();

        while (encodedBatch.length() > MAX_PACKET_CHARS && entries.size() > 1) {
            entries.remove(entries.size() - 1);
            packetJson.add("lines", entries);
            encodedBatch = packetJson.toString();
        }
        if (encodedBatch.length() > MAX_PACKET_CHARS || entries.isEmpty()) {
            System.err.println("[Minecraft AI Agent] Dropped an oversized server-log batch.");
            return false;
        }

        try {
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeString(encodedBatch, MAX_PACKET_CHARS);
            ServerPlayNetworking.send(player, LOG_BATCH, buffer);
            return true;
        } catch (RuntimeException exception) {
            System.err.println("[Minecraft AI Agent] Could not send server-log batch: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return false;
        }
    }

    private static void sendStatus(ServerPlayerEntity player, String status, boolean active) {
        try {
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeString(status, 512);
            buffer.writeBoolean(active);
            ServerPlayNetworking.send(player, LOG_STATUS, buffer);
        } catch (RuntimeException exception) {
            System.err.println("[Minecraft AI Agent] Could not send log status: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static void sendSnapshotDone(ServerPlayerEntity player, String requestId,
                                         boolean success, int lineCount, String message) {
        try {
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeString(requestId, 64);
            buffer.writeBoolean(success);
            buffer.writeVarInt(lineCount);
            buffer.writeString(message == null ? "" : message, 512);
            ServerPlayNetworking.send(player, LOG_SNAPSHOT_DONE, buffer);
        } catch (RuntimeException exception) {
            System.err.println("[Minecraft AI Agent] Could not finish log snapshot response: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static void enqueue(LogEvent event) {
        if (event == null) return;
        try {
            String level = event.getLevel() == null ? "INFO" : event.getLevel().name();
            String logger = event.getLoggerName() == null ? "Minecraft" : event.getLoggerName();
            if (logger.length() > MAX_LOGGER_CHARS) logger = logger.substring(0, MAX_LOGGER_CHARS);
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();

            if (event.getThrown() != null) {
                StringWriter writer = new StringWriter();
                event.getThrown().printStackTrace(new PrintWriter(writer));
                message += "\n" + writer;
            }

            message = redactSecrets(message);
            if (message.length() > MAX_LOG_MESSAGE_CHARS) {
                message = message.substring(0, MAX_LOG_MESSAGE_CHARS) + " …[truncated]";
            }
            boolean custom = isProjectLog(logger, message);

            synchronized (HISTORY_LOCK) {
                HISTORY.addLast(new LogLine(++nextSequence, event.getTimeMillis(), level, logger, message, custom));
                while (HISTORY.size() > MAX_HISTORY_LINES) HISTORY.removeFirst();
            }
        } catch (Throwable throwable) {
            // Appender failures must never leak back into Log4j callers.
            System.err.println("[Minecraft AI Agent] Could not retain a log event safely: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static boolean isProjectLog(String logger, String message) {
        String normalizedLogger = logger.toLowerCase(java.util.Locale.ROOT);
        if (normalizedLogger.startsWith("com.mortylovelly.") || normalizedLogger.contains(".mortylovelly.")) return true;
        String normalizedMessage = message.toLowerCase(java.util.Locale.ROOT);
        return normalizedMessage.contains("[fix]")
                || normalizedMessage.contains("[minecraft ai agent]")
                || normalizedMessage.contains("[fauna]")
                || normalizedMessage.contains("[minecartplus]")
                || normalizedMessage.contains("[uniquerelics]")
                || normalizedMessage.contains("[unique-relics]")
                || normalizedMessage.contains("[skylimitless]")
                || normalizedMessage.contains("[seasonaldaycycle]")
                || normalizedMessage.contains("[vitalcraft]")
                || normalizedMessage.contains("[weatherplus]");
    }

    private static String redactSecrets(String message) {
        if (message == null || message.isEmpty()) return "";
        return message
                .replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:api[_ -]?key|access[_ -]?token|secret)\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}\\b", "[REDACTED_API_KEY]")
                .replaceAll("\\bAIza[0-9A-Za-z_-]{20,}\\b", "[REDACTED_API_KEY]");
    }

    private record HistorySlice(long cursor, List<LogLine> lines) {}

    private record LogLine(long sequence, long timestamp, String level, String logger,
                           String message, boolean custom) {
        private JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("sequence", sequence);
            result.addProperty("timestamp", timestamp);
            result.addProperty("level", level);
            result.addProperty("logger", logger);
            result.addProperty("message", message);
            result.addProperty("custom", custom);
            return result;
        }
    }

    private static final class AgentAppender extends AbstractAppender {
        private AgentAppender() {
            super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            enqueue(event);
        }
    }
}
