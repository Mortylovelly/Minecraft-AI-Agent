package com.mortylovelly.minecraftai.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public final class AiServerDiagnostics {
    private static final Identifier CHANNEL=new Identifier("minecraft_ai_agent","diagnostics");
    private static final int SAMPLE_COUNT=20;
    private static final long[] TICK_TIMES=new long[SAMPLE_COUNT];
    private static long lastTickStartedNanos;
    private static int sampleCount;
    private static int nextSample;
    private static int ticksSinceUpdate;

    private AiServerDiagnostics(){}

    public static void register(){
        ServerTickEvents.START_SERVER_TICK.register(AiServerDiagnostics::onStartTick);
        ServerTickEvents.END_SERVER_TICK.register(AiServerDiagnostics::onEndTick);
    }

    private static void onStartTick(MinecraftServer server){
        long now=System.nanoTime();
        if(lastTickStartedNanos!=0L){
            long interval=Math.max(1L,now-lastTickStartedNanos);
            TICK_TIMES[nextSample]=interval;
            nextSample=(nextSample+1)%SAMPLE_COUNT;
            sampleCount=Math.min(SAMPLE_COUNT,sampleCount+1);
        }
        lastTickStartedNanos=now;
    }

    private static void onEndTick(MinecraftServer server){
        if(++ticksSinceUpdate<10)return;
        ticksSinceUpdate=0;

        long total=0L;
        for(int i=0;i<sampleCount;i++)total+=TICK_TIMES[i];
        float averageMillis=(total/(float)Math.max(1,sampleCount))/1_000_000.0F;
        float tps=Math.min(20.0F,1000.0F/Math.max(1.0F,averageMillis));

        for(ServerPlayerEntity player:server.getPlayerManager().getPlayerList()){
            if(!ServerPlayNetworking.canSend(player,CHANNEL))continue;
            PacketByteBuf packet=PacketByteBufs.create();
            packet.writeFloat(tps);
            packet.writeFloat(averageMillis);
            ServerPlayNetworking.send(player,CHANNEL,packet);
        }
    }
}
