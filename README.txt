# Minecraft AI Agent 0.3.3

Minecraft 1.20.1 • Fabric • Java 17

A server-enabled Minecraft AI agent. The client provides the AI chat panel and model connection. The server executes world tools, checks operator permissions, controls entities and streams the server's Log4j events to the client log window.

## Open the panel

Press **Right Shift** by default. Configure a provider API key in the panel. The key is stored locally in the client's config directory; it is not sent to the Minecraft server.

Click **Logs** in the AI panel to open the independent log HUD. The AI panel closes with a short animation and the log window stays on the game HUD; opening AI again with Right Shift temporarily hides Logs instead of drawing it over the AI screen. The log window starts compact (390 × 230 GUI pixels), can be dragged, resized up to half of the screen, collapsed with the minus button and closed with X. It uses an opaque grey background, slightly rounded corners and short open/close animations.

The log window has three filters:
- **Logs** — all received server Log4j events.
- **Custom logs** — events from Mortylovelly project namespaces and recognized project markers such as \`[Fix]\`.
- **Errors** — ERROR and FATAL events from both the general and custom streams.

Log text is re-wrapped when the window is resized. The panel keeps the latest 5,000 entries in memory for display and also saves the complete collected panel feed on the client in `logs/minecraft-ai-agent/latest.log`. The archive rotates through `latest.log.1` and `latest.log.2`; each file is capped at 4 MiB. The COPY button in the log window copies the full saved archive, oldest first, including all collected server, custom and error entries. The three counters show total, warning and error events received since joining the current world. INFO lines are green, WARN lines yellow, ERROR/FATAL lines red, DEBUG lines blue and TRACE lines muted. Minecraft item icons (emerald, glowstone dust and redstone) are used for the counters.

The dedicated-server log stream requires operator permission level 2. The server sends logs only to subscribed operator clients. API keys and common token patterns are redacted before transmission. The stream starts on join, so the counters include events from the moment the client connects; older server startup events are not replayed.

The log window reads the live server Log4j stream, not the server filesystem's `logs/latest.log` file directly. On a dedicated server it receives events from that server process. On an integrated singleplayer server the Log4j root is shared by the client and integrated server process. The local archive contains only entries that reached the Agent panel; it does not replace Minecraft's complete vanilla `latest.log` or automatically capture every third-party client mod logger. Common API-key and token patterns are redacted before entries are stored.

You can also address the agent from the normal game chat:

\`@AI find the coordinates from chat and teleport me there\`

The agent answers in chat using a server-generated message label. Such a message is not a signed message from a real player account.

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
