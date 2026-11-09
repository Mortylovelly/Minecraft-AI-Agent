package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AiGameChatHistory {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("minecraft_ai_agent_game_chat.jsonl");
    private static final int MAX_MESSAGES = 1800;
    private static final int MAX_MESSAGE_CHARS = 1200;
    private static final Pattern COORDINATE_PATTERN = Pattern.compile(
            "(?i)(?:x\\s*[:=]\\s*-?\\d+.*y\\s*[:=]\\s*-?\\d+.*z\\s*[:=]\\s*-?\\d+)|(?:-?\\d{1,7}\\s*[,; ]\\s*-?\\d{1,6}\\s*[,; ]\\s*-?\\d{1,7})"
    );
    private static final List<Entry> MESSAGES = new ArrayList<>();
    private static boolean loaded;

    private record Entry(long timestamp, String sender, String kind, String text) {}

    private AiGameChatHistory() {}

    public static synchronized void record(String sender, String kind, String text, long timestamp) {
        load();
        if (text == null || text.isBlank()) return;
        String safe = text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) : text;
        Entry entry = new Entry(timestamp, sender == null || sender.isBlank() ? "SERVER" : sender, kind, safe);
        MESSAGES.add(entry);
        if (MESSAGES.size() > MAX_MESSAGES) {
            while (MESSAGES.size() > MAX_MESSAGES) MESSAGES.remove(0);
            rewriteArchive();
        } else {
            appendEntry(entry);
        }
    }

    public static synchronized JsonObject search(String query, String mode, int requestedLimit) {
        load();
        int limit = Math.max(1, Math.min(20, requestedLimit));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean coordinates = "coordinates".equalsIgnoreCase(mode);
        JsonArray matches = new JsonArray();
        int total = 0;
        for (int i = MESSAGES.size() - 1; i >= 0; i--) {
            Entry entry = MESSAGES.get(i);
            boolean match = coordinates
                    ? COORDINATE_PATTERN.matcher(entry.text()).find()
                    : needle.isEmpty() || entry.text().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.sender().toLowerCase(Locale.ROOT).contains(needle);
            if (!match) continue;
            total++;
            if (matches.size() < limit) matches.add(toJson(entry));
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("mode", coordinates ? "coordinates" : "text");
        result.addProperty("matches_total", total);
        result.add("matches", matches);
        result.addProperty("archive_messages", MESSAGES.size());
        return result;
    }

    public static synchronized JsonObject recent(int requestedLimit) {
        load();
        int limit = Math.max(1, Math.min(20, requestedLimit));
        JsonArray messages = new JsonArray();
        for (int i = Math.max(0, MESSAGES.size() - limit); i < MESSAGES.size(); i++) {
            messages.add(toJson(MESSAGES.get(i)));
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("returned", messages.size());
        result.addProperty("archive_messages", MESSAGES.size());
        result.add("messages", messages);
        return result;
    }

    public static synchronized int size() {
        load();
        return MESSAGES.size();
    }

    private static JsonObject toJson(Entry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("time", Instant.ofEpochMilli(entry.timestamp()).toString());
        json.addProperty("sender", entry.sender());
        json.addProperty("type", entry.kind());
        json.addProperty("text", entry.text());
        return json;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(PATH)) return;
        try {
            List<String> lines = Files.readAllLines(PATH, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    JsonElement value = JsonParser.parseString(line);
                    if (!value.isJsonObject()) continue;
                    JsonObject object = value.getAsJsonObject();
                    MESSAGES.add(new Entry(
                            object.has("timestamp") ? object.get("timestamp").getAsLong() : System.currentTimeMillis(),
                            object.has("sender") ? object.get("sender").getAsString() : "SERVER",
                            object.has("kind") ? object.get("kind").getAsString() : "chat",
                            object.has("text") ? object.get("text").getAsString() : ""
                    ));
                } catch (RuntimeException ignored) {
                    // Ignore a single malformed record without losing the rest of the archive.
                }
            }
            if (MESSAGES.size() > MAX_MESSAGES) {
                while (MESSAGES.size() > MAX_MESSAGES) MESSAGES.remove(0);
                rewriteArchive();
            }
        } catch (Exception exception) {
            AiAgentLog.warn("Could not load game chat archive: " + exception.getMessage());
        }
    }

    private static void appendEntry(Entry entry) {
        JsonObject object = new JsonObject();
        object.addProperty("timestamp", entry.timestamp());
        object.addProperty("sender", entry.sender());
        object.addProperty("kind", entry.kind());
        object.addProperty("text", entry.text());
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(
                    PATH,
                    object + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            AiAgentLog.warn("Could not append game chat archive: " + exception.getMessage());
        }
    }

    private static void rewriteArchive() {
        StringBuilder content = new StringBuilder();
        for (Entry entry : MESSAGES) {
            JsonObject object = new JsonObject();
            object.addProperty("timestamp", entry.timestamp());
            object.addProperty("sender", entry.sender());
            object.addProperty("kind", entry.kind());
            object.addProperty("text", entry.text());
            content.append(object).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AiAgentLog.warn("Could not compact game chat archive: " + exception.getMessage());
        }
    }
}
