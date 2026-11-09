package com.mortylovelly.minecraftai.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class AiAgentEntityController {
    private static final Map<UUID, FollowTask> FOLLOW_TASKS = new HashMap<>();
    private static long ticks;

    private record FollowTask(String dimension, int entityId, UUID targetPlayer) {}

    private AiAgentEntityController() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(AiAgentEntityController::tick);
    }

    public static void follow(MobEntity mob, ServerPlayerEntity player) {
        FOLLOW_TASKS.put(mob.getUuid(), new FollowTask(
                mob.getWorld().getRegistryKey().getValue().toString(),
                mob.getId(),
                player.getUuid()
        ));
        mob.getNavigation().startMovingTo(player.getX(), player.getY(), player.getZ(), 1.1D);
    }

    public static void stop(Entity entity) {
        FOLLOW_TASKS.remove(entity.getUuid());
        if (entity instanceof MobEntity mob) mob.getNavigation().stop();
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % 10 != 0 || FOLLOW_TASKS.isEmpty()) return;
        Iterator<Map.Entry<UUID, FollowTask>> iterator = FOLLOW_TASKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FollowTask> entry = iterator.next();
            FollowTask task = entry.getValue();
            ServerWorld world = null;
            for (ServerWorld candidate : server.getWorlds()) {
                if (candidate.getRegistryKey().getValue().toString().equals(task.dimension())) {
                    world = candidate;
                    break;
                }
            }
            if (world == null) {
                iterator.remove();
                continue;
            }
            Entity entity = world.getEntityById(task.entityId());
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(task.targetPlayer());
            if (!(entity instanceof MobEntity mob) || !mob.isAlive() || target == null || target.getWorld() != world) {
                iterator.remove();
                continue;
            }
            if (mob.squaredDistanceTo(target) > 3.0 * 3.0) {
                mob.getNavigation().startMovingTo(target.getX(), target.getY(), target.getZ(), 1.1D);
            } else {
                mob.getNavigation().stop();
            }
        }
    }
}
