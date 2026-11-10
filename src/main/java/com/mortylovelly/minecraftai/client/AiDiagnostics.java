package com.mortylovelly.minecraftai.client;

import com.sun.management.OperatingSystemMXBean;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AiDiagnostics {
    private static final int HISTORY_SIZE=52;
    private static final long HISTORY_INTERVAL_NANOS=500_000_000L;
    private static final float[] FPS_HISTORY=new float[HISTORY_SIZE];
    private static final float[] TPS_HISTORY=new float[HISTORY_SIZE];
    private static final float[] GPU_HISTORY=new float[HISTORY_SIZE];
    private static final float[] CPU_HISTORY=new float[HISTORY_SIZE];
    private static final ScheduledExecutorService SYSTEM_MONITOR=Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread=new Thread(task,"Minecraft AI Agent diagnostics");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean enabled;
    private static boolean initialized;
    private static volatile float cpuLoad=-1.0F;
    private static volatile float gpuLoad=-1.0F;
    private static volatile long usedMemory;
    private static volatile long totalMemory;
    private static long sessionStartedNanos;
    private static long lastFrameNanos;
    private static long fpsWindowStartedNanos;
    private static long historySampleNanos;
    private static long totalFrames;
    private static int fpsWindowFrames;
    private static int currentFps;
    private static int maxFps;
    private static int lastPingMs=-1;
    private static float serverTps=-1.0F;
    private static float serverTickMs=-1.0F;
    private static int fpsHistoryCount,fpsHistoryNext;
    private static int tpsHistoryCount,tpsHistoryNext;
    private static int gpuHistoryCount,gpuHistoryNext;
    private static int cpuHistoryCount,cpuHistoryNext;

    private AiDiagnostics(){}

    public static synchronized void initialize(){
        if(initialized)return;
        initialized=true;
        resetSession();
        // Keep telemetry sampling available to the AI agent even when the HUD switch is off.
        SYSTEM_MONITOR.scheduleWithFixedDelay(AiDiagnostics::refreshSystemMetrics,1,2,TimeUnit.SECONDS);
    }

    public static synchronized void setEnabled(boolean value){enabled=value;}
    public static synchronized boolean isEnabled(){return enabled;}

    private static void resetSession(){
        long now=System.nanoTime();
        sessionStartedNanos=now;
        lastFrameNanos=0L;
        fpsWindowStartedNanos=now;
        historySampleNanos=now;
        totalFrames=0L;
        fpsWindowFrames=0;
        currentFps=0;
        maxFps=0;
        lastPingMs=-1;
        fpsHistoryCount=0;fpsHistoryNext=0;
        tpsHistoryCount=0;tpsHistoryNext=0;
        gpuHistoryCount=0;gpuHistoryNext=0;
        cpuHistoryCount=0;cpuHistoryNext=0;
        java.util.Arrays.fill(FPS_HISTORY,0.0F);
        java.util.Arrays.fill(TPS_HISTORY,-1.0F);
        java.util.Arrays.fill(GPU_HISTORY,-1.0F);
        java.util.Arrays.fill(CPU_HISTORY,-1.0F);
    }

    public static synchronized void onFrame(MinecraftClient client){
        if(client==null)return;
        long now=System.nanoTime();
        if(lastFrameNanos!=0L){
            long frameDuration=now-lastFrameNanos;
            if(frameDuration>0L&&frameDuration<5_000_000_000L){
                totalFrames++;
                fpsWindowFrames++;
                int instantaneous=(int)Math.round(1_000_000_000.0/frameDuration);
                if(instantaneous>0&&instantaneous<2000)maxFps=Math.max(maxFps,instantaneous);
            }
        }
        lastFrameNanos=now;
        long fpsWindowDuration=now-fpsWindowStartedNanos;
        if(fpsWindowDuration>=1_000_000_000L){
            currentFps=(int)Math.round(fpsWindowFrames*1_000_000_000.0/Math.max(1L,fpsWindowDuration));
            fpsWindowFrames=0;
            fpsWindowStartedNanos=now;
        }
        if(now-historySampleNanos>=HISTORY_INTERVAL_NANOS){
            lastPingMs=readPing(client);
            appendFps(currentFps>0?currentFps:averageFps(now));
            appendTps(serverTps);
            appendGpu(gpuLoad);
            appendCpu(cpuLoad);
            historySampleNanos=now;
        }
    }

    private static int readPing(MinecraftClient client){
        if(client.player==null||client.getNetworkHandler()==null)return -1;
        PlayerListEntry entry=client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        return entry==null?-1:Math.max(0,entry.getLatency());
    }

    private static void appendFps(float value){
        FPS_HISTORY[fpsHistoryNext]=validOrMissing(value);
        fpsHistoryNext=(fpsHistoryNext+1)%HISTORY_SIZE;
        fpsHistoryCount=Math.min(HISTORY_SIZE,fpsHistoryCount+1);
    }
    private static void appendTps(float value){
        TPS_HISTORY[tpsHistoryNext]=validOrMissing(value);
        tpsHistoryNext=(tpsHistoryNext+1)%HISTORY_SIZE;
        tpsHistoryCount=Math.min(HISTORY_SIZE,tpsHistoryCount+1);
    }
    private static void appendGpu(float value){
        GPU_HISTORY[gpuHistoryNext]=validOrMissing(value);
        gpuHistoryNext=(gpuHistoryNext+1)%HISTORY_SIZE;
        gpuHistoryCount=Math.min(HISTORY_SIZE,gpuHistoryCount+1);
    }
    private static void appendCpu(float value){
        CPU_HISTORY[cpuHistoryNext]=validOrMissing(value);
        cpuHistoryNext=(cpuHistoryNext+1)%HISTORY_SIZE;
        cpuHistoryCount=Math.min(HISTORY_SIZE,cpuHistoryCount+1);
    }
    private static float validOrMissing(float value){
        return Float.isFinite(value)&&value>=0.0F?value:-1.0F;
    }

    private static int averageFps(long now){
        long elapsed=now-sessionStartedNanos;
        if(totalFrames<=0L||elapsed<=0L)return 0;
        return (int)Math.round(totalFrames*1_000_000_000.0/elapsed);
    }

    public static synchronized void setServerMetrics(float tps,float tickMs){
        serverTps=Float.isFinite(tps)&&tps>=0.0F?Math.min(20.0F,tps):-1.0F;
        serverTickMs=Float.isFinite(tickMs)&&tickMs>=0.0F?tickMs:-1.0F;
    }

    public static synchronized void onWorldJoin(){
        resetSession();
        serverTps=-1.0F;
        serverTickMs=-1.0F;
    }
    public static synchronized void onDisconnect(){onWorldJoin();}

    public static synchronized Snapshot snapshot(MinecraftClient client){
        long now=System.nanoTime();
        int average=averageFps(now);
        int width=client==null?0:client.getWindow().getFramebufferWidth();
        int height=client==null?0:client.getWindow().getFramebufferHeight();
        return new Snapshot(currentFps,average,maxFps,lastPingMs,serverTps,serverTickMs,
                gpuLoad,cpuLoad,usedMemory,totalMemory,width,height,
                ordered(FPS_HISTORY,fpsHistoryCount,fpsHistoryNext,0.0F),
                ordered(TPS_HISTORY,tpsHistoryCount,tpsHistoryNext,-1.0F),
                ordered(GPU_HISTORY,gpuHistoryCount,gpuHistoryNext,-1.0F),
                ordered(CPU_HISTORY,cpuHistoryCount,cpuHistoryNext,-1.0F));
    }

    private static float[] ordered(float[] source,int count,int next,float emptyValue){
        float[] result=new float[HISTORY_SIZE];
        java.util.Arrays.fill(result,emptyValue);
        int start=(next-count+HISTORY_SIZE)%HISTORY_SIZE;
        int offset=HISTORY_SIZE-count;
        for(int i=0;i<count;i++)result[offset+i]=source[(start+i)%HISTORY_SIZE];
        return result;
    }

    private static void refreshSystemMetrics(){
        try{
            java.lang.management.OperatingSystemMXBean base=ManagementFactory.getOperatingSystemMXBean();
            if(base instanceof OperatingSystemMXBean os){
                double load=os.getCpuLoad();
                if(load>=0.0)cpuLoad=(float)Math.min(100.0,load*100.0);
                long total=os.getTotalMemorySize(),free=os.getFreeMemorySize();
                if(total>0L&&free>=0L){totalMemory=total;usedMemory=Math.max(0L,total-free);}
            }
        }catch(Throwable ignored){
            // Unsupported operating-system metrics remain unavailable.
        }
        try{gpuLoad=queryNvidiaGpu();}catch(Throwable ignored){gpuLoad=-1.0F;}
    }

    private static float queryNvidiaGpu(){
        List<String[]> commands=new ArrayList<>();
        commands.add(new String[]{"nvidia-smi","--query-gpu=utilization.gpu","--format=csv,noheader,nounits"});
        if(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win")){
            String programFiles=System.getenv("ProgramFiles");
            if(programFiles!=null&&!programFiles.isBlank()){
                String executable=programFiles+File.separator+"NVIDIA Corporation"+File.separator+"NVSMI"+File.separator+"nvidia-smi.exe";
                if(new File(executable).isFile())commands.add(new String[]{executable,"--query-gpu=utilization.gpu","--format=csv,noheader,nounits"});
            }
        }
        for(String[] command:commands){
            Process process=null;
            try{
                process=new ProcessBuilder(command).redirectErrorStream(true).start();
                if(!process.waitFor(1200,TimeUnit.MILLISECONDS)){process.destroyForcibly();continue;}
                String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
                if(process.exitValue()!=0||output.isEmpty())continue;
                float value=Float.parseFloat(output.split("\\R",2)[0].trim().replace("%",""));
                if(value>=0.0F&&value<=100.0F)return value;
            }catch(Exception ignored){if(process!=null)process.destroyForcibly();}
        }
        return -1.0F;
    }

    public record Snapshot(int currentFps,int averageFps,int maxFps,int pingMs,
                           float serverTps,float serverTickMs,float gpuLoad,float cpuLoad,
                           long usedMemory,long totalMemory,int resolutionWidth,int resolutionHeight,
                           float[] fpsHistory,float[] tpsHistory,float[] gpuHistory,float[] cpuHistory){}
}
