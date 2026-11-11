package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class AiAgentNetwork {
    private static final Identifier TOOL_REQUEST = new Identifier("minecraft_ai_agent", "tool_request");
    private static final Identifier TOOL_RESPONSE = new Identifier("minecraft_ai_agent", "tool_response");
    private static final Identifier LOG_SUBSCRIBE = new Identifier("minecraft_ai_agent", "log_subscribe");
    private static final Identifier LOG_BATCH = new Identifier("minecraft_ai_agent", "log_batch");
    private static final Identifier LOG_STATUS = new Identifier("minecraft_ai_agent", "log_status");
    private static final Identifier LOG_SNAPSHOT_REQUEST = new Identifier("minecraft_ai_agent", "log_snapshot_request");
    private static final Identifier LOG_SNAPSHOT_DONE = new Identifier("minecraft_ai_agent", "log_snapshot_done");
    private static final Identifier DIAGNOSTICS = new Identifier("minecraft_ai_agent", "diagnostics");

    private static final Map<String, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<JsonObject>> SNAPSHOT_PENDING = new ConcurrentHashMap<>();
    private static boolean initialized;

    private AiAgentNetwork() {}

    public static void initializeClient() {
        if (initialized) return;
        initialized = true;

        ClientPlayNetworking.registerGlobalReceiver(TOOL_RESPONSE, (client, handler, buffer, responseSender) -> {
            final String requestId = buffer.readString(64);
            final String json = buffer.readString(30000);
            client.execute(() -> {
                CompletableFuture<JsonObject> future = PENDING.remove(requestId);
                if (future == null) return;
                try {
                    future.complete(JsonParser.parseString(json).getAsJsonObject());
                } catch (RuntimeException exception) {
                    future.complete(error("Сервер вернул некорректный ответ: " + exception.getMessage()));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(DIAGNOSTICS, (client, handler, buffer, responseSender) -> {
            final float tps = buffer.readFloat();
            final float tickMs = buffer.readFloat();
            client.execute(() -> AiDiagnostics.setServerMetrics(tps, tickMs));
        });

        ClientPlayNetworking.registerGlobalReceiver(LOG_BATCH, (client, handler, buffer, responseSender) -> {
            final String json = buffer.readString(30000);
            client.execute(() -> AiLogClient.acceptRemoteBatch(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(LOG_STATUS, (client, handler, buffer, responseSender) -> {
            final String status = buffer.readString(512);
            final boolean connected = buffer.readBoolean();
            client.execute(() -> AiLogClient.setConnectionStatus(status, connected));
        });

        ClientPlayNetworking.registerGlobalReceiver(LOG_SNAPSHOT_DONE, (client, handler, buffer, responseSender) -> {
            final String requestId = buffer.readString(64);
            final boolean success = buffer.readBoolean();
            final int lineCount = buffer.readVarInt();
            final String message = buffer.readString(512);
            client.execute(() -> {
                CompletableFuture<JsonObject> future = SNAPSHOT_PENDING.remove(requestId);
                if (future == null) return;
                JsonObject result = new JsonObject();
                result.addProperty("ok", success);
                result.addProperty("received_lines", lineCount);
                result.addProperty("message", message);
                future.complete(result);
            });
        });
    }

    public static void subscribeToLogs() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) return;
        if (!ClientPlayNetworking.canSend(LOG_SUBSCRIBE)) {
            AiLogClient.setConnectionStatus("На сервере нет совместимого Minecraft AI Agent.", false);
            return;
        }
        try {
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeBoolean(true);
            ClientPlayNetworking.send(LOG_SUBSCRIBE, buffer);
            AiLogClient.setConnectionStatus("Запрашиваю доступ к серверным логам...", false);
        } catch (RuntimeException exception) {
            AiLogClient.setConnectionStatus("Не удалось включить поток логов: " + exception.getMessage(), false);
        }
    }

    public static void unsubscribeFromLogs() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null && ClientPlayNetworking.canSend(LOG_SUBSCRIBE)) {
            try {
                PacketByteBuf buffer = PacketByteBufs.create();
                buffer.writeBoolean(false);
                ClientPlayNetworking.send(LOG_SUBSCRIBE, buffer);
            } catch (RuntimeException ignored) {
                // Closing a diagnostic panel must remain harmless.
            }
        }
        AiLogClient.setConnectionStatus("Поток логов остановлен. Открой Logs HUD для продолжения.", false);
    }

    /**
     * Requests one bounded server-log snapshot for an explicit AI diagnostic query.
     * This does not enable the continuous Logs HUD stream.
     */
    public static CompletableFuture<JsonObject> requestRecentServerLogs() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return CompletableFuture.completedFuture(error(
                    "Нет подключения к миру или серверу; доступны только ранее сохранённые логи."));
        }
        if (!ClientPlayNetworking.canSend(LOG_SNAPSHOT_REQUEST)) {
            return CompletableFuture.completedFuture(error(
                    "Сервер не поддерживает запрос свежего снимка логов; выполнен поиск только по уже сохранённому клиентскому архиву."));
        }

        String requestId = UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        SNAPSHOT_PENDING.put(requestId, future);
        try {
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeString(requestId, 64);
            ClientPlayNetworking.send(LOG_SNAPSHOT_REQUEST, buffer);
        } catch (RuntimeException exception) {
            SNAPSHOT_PENDING.remove(requestId);
            return CompletableFuture.completedFuture(error(
                    "Не удалось запросить снимок серверных логов: " + exception.getMessage()));
        }

        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
            CompletableFuture<JsonObject> waiting = SNAPSHOT_PENDING.remove(requestId);
            if (waiting != null) {
                waiting.complete(error("Сервер не ответил на запрос свежих логов за 10 секунд."));
            }
        });
        return future;
    }

    public static CompletableFuture<JsonObject> requestTool(String toolName, JsonObject args) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return CompletableFuture.completedFuture(error("Сначала подключись к миру или серверу."));
        }
        if (!ClientPlayNetworking.canSend(TOOL_REQUEST)) {
            return CompletableFuture.completedFuture(error(
                    "На сервере не установлен совместимый Minecraft AI Agent или сервер не поддерживает сетевой интерфейс агента."));
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING.put(requestId, future);
        try {
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeString(requestId, 64);
            buffer.writeString(toolName, 64);
            String encodedArgs = args == null ? "{}" : args.toString();
            if (encodedArgs.length() > 30000) {
                PENDING.remove(requestId);
                return CompletableFuture.completedFuture(error("Аргументы инструмента слишком большие."));
            }
            buffer.writeString(encodedArgs, 30000);
            ClientPlayNetworking.send(TOOL_REQUEST, buffer);
        } catch (RuntimeException exception) {
            PENDING.remove(requestId);
            return CompletableFuture.completedFuture(error(
                    "Не удалось отправить запрос серверу: " + exception.getMessage()));
        }

        CompletableFuture.delayedExecutor(25, TimeUnit.SECONDS).execute(() -> {
            CompletableFuture<JsonObject> waiting = PENDING.remove(requestId);
            if (waiting != null) waiting.complete(error("Сервер не ответил на запрос за 25 секунд."));
        });
        return future;
    }

    public static void onDisconnect() {
        for (CompletableFuture<JsonObject> future : PENDING.values()) {
            future.complete(error("Соединение с сервером закрыто."));
        }
        PENDING.clear();
        for (CompletableFuture<JsonObject> future : SNAPSHOT_PENDING.values()) {
            future.complete(error("Соединение с сервером закрыто."));
        }
        SNAPSHOT_PENDING.clear();
        AiLogClient.onDisconnect();
        AiDiagnostics.onDisconnect();
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }
}
