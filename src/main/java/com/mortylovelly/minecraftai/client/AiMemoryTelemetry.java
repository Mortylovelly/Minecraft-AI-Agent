package com.mortylovelly.minecraftai.client;

import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiMemoryTelemetry {
    private static final long SAMPLE_INTERVAL_SECONDS = 3L;
    private static final long PROCESS_MEMORY_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final Pattern WINDOWS_MEMORY_FIELD = Pattern.compile(",\"([^\"]+)\"\\s*$");

    private static final ScheduledExecutorService MONITOR = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Minecraft AI Agent memory telemetry");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean initialized;
    private static volatile long processWorkingSet = -1L;
    private static volatile String processMemorySource = "unavailable";
    private static long lastProcessMemoryRefreshNanos;
    private static boolean heapPressureWarning;
    private static boolean systemPressureWarning;
    private static long lastFailureLogNanos;
    private static long previousHeapUsed = -1L;
    private static long previousOldGenUsed = -1L;
    private static long previousGcCount = -1L;
    private static long previousGcMillis = -1L;

    private AiMemoryTelemetry() {}

    /** Starts once per game process; collection is independent of which log source is displayed. */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        AiLogClient.addCustomLocal("INFO", "MEMORY-MONITOR",
                "Started; interval=" + SAMPLE_INTERVAL_SECONDS
                        + "s; captures heap/Old Gen deltas, non-heap, direct/mapped buffers, memory pools, GC, threads, process and system RAM.");
        AiAllocationProfiler.initialize();
        MONITOR.scheduleWithFixedDelay(AiMemoryTelemetry::sampleSafely,
                2L, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void sampleSafely() {
        try {
            AiLogClient.addCustomLocal("INFO", "JVM-MEMORY", buildSnapshot());
        } catch (Throwable throwable) {
            long now = System.nanoTime();
            if (now - lastFailureLogNanos > TimeUnit.SECONDS.toNanos(30L)) {
                lastFailureLogNanos = now;
                AiLogClient.addCustomLocal("WARN", "MEMORY-MONITOR",
                        "Snapshot failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
    }

    private static String buildSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();

        long heapUsed = heap.getUsed();
        long heapCommitted = heap.getCommitted();
        long heapMax = heap.getMax();
        long nonHeapUsed = nonHeap.getUsed();
        long nonHeapCommitted = nonHeap.getCommitted();

        long directUsed = 0L, directCapacity = 0L, directCount = 0L;
        long mappedUsed = 0L, mappedCapacity = 0L, mappedCount = 0L;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String name = pool.getName().toLowerCase(Locale.ROOT);
            if (name.contains("direct")) {
                directUsed = pool.getMemoryUsed();
                directCapacity = pool.getTotalCapacity();
                directCount = pool.getCount();
            } else if (name.contains("mapped")) {
                mappedUsed += Math.max(0L, pool.getMemoryUsed());
                mappedCapacity += Math.max(0L, pool.getTotalCapacity());
                mappedCount += Math.max(0L, pool.getCount());
            }
        }

        long systemTotal = -1L, systemFree = -1L, processVirtualCommitted = -1L;
        double systemCpu = -1.0, processCpu = -1.0;
        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
        if (base instanceof OperatingSystemMXBean os) {
            systemTotal = os.getTotalMemorySize();
            systemFree = os.getFreeMemorySize();
            processVirtualCommitted = os.getCommittedVirtualMemorySize();
            systemCpu = os.getCpuLoad();
            processCpu = os.getProcessCpuLoad();
        }
        long systemUsed = systemTotal >= 0L && systemFree >= 0L
                ? Math.max(0L, systemTotal - systemFree) : -1L;

        refreshProcessWorkingSet();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long gcCount = 0L, gcMillis = 0L;
        StringBuilder gc = new StringBuilder();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0L, collector.getCollectionCount());
            long time = Math.max(0L, collector.getCollectionTime());
            gcCount += count;
            gcMillis += time;
            if (!gc.isEmpty()) gc.append('|');
            gc.append(safeName(collector.getName())).append(':').append(count).append('/').append(time).append("ms");
        }

        long oldGenUsed = -1L;
        long oldGenCommitted = -1L;
        long oldGenMax = -1L;
        StringBuilder pools = new StringBuilder();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;
            if (pool.getName() != null && pool.getName().toLowerCase(Locale.ROOT).contains("old")) {
                oldGenUsed = usage.getUsed();
                oldGenCommitted = usage.getCommitted();
                oldGenMax = usage.getMax();
            }
            if (!pools.isEmpty()) pools.append(';');
            pools.append(safeName(pool.getName())).append('=')
                    .append(formatBytes(usage.getUsed())).append('/')
                    .append(formatBytes(usage.getCommitted())).append('/')
                    .append(formatLimit(usage.getMax()));
        }

        double heapPercent = heapMax > 0L ? heapUsed * 100.0 / heapMax : -1.0;
        double systemPercent = systemTotal > 0L && systemUsed >= 0L ? systemUsed * 100.0 / systemTotal : -1.0;

        checkPressure(heapPercent, systemPercent);

        String heapUsedDelta = formatDelta(heapUsed, previousHeapUsed);
        String oldGenUsedDelta = formatDelta(oldGenUsed, previousOldGenUsed);
        String gcCountDelta = previousGcCount < 0L ? "n/a" : Long.toString(gcCount - previousGcCount);
        String gcMillisDelta = previousGcMillis < 0L ? "n/a" : Long.toString(gcMillis - previousGcMillis) + "ms";
        previousHeapUsed = heapUsed;
        previousOldGenUsed = oldGenUsed;
        previousGcCount = gcCount;
        previousGcMillis = gcMillis;

        return "heapUsed=" + formatBytes(heapUsed)
                + " heapUsedDelta=" + heapUsedDelta
                + " oldGenUsed=" + formatBytes(oldGenUsed)
                + " oldGenUsedDelta=" + oldGenUsedDelta
                + " oldGenCommitted=" + formatBytes(oldGenCommitted)
                + " oldGenMax=" + formatLimit(oldGenMax)
                + " heapCommitted=" + formatBytes(heapCommitted)
                + " heapMax=" + formatLimit(heapMax)
                + " heapPct=" + formatPercent(heapPercent)
                + " runtimeUsed=" + formatBytes(runtime.totalMemory() - runtime.freeMemory())
                + " runtimeCommitted=" + formatBytes(runtime.totalMemory())
                + " runtimeMax=" + formatBytes(runtime.maxMemory())
                + " nonHeapUsed=" + formatBytes(nonHeapUsed)
                + " nonHeapCommitted=" + formatBytes(nonHeapCommitted)
                + " directUsed=" + formatBytes(directUsed)
                + " directCapacity=" + formatBytes(directCapacity)
                + " directBuffers=" + directCount
                + " mappedUsed=" + formatBytes(mappedUsed)
                + " mappedCapacity=" + formatBytes(mappedCapacity)
                + " mappedBuffers=" + mappedCount
                + " processWorkingSet=" + formatBytes(processWorkingSet)
                + " processWorkingSetSource=" + processMemorySource
                + " processVirtualCommitted=" + formatBytes(processVirtualCommitted)
                + " systemUsed=" + formatBytes(systemUsed)
                + " systemFree=" + formatBytes(systemFree)
                + " systemTotal=" + formatBytes(systemTotal)
                + " systemPct=" + formatPercent(systemPercent)
                + " processCpuPct=" + formatPercent(processCpu < 0.0 ? -1.0 : processCpu * 100.0)
                + " systemCpuPct=" + formatPercent(systemCpu < 0.0 ? -1.0 : systemCpu * 100.0)
                + " threads=" + threads.getThreadCount()
                + " daemonThreads=" + threads.getDaemonThreadCount()
                + " peakThreads=" + threads.getPeakThreadCount()
                + " gcTotalCount=" + gcCount
                + " gcTotalTimeMs=" + gcMillis
                + " gcSinceSample=" + gcCountDelta + "/" + gcMillisDelta
                + " gcPools={" + gc + "}"
                + " memoryPools={" + pools + "}";
    }

    private static void checkPressure(double heapPercent, double systemPercent) {
        if (heapPercent >= 90.0 && !heapPressureWarning) {
            heapPressureWarning = true;
            AiLogClient.addCustomLocal("WARN", "MEMORY-PRESSURE",
                    "JVM heap reached " + formatPercent(heapPercent) + " of its maximum.");
        } else if (heapPercent >= 0.0 && heapPercent < 85.0) {
            heapPressureWarning = false;
        }

        if (systemPercent >= 90.0 && !systemPressureWarning) {
            systemPressureWarning = true;
            AiLogClient.addCustomLocal("WARN", "MEMORY-PRESSURE",
                    "System RAM reached " + formatPercent(systemPercent) + " usage.");
        } else if (systemPercent >= 0.0 && systemPercent < 85.0) {
            systemPressureWarning = false;
        }
    }

    private static void refreshProcessWorkingSet() {
        long now = System.nanoTime();
        if (lastProcessMemoryRefreshNanos != 0L
                && now - lastProcessMemoryRefreshNanos < PROCESS_MEMORY_REFRESH_NANOS) return;
        lastProcessMemoryRefreshNanos = now;

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            long value = readWindowsWorkingSet();
            if (value >= 0L) {
                processWorkingSet = value;
                processMemorySource = "tasklist";
            }
            return;
        }

        Path procStatus = Path.of("/proc/self/status");
        if (Files.isRegularFile(procStatus)) {
            try {
                for (String line : Files.readAllLines(procStatus, StandardCharsets.US_ASCII)) {
                    if (!line.startsWith("VmRSS:")) continue;
                    String digits = line.replaceAll("[^0-9]", "").trim();
                    if (!digits.isEmpty()) {
                        processWorkingSet = Long.parseLong(digits) * 1024L;
                        processMemorySource = "/proc/self/status";
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the portable process query below.
            }
        }

        long value = readPsWorkingSet();
        if (value >= 0L) {
            processWorkingSet = value;
            processMemorySource = "ps";
        }
    }

    private static long readWindowsWorkingSet() {
        long pid = ProcessHandle.current().pid();
        Process process = null;
        try {
            process = new ProcessBuilder("tasklist.exe", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(1200L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return -1L;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            for (String line : output.split("\\R")) {
                if (!line.contains("\"" + pid + "\"")) continue;
                Matcher matcher = WINDOWS_MEMORY_FIELD.matcher(line);
                if (!matcher.find()) continue;
                String digits = matcher.group(1).replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) return Long.parseLong(digits) * 1024L;
            }
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
        }
        return -1L;
    }

    private static long readPsWorkingSet() {
        Process process = null;
        try {
            process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).start();
            if (!process.waitFor(1000L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return -1L;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String digits = output.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? -1L : Long.parseLong(digits) * 1024L;
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return -1L;
        }
    }

    private static String formatBytes(long bytes) {
        return bytes < 0L ? "n/a" : String.format(Locale.ROOT, "%.1fMiB", bytes / 1048576.0);
    }

    private static String formatLimit(long bytes) {
        return bytes < 0L ? "unbounded" : formatBytes(bytes);
    }

    private static String formatDelta(long current, long previous) {
        if (current < 0L || previous < 0L) return "n/a";
        long delta = current - previous;
        if (delta == 0L) return "0.0MiB";
        String sign = delta > 0L ? "+" : "-";
        double absoluteMiB = Math.abs((double) delta) / 1048576.0;
        if (absoluteMiB >= 1024.0) {
            return sign + String.format(Locale.ROOT, "%.2fGiB", absoluteMiB / 1024.0);
        }
        return sign + String.format(Locale.ROOT, "%.1fMiB", absoluteMiB);
    }

    private static String formatPercent(double percent) {
        return !Double.isFinite(percent) || percent < 0.0
                ? "n/a" : String.format(Locale.ROOT, "%.1f%%", percent);
    }

    private static String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
