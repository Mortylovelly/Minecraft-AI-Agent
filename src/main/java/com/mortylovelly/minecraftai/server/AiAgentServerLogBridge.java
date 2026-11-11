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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class AiAgentServerLogBridge {
    public static final Identifier LOG_BATCH = new Identifier("minecraft_ai_agent", "log_batch");
    public static final Identifier LOG_STATUS = new Identifier("minecraft_ai_agent", "log_status");

    private static final String APPENDER_NAME = "MinecraftAiAgentServerLogBridge";
    private static final int MAX_QUEUE = 4096;
    // Small batches leave ample room for JSON escaping and network framing.
    private static final int BATCH_SIZE = 4;
    private static final int MAX_PACKET_CHARS = 24000;
    private static final int MAX_LOG_MESSAGE_CHARS = 1600;
    private static final int MAX_LOGGER_CHARS = 96;

    private static final Set<UUID> SUBSCRIBERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedDeque<LogLine> QUEUE = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger QUEUE_SIZE = new AtomicInteger();

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
        SUBSCRIBERS.add(player.getUuid());
        sendStatus(player, "Подключено к потоку серверных логов.", true);
    }

    public static void unsubscribe(UUID playerId) {
        SUBSCRIBERS.remove(playerId);
    }

    private static void attach(MinecraftServer currentServer) {
        detach(currentServer);
        server = currentServer;
        QUEUE.clear();
        QUEUE_SIZE.set(0);

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
            System.err.println("[Minecraft AI Agent] Could not attach server log appender: " + throwable.getMessage());
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
                System.err.println("[Minecraft AI Agent] Could not detach server log appender: " + throwable.getMessage());
            }
        }

        appender = null;
        loggerContext = null;
        rootLoggerConfig = null;
        server = null;
        SUBSCRIBERS.clear();
        QUEUE.clear();
        QUEUE_SIZE.set(0);
    }

    private static void flush(MinecraftServer currentServer) {
        if (SUBSCRIBERS.isEmpty()) {
            if (!QUEUE.isEmpty()) {
                QUEUE.clear();
                QUEUE_SIZE.set(0);
            }
            return;
        }

        for (int batchIndex = 0; batchIndex < 3; batchIndex++) {
            JsonArray entries = new JsonArray();
            int removed = 0;
            while (removed < BATCH_SIZE) {
                LogLine line = QUEUE.pollFirst();
                if (line == null) break;
                QUEUE_SIZE.decrementAndGet();
                entries.add(line.toJson());
                removed++;
            }
            if (entries.isEmpty()) break;

            JsonObject packetJson = new JsonObject();
            packetJson.add("lines", entries);
            String encodedBatch = packetJson.toString();

            // Never let an oversized diagnostic packet take down the integrated server.
            // If unusual control characters expand during JSON escaping, discard only
            // the newest entries until the encoded packet is safely below the limit.
            while (encodedBatch.length() > MAX_PACKET_CHARS && entries.size() > 0) {
                entries.remove(entries.size() - 1);
                packetJson.add("lines", entries);
                encodedBatch = packetJson.toString();
            }
            if (entries.isEmpty()) {
                System.err.println("[Minecraft AI Agent] Dropped an oversized server-log batch.");
                continue;
            }

            List<UUID> stale = new ArrayList<>();
            for (UUID id : SUBSCRIBERS) {
                ServerPlayerEntity player = currentServer.getPlayerManager().getPlayer(id);
                if (player == null || player.isDisconnected() || !player.hasPermissionLevel(2)) {
                    stale.add(id);
                    continue;
                }

                try {
                    PacketByteBuf buffer = PacketByteBufs.create();
                    buffer.writeString(encodedBatch, MAX_PACKET_CHARS);
                    ServerPlayNetworking.send(player, LOG_BATCH, buffer);
                } catch (RuntimeException exception) {
                    // Logging transport is optional; network encoding failures must not
                    // escape the server tick and crash the world being diagnosed.
                    System.err.println("[Minecraft AI Agent] Could not send server-log batch: "
                            + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                    stale.add(id);
                }
            }
            stale.forEach(SUBSCRIBERS::remove);
        }
    }

    private static void sendStatus(ServerPlayerEntity player, String status, boolean active) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(status, 512);
        buffer.writeBoolean(active);
        ServerPlayNetworking.send(player, LOG_STATUS, buffer);
    }

    private static void enqueue(LogEvent event) {
        if (SUBSCRIBERS.isEmpty() || event == null) return;

        String level = event.getLevel() == null ? "INFO" : event.getLevel().name();
        String logger = event.getLoggerName() == null ? "Minecraft" : event.getLoggerName();
        if (logger.length() > MAX_LOGGER_CHARS) {
            logger = logger.substring(0, MAX_LOGGER_CHARS);
        }
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
        LogLine line = new LogLine(event.getTimeMillis(), level, logger, message, custom);

        QUEUE.addLast(line);
        if (QUEUE_SIZE.incrementAndGet() > MAX_QUEUE) {
            if (QUEUE.pollFirst() != null) QUEUE_SIZE.decrementAndGet();
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
                || normalizedMessage.contains("[unique-relics]")
                || normalizedMessage.contains("[uniquerelics]")
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

    private record LogLine(long timestamp, String level, String logger, String message, boolean custom) {
        private JsonObject toJson() {
            JsonObject result = new JsonObject();
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
