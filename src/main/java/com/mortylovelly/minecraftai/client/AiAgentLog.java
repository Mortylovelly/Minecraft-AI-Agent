package com.mortylovelly.minecraftai.client;

public final class AiAgentLog {
    private AiAgentLog() {}

    public static void info(String message) { write("INFO", message); }
    public static void warn(String message) { write("WARN", message); }
    public static void error(String message) { write("ERROR", message); }

    private static void write(String level, String message) {
        String safe = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        AiLogClient.addLocal(level, "minecraft_ai_agent.client", safe);
    }
}
