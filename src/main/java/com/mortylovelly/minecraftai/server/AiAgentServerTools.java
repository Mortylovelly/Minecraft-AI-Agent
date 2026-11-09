package com.mortylovelly.minecraftai.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mortylovelly.minecraftai.client.AiBuildTool;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AiAgentServerTools {
    private static final int MAX_FILL_BLOCKS = 20000;
    private static final int MAX_CLEAR_RADIUS = 15;
    private static final int MAX_INSPECT_BLOCKS = 2000;

    private AiAgentServerTools() {}

    public static JsonObject execute(MinecraftServer server, ServerPlayerEntity sender, String name, JsonObject args) {
        try {
            if (requiresOperator(name) && !sender.hasPermissionLevel(2)) {
                return error("Для этого действия нужны права оператора (OP).");
            }
            return switch (name) {
                case "get_player_state" -> playerState(target(server, sender, args));
                case "get_inventory" -> inventory(target(server, sender, args));
                case "list_players" -> listPlayers(server);
                case "get_block" -> getBlock(target(server, sender, args), args);
                case "set_block" -> setBlock(target(server, sender, args), args);
                case "break_block" -> breakBlock(target(server, sender, args), args);
                case "fill_area" -> fillArea(target(server, sender, args), args);
                case "clear_area" -> clearArea(target(server, sender, args), args);
                case "observe_world" -> observeWorld(target(server, sender, args), args);
                case "inspect_region" -> inspectRegion(target(server, sender, args), args);
                case "locate_structure" -> locateStructure(target(server, sender, args), args);
                case "give_item" -> giveItem(target(server, sender, args), args);
                case "apply_effect" -> applyEffect(server, target(server, sender, args), args);
                case "set_player_stats" -> setPlayerStats(target(server, sender, args), args);
                case "clear_inventory" -> clearInventory(target(server, sender, args));
                case "teleport_player" -> teleport(server, sender, target(server, sender, args), args);
                case "set_gamemode" -> setGamemode(server, sender, target(server, sender, args), args);
                case "set_time" -> setTime(server, sender, args);
                case "set_weather" -> setWeather(server, sender, args);
                case "spawn_entity" -> spawnEntity(server, sender, target(server, sender, args), args);
                case "give_experience" -> giveExperience(server, sender, target(server, sender, args), args);
                case "set_spawnpoint" -> setSpawnpoint(server, sender, target(server, sender, args), args);
                case "run_minecraft_command" -> runMinecraftCommand(server, sender, args);
                case "send_chat" -> sendChat(server, args);
                case "build_structure" -> buildStructure(server, sender, args);
                case "find_entities" -> findEntities(target(server, sender, args), args);
                case "create_npc" -> createNpc(target(server, sender, args), args);
                case "control_entity" -> controlEntity(target(server, sender, args), args);
                case "remove_npc" -> removeNpc(target(server, sender, args), args);
                default -> error("Неизвестный серверный инструмент: " + name);
            };
        } catch (Exception exception) {
            return error(exception.getMessage() == null ? exception.toString() : exception.getMessage());
        }
    }

    private static boolean requiresOperator(String name) {
        return switch (name) {
            case "set_block", "break_block", "fill_area", "clear_area", "give_item", "apply_effect",
                    "set_player_stats", "clear_inventory", "teleport_player", "set_gamemode", "set_time",
                    "set_weather", "spawn_entity", "give_experience", "set_spawnpoint",
                    "run_minecraft_command", "send_chat", "build_structure", "create_npc",
                    "control_entity", "remove_npc" -> true;
            default -> false;
        };
    }

    private static ServerPlayerEntity target(MinecraftServer server, ServerPlayerEntity sender, JsonObject args) {
        String requested = string(args, "player", "").trim();
        if (requested.isEmpty() || requested.equalsIgnoreCase(sender.getGameProfile().getName())) return sender;
        if (!sender.hasPermissionLevel(2)) throw new IllegalArgumentException("Нельзя управлять другим игроком без OP.");
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(requested);
        if (player == null) throw new IllegalArgumentException("Игрок не найден: " + requested);
        return player;
    }

    private static JsonObject playerState(ServerPlayerEntity player) {
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getGameProfile().getName());
        data.addProperty("x", round(player.getX()));
        data.addProperty("y", round(player.getY()));
        data.addProperty("z", round(player.getZ()));
        data.addProperty("block_x", player.getBlockPos().getX());
        data.addProperty("block_y", player.getBlockPos().getY());
        data.addProperty("block_z", player.getBlockPos().getZ());
        data.addProperty("yaw", round(player.getYaw()));
        data.addProperty("pitch", round(player.getPitch()));
        data.addProperty("health", round(player.getHealth()));
        data.addProperty("max_health", round(player.getMaxHealth()));
        data.addProperty("food", player.getHungerManager().getFoodLevel());
        data.addProperty("saturation", round(player.getHungerManager().getSaturationLevel()));
        data.addProperty("game_mode", player.interactionManager.getGameMode().getName());
        data.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
        return ok(data);
    }

    private static JsonObject inventory(ServerPlayerEntity player) {
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
            item.addProperty("name", stack.getName().getString());
            item.addProperty("count", stack.getCount());
            items.add(item);
        }
        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("occupied_slots", items.size());
        return ok(data);
    }

    private static JsonObject listPlayers(MinecraftServer server) {
        JsonArray players = new JsonArray();
        server.getPlayerManager().getPlayerList().forEach(player -> players.add(player.getGameProfile().getName()));
        JsonObject data = new JsonObject();
        data.add("players", players);
        data.addProperty("count", players.size());
        return ok(data);
    }

    private static JsonObject getBlock(ServerPlayerEntity player, JsonObject args) {
        BlockPos pos = position(args);
        if (!player.getServerWorld().isInBuildLimit(pos)) return error("Координата находится за пределами высоты мира.");
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("block", Registries.BLOCK.getId(player.getServerWorld().getBlockState(pos).getBlock()).toString());
        data.addProperty("state", player.getServerWorld().getBlockState(pos).toString());
        return ok(data);
    }

    private static JsonObject setBlock(ServerPlayerEntity player, JsonObject args) {
        BlockPos pos = position(args);
        if (!player.getServerWorld().isInBuildLimit(pos)) return error("Координата находится за пределами высоты мира.");
        Block block = block(string(args, "block", ""));
        boolean changed = player.getServerWorld().setBlockState(pos, block.getDefaultState(), 3);
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("block", Registries.BLOCK.getId(block).toString());
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return ok(data);
    }

    private static JsonObject breakBlock(ServerPlayerEntity player, JsonObject args) {
        BlockPos pos = position(args);
        if (!player.getServerWorld().isInBuildLimit(pos)) return error("Координата находится за пределами высоты мира.");
        String previous = Registries.BLOCK.getId(player.getServerWorld().getBlockState(pos).getBlock()).toString();
        boolean changed = player.getServerWorld().breakBlock(pos, false);
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("previous_block", previous);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return ok(data);
    }

    private static JsonObject fillArea(ServerPlayerEntity player, JsonObject args) {
        int x1 = integer(args, "x1"), y1 = integer(args, "y1"), z1 = integer(args, "z1");
        int x2 = integer(args, "x2"), y2 = integer(args, "y2"), z2 = integer(args, "z2");
        long volume = volume(x1, y1, z1, x2, y2, z2);
        if (volume > MAX_FILL_BLOCKS) return error("Слишком большая область: максимум " + MAX_FILL_BLOCKS + " блоков за один вызов.");
        String mode = string(args, "mode", "fill").toLowerCase(Locale.ROOT);
        if (!mode.equals("fill") && !mode.equals("hollow") && !mode.equals("clear")) return error("Неизвестный режим: " + mode);
        Block block = mode.equals("clear") ? Blocks.AIR : block(string(args, "block", ""));
        int changed = 0;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!player.getServerWorld().isInBuildLimit(pos)) continue;
                    boolean boundary = x == Math.min(x1, x2) || x == Math.max(x1, x2)
                            || y == Math.min(y1, y2) || y == Math.max(y1, y2)
                            || z == Math.min(z1, z2) || z == Math.max(z1, z2);
                    if (mode.equals("hollow") && !boundary) continue;
                    if (player.getServerWorld().setBlockState(pos, block.getDefaultState(), 3)) changed++;
                }
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("changed_blocks", changed);
        data.addProperty("requested_blocks", volume);
        data.addProperty("mode", mode);
        return ok(data);
    }

    private static JsonObject clearArea(ServerPlayerEntity player, JsonObject args) {
        int cx = optionalInt(args, "x", player.getBlockPos().getX());
        int cy = optionalInt(args, "y", player.getBlockPos().getY());
        int cz = optionalInt(args, "z", player.getBlockPos().getZ());
        int radius = clamp(optionalInt(args, "radius", 5), 1, MAX_CLEAR_RADIUS);
        int vertical = clamp(optionalInt(args, "vertical", radius), 1, MAX_CLEAR_RADIUS);
        String shape = string(args, "shape", "sphere").toLowerCase(Locale.ROOT);
        long volume = (long) (radius * 2 + 1) * (vertical * 2 + 1) * (radius * 2 + 1);
        if (volume > MAX_FILL_BLOCKS) return error("Область очистки слишком большая.");
        int changed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (shape.equals("sphere") && dx * dx + dz * dz + dy * dy > radius * radius) continue;
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!player.getServerWorld().isInBuildLimit(pos)) continue;
                    if (!player.getServerWorld().getBlockState(pos).isAir()
                            && player.getServerWorld().setBlockState(pos, Blocks.AIR.getDefaultState(), 3)) changed++;
                }
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("changed_blocks", changed);
        data.addProperty("shape", shape);
        data.addProperty("radius", radius);
        data.addProperty("vertical", vertical);
        return ok(data);
    }

    private static JsonObject observeWorld(ServerPlayerEntity player, JsonObject args) {
        JsonObject data = playerState(player);
        ServerWorld world = player.getServerWorld();
        data.addProperty("world_time", world.getTimeOfDay());
        data.addProperty("day_time", world.getTime());
        data.addProperty("raining", world.isRaining());
        data.addProperty("thundering", world.isThundering());
        JsonObject nearby = findEntities(player, withDefaults(args, "radius", 8, "limit", 12));
        data.add("nearby_entities", nearby.getAsJsonArray("entities"));
        return data;
    }

    private static JsonObject inspectRegion(ServerPlayerEntity player, JsonObject args) {
        ServerWorld world = player.getServerWorld();
        int cx = optionalInt(args, "x", player.getBlockPos().getX());
        int cy = optionalInt(args, "y", player.getBlockPos().getY());
        int cz = optionalInt(args, "z", player.getBlockPos().getZ());
        int radius = clamp(optionalInt(args, "radius", 3), 1, 6);
        int vertical = clamp(optionalInt(args, "vertical", 3), 1, 5);
        JsonArray blocks = new JsonArray();
        int total = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - vertical; y <= cy + vertical; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isInBuildLimit(pos)) continue;
                    Block block = world.getBlockState(pos).getBlock();
                    if (block == Blocks.AIR) continue;
                    total++;
                    if (blocks.size() < 120) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("x", x);
                        entry.addProperty("y", y);
                        entry.addProperty("z", z);
                        entry.addProperty("block", Registries.BLOCK.getId(block).toString());
                        blocks.add(entry);
                    }
                    if (total >= MAX_INSPECT_BLOCKS) break;
                }
                if (total >= MAX_INSPECT_BLOCKS) break;
            }
            if (total >= MAX_INSPECT_BLOCKS) break;
        }
        JsonObject data = new JsonObject();
        data.addProperty("center_x", cx);
        data.addProperty("center_y", cy);
        data.addProperty("center_z", cz);
        data.addProperty("total_non_air", total);
        data.add("blocks_sample", blocks);
        return ok(data);
    }

    private static JsonObject locateStructure(ServerPlayerEntity player, JsonObject args) {
        ServerWorld world = player.getServerWorld();
        String structure = string(args, "structure", "village").toLowerCase(Locale.ROOT);
        int radius = clamp(optionalInt(args, "radius", 32), 1, 128);
        var tag = switch (structure) {
            case "village", "villages", "деревня", "деревни" -> StructureTags.VILLAGE;
            case "mineshaft", "шахта" -> StructureTags.MINESHAFT;
            case "shipwreck", "корабль", "кораблекрушение" -> StructureTags.SHIPWRECK;
            case "ocean_ruin", "подводные_руины" -> StructureTags.OCEAN_RUIN;
            case "ruined_portal", "разрушенный_портал" -> StructureTags.RUINED_PORTAL;
            case "stronghold", "крепость", "крепость_энд" -> StructureTags.EYE_OF_ENDER_LOCATED;
            default -> {
                Identifier id = Identifier.tryParse(structure.contains(":") ? structure : "minecraft:" + structure);
                if (id == null) yield StructureTags.VILLAGE;
                yield net.minecraft.registry.tag.TagKey.of(RegistryKeys.STRUCTURE, id);
            }
        };
        BlockPos found = world.locateStructure(tag, player.getBlockPos(), radius, false);
        JsonObject data = new JsonObject();
        data.addProperty("requested_structure", structure);
        data.addProperty("found", found != null);
        data.addProperty("search_radius_chunks", radius);
        if (found != null) {
            data.addProperty("x", found.getX());
            data.addProperty("y", found.getY());
            data.addProperty("z", found.getZ());
            data.addProperty("distance", round(Math.sqrt(found.getSquaredDistance(player.getBlockPos()))));
        }
        return ok(data);
    }

    private static JsonObject giveItem(ServerPlayerEntity player, JsonObject args) {
        String rawId = string(args, "item", "");
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !Registries.ITEM.containsId(id)) return error("Неизвестный предмет: " + rawId);
        int count = clamp(optionalInt(args, "count", 1), 1, 64);
        ItemStack stack = new ItemStack(Registries.ITEM.get(id), count);
        String customName = string(args, "custom_name", "").trim();
        if (!customName.isEmpty()) stack.setCustomName(Text.literal(customName));
        if (args.has("unbreakable") && args.get("unbreakable").getAsBoolean()) stack.getOrCreateNbt().putBoolean("Unbreakable", true);
        String enchantments = string(args, "enchantments", "").trim();
        if (!enchantments.isEmpty()) {
            for (String entry : enchantments.split(",")) {
                String[] pair = entry.trim().split("[=:]", 2);
                if (pair.length != 2) continue;
                String enchantmentId = pair[0].trim();
                if (!enchantmentId.contains(":")) enchantmentId = "minecraft:" + enchantmentId;
                Identifier enchantId = Identifier.tryParse(enchantmentId);
                if (enchantId == null || !Registries.ENCHANTMENT.containsId(enchantId)) continue;
                int level;
                try { level = clamp(Integer.parseInt(pair[1].trim()), 1, 255); }
                catch (NumberFormatException ignored) { continue; }
                Enchantment enchantment = Registries.ENCHANTMENT.get(enchantId);
                stack.addEnchantment(enchantment, level);
            }
        }
        int given = stack.getCount();
        boolean inserted = player.getInventory().insertStack(stack);
        if (!stack.isEmpty()) player.dropItem(stack, false);
        player.currentScreenHandler.sendContentUpdates();
        JsonObject data = new JsonObject();
        data.addProperty("item", rawId);
        data.addProperty("count", given);
        data.addProperty("inserted_in_inventory", inserted);
        data.addProperty("custom_name", customName);
        data.addProperty("enchantments", enchantments);
        return ok(data);
    }

    private static JsonObject applyEffect(MinecraftServer server, ServerPlayerEntity player, JsonObject args) {
        String effect = string(args, "effect", "").trim();
        if (effect.isEmpty()) return error("Не указан эффект.");
        if (!effect.contains(":")) effect = "minecraft:" + effect;
        if (args.has("clear") && args.get("clear").getAsBoolean()) {
            return command(server, player, "effect clear " + player.getGameProfile().getName() + " " + effect);
        }
        int duration = clamp(optionalInt(args, "duration", 30), 1, 1000000);
        int level = clamp(optionalInt(args, "level", 1), 1, 255) - 1;
        boolean hideParticles = args.has("show_particles") && !args.get("show_particles").getAsBoolean();
        return command(server, player, "effect give " + player.getGameProfile().getName() + " " + effect + " " + duration + " " + level + " " + hideParticles);
    }

    private static JsonObject setPlayerStats(ServerPlayerEntity player, JsonObject args) {
        if (args.has("health")) player.setHealth(Math.max(0.0F, Math.min(player.getMaxHealth(), args.get("health").getAsFloat())));
        if (args.has("hunger")) player.getHungerManager().setFoodLevel(clamp(args.get("hunger").getAsInt(), 0, 20));
        if (args.has("saturation")) player.getHungerManager().setSaturationLevel(Math.max(0.0F, Math.min(20.0F, args.get("saturation").getAsFloat())));
        player.currentScreenHandler.sendContentUpdates();
        return playerState(player);
    }

    private static JsonObject clearInventory(ServerPlayerEntity player) {
        int removed = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                removed += stack.getCount();
                player.getInventory().setStack(slot, ItemStack.EMPTY);
            }
        }
        player.currentScreenHandler.sendContentUpdates();
        JsonObject data = new JsonObject();
        data.addProperty("items_removed", removed);
        return ok(data);
    }

    private static JsonObject teleport(MinecraftServer server, ServerPlayerEntity sender, ServerPlayerEntity target, JsonObject args) {
        double x = requiredDouble(args, "x");
        double y = requiredDouble(args, "y");
        double z = requiredDouble(args, "z");
        String dimension = string(args, "dimension", "").trim();
        String command = "tp " + target.getGameProfile().getName() + " " + x + " " + y + " " + z;
        if (!dimension.isEmpty()) command = "execute in " + (dimension.contains(":") ? dimension : "minecraft:" + dimension) + " run " + command;
        return command(server, sender, command);
    }

    private static JsonObject setGamemode(MinecraftServer server, ServerPlayerEntity sender, ServerPlayerEntity target, JsonObject args) {
        String mode = string(args, "mode", "survival").toLowerCase(Locale.ROOT);
        mode = switch (mode) {
            case "спектатор", "spectate", "spec" -> "spectator";
            case "креатив", "творческий" -> "creative";
            case "выживание", "сурвайвал", "выживач" -> "survival";
            case "приключение" -> "adventure";
            default -> mode;
        };
        if (!List.of("survival", "creative", "adventure", "spectator").contains(mode)) return error("Неизвестный режим: " + mode);
        return command(server, sender, "gamemode " + mode + " " + target.getGameProfile().getName());
    }

    private static JsonObject setTime(MinecraftServer server, ServerPlayerEntity sender, JsonObject args) {
        String time = string(args, "time", "day").toLowerCase(Locale.ROOT);
        if (!List.of("day", "night", "noon", "midnight").contains(time)) return error("Неизвестное время: " + time);
        return command(server, sender, "time set " + time);
    }

    private static JsonObject setWeather(MinecraftServer server, ServerPlayerEntity sender, JsonObject args) {
        String weather = string(args, "weather", "clear").toLowerCase(Locale.ROOT);
        if (!List.of("clear", "rain", "thunder").contains(weather)) return error("Неизвестная погода: " + weather);
        return command(server, sender, "weather " + weather + " 1000000");
    }

    private static JsonObject spawnEntity(MinecraftServer server, ServerPlayerEntity sender, ServerPlayerEntity player, JsonObject args) {
        String entity = string(args, "entity", "minecraft:pig");
        Identifier id = Identifier.tryParse(entity);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return error("Неизвестное существо: " + entity);
        int x = optionalInt(args, "x", player.getBlockPos().getX());
        int y = optionalInt(args, "y", player.getBlockPos().getY());
        int z = optionalInt(args, "z", player.getBlockPos().getZ());
        int count = clamp(optionalInt(args, "count", 1), 1, 16);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            JsonObject result = command(server, sender, "summon " + id + " " + x + " " + y + " " + z);
            if (result.get("ok").getAsBoolean()) spawned++;
        }
        JsonObject data = new JsonObject();
        data.addProperty("entity", id.toString());
        data.addProperty("spawned", spawned);
        return ok(data);
    }

    private static JsonObject giveExperience(MinecraftServer server, ServerPlayerEntity sender, ServerPlayerEntity player, JsonObject args) {
        int amount = clamp(integer(args, "amount"), 1, 1000000);
        String unit = string(args, "unit", "levels").toLowerCase(Locale.ROOT);
        if (!unit.equals("levels") && !unit.equals("points")) return error("unit должен быть levels или points.");
        return command(server, sender, "experience add " + player.getGameProfile().getName() + " " + amount + " " + unit);
    }

    private static JsonObject setSpawnpoint(MinecraftServer server, ServerPlayerEntity sender, ServerPlayerEntity player, JsonObject args) {
        int x = optionalInt(args, "x", player.getBlockPos().getX());
        int y = optionalInt(args, "y", player.getBlockPos().getY());
        int z = optionalInt(args, "z", player.getBlockPos().getZ());
        return command(server, sender, "spawnpoint " + player.getGameProfile().getName() + " " + x + " " + y + " " + z);
    }

    private static JsonObject runMinecraftCommand(MinecraftServer server, ServerPlayerEntity sender, JsonObject args) {
        String command = string(args, "command", "").trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty()) return error("Пустая команда.");
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!List.of("advancement", "attribute", "clear", "clone", "damage", "data", "difficulty",
                "effect", "enchant", "execute", "experience", "fill", "function", "gamemode",
                "give", "item", "kill", "locate", "loot", "particle", "place", "playsound",
                "recipe", "ride", "say", "schedule", "setblock", "spawnpoint", "spreadplayers",
                "summon", "tag", "team", "teleport", "tellraw", "time", "title", "trigger",
                "weather", "xp", "spectate").contains(root)) {
            return error("Эта команда не разрешена: " + root);
        }
        return command(server, sender, command);
    }

    private static JsonObject sendChat(MinecraftServer server, JsonObject args) {
        String message = string(args, "message", "").trim();
        if (message.isEmpty()) return error("Пустое сообщение.");
        if (message.length() > 2048) message = message.substring(0, 2048);
        String speaker = string(args, "name", "AI").trim();
        if (speaker.isEmpty()) speaker = "AI";
        if (speaker.length() > 32) speaker = speaker.substring(0, 32);
        // This is a server-generated system message with a configurable display label,
        // not a signed message or authenticated account belonging to another player.
        server.getPlayerManager().broadcast(Text.literal("<" + speaker + "> " + message), false);
        JsonObject data = new JsonObject();
        data.addProperty("sent", true);
        data.addProperty("display_name", speaker);
        data.addProperty("message", message);
        data.addProperty("message_type", "server_system_message");
        return ok(data);
    }

    private static JsonObject buildStructure(MinecraftServer server, ServerPlayerEntity sender, JsonObject args) {
        JsonObject adjusted = args.deepCopy();
        adjusted.addProperty("player", sender.getGameProfile().getName());
        return AiBuildTool.execute(server, adjusted);
    }

    private static JsonObject findEntities(ServerPlayerEntity player, JsonObject args) {
        int radius = clamp(optionalInt(args, "radius", 12), 1, 32);
        int limit = clamp(optionalInt(args, "limit", 10), 1, 30);
        String wanted = string(args, "type", "").trim().toLowerCase(Locale.ROOT);
        Box box = new Box(player.getBlockPos()).expand(radius);
        JsonArray entities = new JsonArray();
        List<Entity> found = player.getServerWorld().getOtherEntities(player, box, entity ->
                wanted.isEmpty() || Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(wanted)
                        || entity.getType().getTranslationKey().toLowerCase(Locale.ROOT).contains(wanted));
        found.stream().sorted(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player))).limit(limit).forEach(entity -> {
            JsonObject item = new JsonObject();
            item.addProperty("entity_id", entity.getId());
            item.addProperty("uuid", entity.getUuidAsString());
            item.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            item.addProperty("name", entity.getName().getString());
            item.addProperty("x", round(entity.getX()));
            item.addProperty("y", round(entity.getY()));
            item.addProperty("z", round(entity.getZ()));
            item.addProperty("distance", round(Math.sqrt(entity.squaredDistanceTo(player))));
            if (entity instanceof LivingEntity living) {
                item.addProperty("health", round(living.getHealth()));
                item.addProperty("max_health", round(living.getMaxHealth()));
            }
            entities.add(item);
        });
        JsonObject data = new JsonObject();
        data.add("entities", entities);
        data.addProperty("count", entities.size());
        return ok(data);
    }

    private static JsonObject createNpc(ServerPlayerEntity player, JsonObject args) {
        String rawType = string(args, "entity_type", "minecraft:villager");
        Identifier typeId = Identifier.tryParse(rawType);
        if (typeId == null || !Registries.ENTITY_TYPE.containsId(typeId)) return error("Неизвестный тип NPC: " + rawType);
        int x = optionalInt(args, "x", player.getBlockPos().getX() + 2);
        int y = optionalInt(args, "y", player.getBlockPos().getY());
        int z = optionalInt(args, "z", player.getBlockPos().getZ() + 2);
        String name = string(args, "name", "AI NPC").trim();
        if (name.isEmpty()) name = "AI NPC";
        if (name.length() > 32) name = name.substring(0, 32);
        Entity entity = Registries.ENTITY_TYPE.get(typeId).create(player.getServerWorld());
        if (entity == null) return error("Не удалось создать сущность: " + rawType);
        entity.refreshPositionAndAngles(x + 0.5, y, z + 0.5, player.getYaw(), 0.0F);
        entity.setCustomName(Text.literal(name));
        entity.setCustomNameVisible(true);
        if (!player.getServerWorld().spawnEntity(entity)) return error("Сервер отклонил создание NPC.");
        boolean following = args.has("follow_player") && args.get("follow_player").getAsBoolean();
        if (following && entity instanceof MobEntity mob) AiAgentEntityController.follow(mob, player);
        JsonObject data = new JsonObject();
        data.addProperty("created", true);
        data.addProperty("entity_id", entity.getId());
        data.addProperty("uuid", entity.getUuidAsString());
        data.addProperty("entity_type", typeId.toString());
        data.addProperty("name", name);
        data.addProperty("following", following && entity instanceof MobEntity);
        data.addProperty("skin_supported", false);
        data.addProperty("note", "Это серверная сущность NPC, а не отдельный подключённый аккаунт игрока.");
        return ok(data);
    }

    private static JsonObject controlEntity(ServerPlayerEntity player, JsonObject args) {
        Entity entity = player.getServerWorld().getEntityById(integer(args, "entity_id"));
        if (entity == null || !entity.isAlive()) return error("Сущность не найдена в текущем измерении.");
        if (entity.squaredDistanceTo(player) > 96.0 * 96.0) return error("Сущность слишком далеко: предел управления 96 блоков.");
        if (!(entity instanceof MobEntity mob)) return error("Эта сущность не поддерживает навигационное управление.");
        String action = string(args, "action", "stop").toLowerCase(Locale.ROOT);
        switch (action) {
            case "stop" -> AiAgentEntityController.stop(mob);
            case "follow_player" -> AiAgentEntityController.follow(mob, player);
            case "move_to" -> {
                int x = integer(args, "x"), y = integer(args, "y"), z = integer(args, "z");
                double speed = Math.max(0.1, Math.min(2.5, optionalDouble(args, "speed", 1.0)));
                AiAgentEntityController.stop(mob);
                mob.getNavigation().startMovingTo(x + 0.5, y, z + 0.5, speed);
            }
            case "attack_entity" -> {
                Entity target = player.getServerWorld().getEntityById(integer(args, "target_entity_id"));
                if (!(target instanceof LivingEntity living)) return error("Цель атаки не найдена или не является живой сущностью.");
                if (target.squaredDistanceTo(player) > 96.0 * 96.0) return error("Цель атаки слишком далеко.");
                mob.setTarget(living);
                AiAgentEntityController.stop(mob);
            }
            default -> { return error("Неизвестное действие управления: " + action); }
        }
        JsonObject data = new JsonObject();
        data.addProperty("controlled", true);
        data.addProperty("entity_id", entity.getId());
        data.addProperty("action", action);
        data.addProperty("entity_name", entity.getName().getString());
        return ok(data);
    }

    private static JsonObject removeNpc(ServerPlayerEntity player, JsonObject args) {
        Entity entity = player.getServerWorld().getEntityById(integer(args, "entity_id"));
        if (!(entity instanceof MobEntity)) return error("Удалять через этот инструмент можно только мобов и NPC.");
        if (entity.squaredDistanceTo(player) > 128.0 * 128.0) return error("Сущность слишком далеко.");
        AiAgentEntityController.stop(entity);
        entity.discard();
        JsonObject data = new JsonObject();
        data.addProperty("removed", true);
        data.addProperty("entity_id", integer(args, "entity_id"));
        return ok(data);
    }

    private static JsonObject command(MinecraftServer server, ServerPlayerEntity sender, String text) {
        int result = server.getCommandManager().executeWithPrefix(sender.getCommandSource(), text);
        JsonObject data = new JsonObject();
        data.addProperty("executed", result > 0);
        data.addProperty("result", result);
        data.addProperty("command", text);
        if (result <= 0) return error("Команда не выполнена: " + text);
        return ok(data);
    }

    private static Block block(String raw) {
        Identifier id = Identifier.tryParse(raw);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Неизвестный блок: " + raw);
        return Registries.BLOCK.get(id);
    }

    private static BlockPos position(JsonObject args) {
        return new BlockPos(integer(args, "x"), integer(args, "y"), integer(args, "z"));
    }

    private static JsonObject withDefaults(JsonObject source, String firstKey, int firstValue, String secondKey, int secondValue) {
        JsonObject copy = source.deepCopy();
        if (!copy.has(firstKey)) copy.addProperty(firstKey, firstValue);
        if (!copy.has(secondKey)) copy.addProperty(secondKey, secondValue);
        return copy;
    }

    private static int integer(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("Не указан аргумент: " + key);
        return object.get(key).getAsInt();
    }

    private static int optionalInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static double requiredDouble(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("Не указана координата: " + key);
        return object.get(key).getAsDouble();
    }

    private static double optionalDouble(JsonObject object, String key, double fallback) {
        return object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static long volume(int x1, int y1, int z1, int x2, int y2, int z2) {
        long dx = Math.abs((long) x2 - (long) x1) + 1L;
        long dy = Math.abs((long) y2 - (long) y1) + 1L;
        long dz = Math.abs((long) z2 - (long) z1) + 1L;
        if (dx > MAX_FILL_BLOCKS || dy > MAX_FILL_BLOCKS || dz > MAX_FILL_BLOCKS) return MAX_FILL_BLOCKS + 1L;
        if (dx * dy > MAX_FILL_BLOCKS / dz) return MAX_FILL_BLOCKS + 1L;
        return dx * dy * dz;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static JsonObject ok(JsonObject object) {
        object.addProperty("ok", true);
        return object;
    }

    private static JsonObject error(String message) {
        JsonObject object = new JsonObject();
        object.addProperty("ok", false);
        object.addProperty("error", message);
        return object;
    }
}
