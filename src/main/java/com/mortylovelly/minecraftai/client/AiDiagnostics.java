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
    private static final float[] PING_HISTORY=new float[HISTORY_SIZE];
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
    private static long fpsHistorySampleNanos;
    private static long pingHistorySampleNanos;
    private static long totalFrames;
    private static int fpsWindowFrames;
    private static int currentFps;
    private static int maxFps;
    private static int lastPingMs=-1;
    private static float serverTps=-1.0F;
    private static float serverTickMs=-1.0F;
    private static int fpsHistoryCount;
    private static int fpsHistoryNext;
    private static int pingHistoryCount;
    private static int pingHistoryNext;

    private AiDiagnostics(){}

    public static void initialize(){
        if(initialized)return;
        initialized=true;
        SYSTEM_MONITOR.scheduleWithFixedDelay(()->{
            if(enabled)refreshSystemMetrics();
        },1,2,TimeUnit.SECONDS);
    }

    public static synchronized void setEnabled(boolean value){
        if(enabled==value)return;
        enabled=value;
        if(value)resetSession();
    }

    private static void resetSession(){
        long now=System.nanoTime();
        sessionStartedNanos=now;
        lastFrameNanos=0L;
        fpsWindowStartedNanos=now;
        fpsHistorySampleNanos=now;
        pingHistorySampleNanos=now;
        totalFrames=0L;
        fpsWindowFrames=0;
        currentFps=0;
        maxFps=0;
        lastPingMs=-1;
        fpsHistoryCount=0;
        fpsHistoryNext=0;
        pingHistoryCount=0;
        pingHistoryNext=0;
        java.util.Arrays.fill(FPS_HISTORY,0.0F);
        java.util.Arrays.fill(PING_HISTORY,-1.0F);
    }

    public static synchronized void onFrame(MinecraftClient client){
        if(!enabled||client==null)return;
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

        if(now-pingHistorySampleNanos>=HISTORY_INTERVAL_NANOS){
            lastPingMs=readPing(client);
            appendHistory(PING_HISTORY,lastPingMs<0?-1.0F:lastPingMs,true);
            pingHistorySampleNanos=now;
        }
        if(now-fpsHistorySampleNanos>=HISTORY_INTERVAL_NANOS){
            int average=averageFps(now);
            float sample=currentFps>0?currentFps:average;
            appendHistory(FPS_HISTORY,sample,false);
            fpsHistorySampleNanos=now;
        }
    }

    private static int readPing(MinecraftClient client){
        if(client.player==null||client.getNetworkHandler()==null)return -1;
        PlayerListEntry entry=client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        return entry==null?-1:Math.max(0,entry.getLatency());
    }

    private static void appendHistory(float[] history,float value,boolean ping){
        if(ping){
            history[pingHistoryNext]=value;
            pingHistoryNext=(pingHistoryNext+1)%HISTORY_SIZE;
            pingHistoryCount=Math.min(HISTORY_SIZE,pingHistoryCount+1);
        }else{
            history[fpsHistoryNext]=value;
            fpsHistoryNext=(fpsHistoryNext+1)%HISTORY_SIZE;
            fpsHistoryCount=Math.min(HISTORY_SIZE,fpsHistoryCount+1);
        }
    }

    private static int averageFps(long now){
        long elapsed=now-sessionStartedNanos;
        if(totalFrames<=0L||elapsed<=0L)return 0;
        return (int)Math.round(totalFrames*1_000_000_000.0/elapsed);
    }

    public static synchronized void setServerMetrics(float tps,float tickMs){
        serverTps=tps;
        serverTickMs=tickMs;
    }

    public static synchronized void onWorldJoin(){
        serverTps=-1.0F;
        serverTickMs=-1.0F;
        lastPingMs=-1;
        java.util.Arrays.fill(PING_HISTORY,-1.0F);
        pingHistoryCount=0;
        pingHistoryNext=0;
    }

    public static synchronized void onDisconnect(){
        onWorldJoin();
    }

    public static synchronized Snapshot snapshot(MinecraftClient client){
        long now=System.nanoTime();
        int average=enabled?averageFps(now):0;
        int width=client==null?0:client.getWindow().getFramebufferWidth();
        int height=client==null?0:client.getWindow().getFramebufferHeight();
        long used=usedMemory;
        long total=totalMemory;
        return new Snapshot(currentFps,average,maxFps,lastPingMs,serverTps,serverTickMs,
                gpuLoad,cpuLoad,used,total,width,height,
                orderedHistory(FPS_HISTORY,fpsHistoryCount,fpsHistoryNext,0.0F),
                orderedHistory(PING_HISTORY,pingHistoryCount,pingHistoryNext,-1.0F));
    }

    private static float[] orderedHistory(float[] source,int count,int next,float emptyValue){
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
                long total=os.getTotalMemorySize();
                long free=os.getFreeMemorySize();
                if(total>0L&&free>=0L){
                    totalMemory=total;
                    usedMemory=Math.max(0L,total-free);
                }
            }
        }catch(Throwable ignored){
            // Hardware metrics are optional; the HUD displays an em dash when unavailable.
        }
        try{
            gpuLoad=queryNvidiaGpu();
        }catch(Throwable ignored){
            gpuLoad=-1.0F;
        }
    }

    private static float queryNvidiaGpu(){
        List<String[]> commands=new ArrayList<>();
        commands.add(new String[]{"nvidia-smi","--query-gpu=utilization.gpu","--format=csv,noheader,nounits"});
        if(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win")){
            String programFiles=System.getenv("ProgramFiles");
            if(programFiles!=null&&!programFiles.isBlank()){
                String executable=programFiles+File.separator+"NVIDIA Corporation"+File.separator
                        +"NVSMI"+File.separator+"nvidia-smi.exe";
                if(new File(executable).isFile()){
                    commands.add(new String[]{executable,"--query-gpu=utilization.gpu",
                            "--format=csv,noheader,nounits"});
                }
            }
        }
        for(String[] command:commands){
            Process process=null;
            try{
                process=new ProcessBuilder(command).redirectErrorStream(true).start();
                if(!process.waitFor(1200,TimeUnit.MILLISECONDS)){
                    process.destroyForcibly();
                    continue;
                }
                String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
                if(process.exitValue()!=0||output.isEmpty())continue;
                String firstLine=output.split("\\R",2)[0].trim().replace("%","");
                float value=Float.parseFloat(firstLine);
                if(value>=0.0F&&value<=100.0F)return value;
            }catch(Exception ignored){
                if(process!=null)process.destroyForcibly();
            }
        }
        return -1.0F;
    }

    public record Snapshot(int currentFps,int averageFps,int maxFps,int pingMs,
                           float serverTps,float serverTickMs,float gpuLoad,float cpuLoad,
                           long usedMemory,long totalMemory,int resolutionWidth,int resolutionHeight,
                           float[] fpsHistory,float[] pingHistory){}
}
