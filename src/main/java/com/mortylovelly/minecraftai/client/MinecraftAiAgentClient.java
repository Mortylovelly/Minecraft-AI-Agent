package com.mortylovelly.minecraftai.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;

public final class MinecraftAiAgentClient implements ClientModInitializer {
    private static KeyBinding openPanelKey;

    @Override
    public void onInitializeClient() {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv4Addresses", "true");

        AiAgentNetwork.initializeClient();

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                onPlayerChat(message, sender, receptionTimestamp));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                AiGameChatHistory.record("SERVER", "system", message.getString(), Instant.now().toEpochMilli());
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            AiLogClient.resetForWorldJoin();
            AiAgentNetwork.subscribeToLogs();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AiAgentNetwork.onDisconnect());

        openPanelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minecraft_ai_agent.open_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.minecraft_ai_agent"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(MinecraftAiAgentClient::tick);
    }

    private static void onPlayerChat(Text message, GameProfile sender, Instant receivedAt) {
        String senderName = sender == null ? "UNKNOWN" : sender.getName();
        long timestamp = receivedAt == null ? System.currentTimeMillis() : receivedAt.toEpochMilli();
        String raw = message.getString();
        AiGameChatHistory.record(senderName, "player", raw, timestamp);

        MinecraftClient client = MinecraftClient.getInstance();
        if (sender == null || client.player == null || !sender.getId().equals(client.player.getUuid())) return;

        int mention = raw.toLowerCase(java.util.Locale.ROOT).indexOf("@ai");
        if (mention < 0 || mention > 80) return;
        String request = raw.substring(mention + 3).replaceFirst("^[\\s,:;—-]+", "").trim();
        if (request.isEmpty()) return;

        String chatId = AiChatHistory.getCurrentChatId();
        AiAgentService.chat(chatId, request).thenAccept(reply -> client.execute(() -> {
            if (reply == null || reply.isBlank()) return;
            com.google.gson.JsonObject args = new com.google.gson.JsonObject();
            args.addProperty("name", "AI");
            args.addProperty("message", reply);
            AiAgentNetwork.requestTool("send_chat", args);
        }));
    }

    private static void tick(MinecraftClient client) {
        while (openPanelKey.wasPressed()) {
            if (client.currentScreen == null && client.player != null) {
                client.setScreen(new AiChatScreen());
            }
        }
    }
}
