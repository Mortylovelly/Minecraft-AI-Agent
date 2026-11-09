package com.mortylovelly.minecraftai.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AiAgentServerNetworking {
    public static final Identifier TOOL_REQUEST = new Identifier("minecraft_ai_agent", "tool_request");
    public static final Identifier TOOL_RESPONSE = new Identifier("minecraft_ai_agent", "tool_response");
    public static final Identifier LOG_SUBSCRIBE = new Identifier("minecraft_ai_agent", "log_subscribe");

    private static final Map<UUID, long[]> REQUEST_WINDOW = new HashMap<>();

    private AiAgentServerNetworking() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(TOOL_REQUEST, (server, player, handler, buffer, responseSender) -> {
            final String requestId;
            final String toolName;
            final String rawArgs;
            try {
                requestId = buffer.readString(64);
                toolName = buffer.readString(64);
                rawArgs = buffer.readString(30000);
            } catch (RuntimeException exception) {
                return;
            }
            server.execute(() -> {
                JsonObject result;
                try {
                    if (requestId.isBlank() || !toolName.matches("[a-z0-9_]{1,64}")) {
                        result = failure("Некорректный запрос к серверному агенту.");
                    } else if (!allowRequest(player.getUuid(), System.currentTimeMillis())) {
                        result = failure("Слишком много запросов к серверу. Подожди несколько секунд.");
                    } else {
                        JsonObject args = JsonParser.parseString(rawArgs).getAsJsonObject();
                        result = AiAgentServerTools.execute(server, player, toolName, args);
                    }
                } catch (Exception exception) {
                    result = failure(exception.getMessage() == null ? exception.toString() : exception.getMessage());
                }

                String encoded = result.toString();
                if (encoded.length() > 30000) encoded = failure("Результат инструмента превысил допустимый размер.").toString();
                PacketByteBuf response = PacketByteBufs.create();
                response.writeString(requestId, 64);
                response.writeString(encoded, 30000);
                if (player.networkHandler != null && !player.isDisconnected()) {
                    ServerPlayNetworking.send(player, TOOL_RESPONSE, response);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LOG_SUBSCRIBE, (server, player, handler, buffer, responseSender) -> {
            final boolean subscribe;
            try {
                subscribe = buffer.readBoolean();
            } catch (RuntimeException exception) {
                return;
            }
            server.execute(() -> {
                if (subscribe) AiAgentServerLogBridge.subscribe(player);
                else AiAgentServerLogBridge.unsubscribe(player.getUuid());
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                AiAgentServerLogBridge.unsubscribe(handler.player.getUuid()));
    }

    private static boolean allowRequest(UUID playerId, long now) {
        long[] state = REQUEST_WINDOW.computeIfAbsent(playerId, ignored -> new long[]{now, 0});
        if (now - state[0] > 10000L) {
            state[0] = now;
            state[1] = 0;
        }
        state[1]++;
        return state[1] <= 40;
    }

    private static JsonObject failure(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }
}
