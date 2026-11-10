# Minecraft AI Agent 0.3.12

Minecraft 1.20.1 • Fabric • Java 17

A server-enabled Minecraft AI agent. The client provides the AI chat panel and model connection. The server executes world tools, checks operator permissions, controls entities and streams the server's Log4j events to the client log window.

## Open the panel

Press **Right Shift** by default. Configure a provider API key in the panel. The key is stored locally in the client's config directory; it is not sent to the Minecraft server.

Click **Logs** in the AI panel to open the independent log HUD. The AI panel closes with a short animation and the log window stays on the game HUD; opening AI again with Right Shift temporarily hides Logs instead of drawing it over the AI screen. The log window starts at 300 × 175 GUI pixels. Its entire contents (log text, controls, filter tabs and text-only counters) scale together proportionally when you drag the bottom-right corner; the panel can be reduced to 25% of its base size or enlarged up to 160%, limited by the available screen. The outline is one pixel thin. The log area uses wrapped lines and a calculated visible-line count rather than a fixed GUI scissor rectangle, so the text clipping follows the resized panel. The three counters show total, warning and error counts as text without item icons. The former collapse button is now a gear icon that opens a minimal Settings view. Its Diagnostics switch adds a compact, proportionally scaled monitoring section beneath the log counters and filter buttons in the same panel. It reports live FPS, session average FPS and maximum FPS, client resolution, network ping, measured server TPS and average server tick time, system CPU and RAM usage, player coordinates and dimension, plus compact FPS, TPS, GPU and CPU history graphs. The server sends tick metrics twice per second to compatible clients. NVIDIA GPU utilization is read locally through nvidia-smi when available; unsupported systems show a dash rather than a fabricated value. Click the gear again to return to the logs. Press **G** to toggle the free cursor while in the world. It behaves like the pause-menu pointer: you can move the cursor, use the Logs HUD, and scroll without turning the camera or clicking blocks. The cursor mode stays enabled while you open a Minecraft screen such as the pause menu; when you return to the world, free cursor mode is restored automatically. Press **G** again to return to normal mouse capture. The binding can be changed in Options → Controls → Minecraft AI Agent. When upgrading from 0.3.6, the previous default Э binding is automatically migrated to G once; other saved bindings are kept. It uses an opaque grey background, slightly rounded corners and short open/close animations.

The log window has three filters:
- **Logs** — all received server Log4j events.
- **Custom logs** — events from Mortylovelly project namespaces and recognized project markers such as \`[Fix]\`.
- **Errors** — ERROR and FATAL events from both the general and custom streams.

Log text is re-wrapped when the window is resized. The panel keeps the latest 5,000 entries in memory for display and also saves the complete collected panel feed on the client in `logs/minecraft-ai-agent/latest.log`. The archive rotates through `latest.log.1` and `latest.log.2`; each file is capped at 4 MiB. The COPY button in the log window copies the full saved archive, oldest first, including all collected server, custom and error entries. The three text counters show total, warning and error events received since joining the current world. INFO lines are green, WARN lines yellow, ERROR/FATAL lines red, DEBUG lines blue and TRACE lines muted. The counters use text only, so they scale down cleanly with the rest of the panel.

The dedicated-server log stream requires operator permission level 2. The server sends logs only to subscribed operator clients. API keys and common token patterns are redacted before transmission. The stream starts on join, so the counters include events from the moment the client connects; older server startup events are not replayed.

The log window reads the live server Log4j stream, not the server filesystem's `logs/latest.log` file directly. On a dedicated server it receives events from that server process. On an integrated singleplayer server the Log4j root is shared by the client and integrated server process. The local archive contains only entries that reached the Agent panel; it does not replace Minecraft's complete vanilla `latest.log` or automatically capture every third-party client mod logger. Common API-key and token patterns are redacted before entries are stored.

You can also address the agent from the normal game chat:

\`@AI find the coordinates from chat and teleport me there\`

The agent answers in chat using a server-generated message label. Such a message is not a signed message from a real player account.


## AI Agent access to Logs HUD and diagnostics (0.3.12)

The AI Agent can query the same retained archive used by the Logs HUD through two client-local tools:
- \`search_agent_logs\` searches the retained panel archive (\`latest.log\`, \`latest.log.1\`, and \`latest.log.2\`) newest-first. It supports a text fragment, level filter, CUSTOM-only filtering, a bounded result count and offset pagination to continue into older matches. The archive retains up to three 4 MiB files. The agent receives matching lines rather than a blind dump of the entire archive.
- \`get_agent_diagnostics\` returns current FPS (session average and maximum too), ping, server TPS and tick interval, GPU/CPU/RAM, resolution, log-stream state and warning/error counters. It also returns all recent chart samples (FPS/TPS/GPU/CPU) in oldest-to-newest order, with a 500 ms sample interval.

The tool selector enables the relevant tools for log, error, crash, performance, lag, FPS/TPS, GPU/CPU/RAM and broad server-diagnosis questions. For troubleshooting the agent can correlate graph samples with log timestamps. Performance sampling continues even when the Diagnostics HUD switch is off, so telemetry remains available to the agent without requiring the graph view to be open.

Only the log feed collected by this mod and displayed by the Logs HUD is exposed here. This is not an unrestricted reader for Minecraft's entire vanilla \`latest.log\` or arbitrary third-party client-mod log files. Server-side log entries still depend on the existing server log bridge and its permission/subscription rules. Secrets are redacted; log contents are treated as untrusted diagnostic data rather than instructions.

## Server requirements

- Minecraft Java 1.20.1
- Fabric Loader 0.17.2 or newer
- Fabric API 0.92.11+1.20.1 or newer
- Java 17

Install the same mod JAR in the \`mods\` folder on both the client and the Fabric server. Restart the server. The server operator can check the integration with \`/aiagent status\`.

Mutating tools require the requesting player to have operator level 2. Read-only tools such as player state, nearby entities and block inspection are available without OP. Viewing server logs also requires OP 2 because they can contain private server diagnostics.

## Current server tools

Player state and inventory; player list; block inspection, placement, breaking and bounded area editing; world observation; structure location; item giving with custom names/enchantments; effects and player stats; teleport and game mode; time and weather; entity spawning and discovery; XP and spawnpoint; bounded Minecraft command execution; procedural building; custom-labelled system chat messages.

NPC and mob controls:
- Create a named server-side entity with \`create_npc\`.
- Set it to follow the requesting player.
- Control a nearby mob with \`control_entity\`: stop, follow, move to coordinates or attack an entity.
- Remove an NPC by its server entity ID.

These NPCs are server-side entities, not extra logged-in player accounts. Mob navigation is controlled by the server; it does not transfer the player's camera into the mob. Player-skin rendering and a fully player-like fake account are not included yet.

## Chat archive and token economy

The client stores up to 1,800 received player and server chat messages in \`config/minecraft_ai_agent_game_chat.jsonl\`. It captures messages while this client is connected; it cannot reconstruct messages sent before the archive was installed or while the client was offline.

The model receives only search results, not the entire archive. Use \`search_chat\` to find text, senders or coordinate patterns, and \`get_recent_chat\` for a short recent window. Normal chat messages are archived locally and do not trigger model requests. Only messages addressed to \`@AI\` start a task.

The agent routes only relevant tools for a request. Simple fast-path actions can run without an API key; when a key is configured, these are executed without a model request. Other actions use the model only when necessary. The server limits tool packet sizes and request rates.

## Build

GitHub Actions builds the mod with JDK 17:

\`./gradlew build --no-daemon\`

Artifact: \`build/libs/*.jar\`
