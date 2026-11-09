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
    private static final Map<String, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();
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
    }

    public static CompletableFuture<JsonObject> requestTool(String toolName, JsonObject args) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return CompletableFuture.completedFuture(error("Сначала подключись к миру или серверу."));
        }
        if (!ClientPlayNetworking.canSend(TOOL_REQUEST)) {
            return CompletableFuture.completedFuture(error("На сервере не установлен Minecraft AI Agent 0.3.0 или сервер не поддерживает сетевой интерфейс агента."));
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
            return CompletableFuture.completedFuture(error("Не удалось отправить запрос серверу: " + exception.getMessage()));
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
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }
}
