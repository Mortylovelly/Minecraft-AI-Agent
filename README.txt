# Minecraft AI Agent 0.3.0

Minecraft 1.20.1 • Fabric • Java 17

A server-enabled Minecraft AI agent. The client provides the AI chat panel and model connection. The server executes world tools, checks operator permissions and controls entities. Install the same mod JAR on the client and on the Fabric server.

## Open the panel

Press **Right Shift** by default. Configure a provider API key in the panel. The key is stored locally in the client's config directory; it is not sent to the Minecraft server.

You can also address the agent from the normal game chat:

\`@AI find the coordinates from chat and teleport me there\`

The agent answers in chat using a server-generated message label. Such a message is not a signed message from a real player account.

## Server requirements

- Minecraft Java 1.20.1
- Fabric Loader 0.17.2 or newer
- Fabric API 0.92.11+1.20.1 or newer
- Java 17

Install the built JAR in the \`mods\` folder on both the client and the dedicated server. Restart the server. The server operator can check the integration with \`/aiagent status\`.

Mutating tools require the requesting player to have operator level 2. Read-only tools such as player state, nearby entities and block inspection are available without OP. A player cannot use the agent to modify another player's inventory or position unless they are an operator.

## Current server tools

Player state and inventory; player list; block inspection, placement, breaking and bounded area editing; world observation; structure location; item giving with custom names/enchantments; effects and player stats; teleport and game mode; time and weather; entity spawning and discovery; XP and spawnpoint; bounded Minecraft command execution; procedural building; custom-labelled system chat messages.

NPC and mob controls:
- Create a named server-side entity with \`create_npc\`.
- Set it to follow the requesting player.
- Control a nearby mob with \`control_entity\`: stop, follow, move to coordinates or attack an entity.
- Remove an NPC by its server entity ID.

These NPCs are server-side entities, not extra logged-in player accounts. Mob navigation is controlled by the server; it does not transfer the player's camera into the mob. Player-skin rendering and a fully player-like fake account are not included in this first server-enabled release.

## Chat archive and token economy

The client stores up to 1,800 received player and server chat messages in \`config/minecraft_ai_agent_game_chat.json\`. It captures messages while this client is connected; it cannot reconstruct messages sent before the archive was installed or while the client was offline.

The model receives only search results, not the entire archive. Use \`search_chat\` to find text, senders or coordinate patterns, and \`get_recent_chat\` for a short recent window. Normal chat messages are archived locally and do not trigger model requests. Only messages addressed to \`@AI\` start a task.

The agent routes only relevant tools for a request. Simple actions are executed with the fast path where supported; no tool list is sent for ordinary conversation. The server limits tool packet sizes and request rates.

## Build

GitHub Actions builds the mod with JDK 17:

\`./gradlew build --no-daemon\`

Artifact: \`build/libs/*.jar\`
