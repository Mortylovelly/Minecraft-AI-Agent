package com.mortylovelly.minecraftai.client;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AiAllocationProfiler {
    private static final long REPORT_INTERVAL_SECONDS = 15L;
    private static final int MAX_RETAINED_CANDIDATES = 8;
    private static final String OWN_MOD_ID = "minecraft_ai_agent";

    private static final ConcurrentMap<String, String> SOURCE_TO_MOD = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> CLASS_TO_OWNER = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ModStats> INTERVAL_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> RETAINED_CANDIDATES = new ConcurrentLinkedDeque<>();

    private static final ScheduledExecutorService REPORTER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Minecraft AI Agent allocation report");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile RecordingStream recordingStream;
    private static volatile boolean initialized;
    private static volatile long totalSamples;
    private static volatile long totalEstimatedBytes;
    private static volatile long retainedSamples;

    private AiAllocationProfiler() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            int mappedSources = mapModSources();
            RecordingStream stream = new RecordingStream();
            stream.setMaxAge(Duration.ofSeconds(30));
            stream.setMaxSize(16L * 1024L * 1024L);

            stream.enable("jdk.ObjectAllocationSample")
                    .with("throttle", "40/s")
                    .withStackTrace();
            stream.enable("jdk.OldObjectSample")
                    .with("cutoff", "10 s")
                    .withStackTrace();

            stream.onEvent("jdk.ObjectAllocationSample", AiAllocationProfiler::acceptAllocation);
            stream.onEvent("jdk.OldObjectSample", AiAllocationProfiler::acceptRetainedObject);
            stream.onError(error -> AiLogClient.addCustomLocal("WARN", "MEMORY-PROFILER",
                    "JFR stream error: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage())));

            recordingStream = stream;
            stream.startAsync();

            AiLogClient.addCustomLocal("INFO", "MEMORY-PROFILER",
                    "JFR allocation profiling enabled; ObjectAllocationSample throttle=40/s; "
                            + "OldObjectSample cutoff=10s; mappedModSources=" + mappedSources
                            + "; reportInterval=" + REPORT_INTERVAL_SECONDS + "s. Allocation values are weighted estimates.");
            REPORTER.scheduleAtFixedRate(AiAllocationProfiler::reportSafely,
                    REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable error) {
            RecordingStream stream = recordingStream;
            recordingStream = null;
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // Ignore cleanup failure while disabling optional profiling.
                }
            }
            AiLogClient.addCustomLocal("WARN", "MEMORY-PROFILER",
                    "JFR profiling unavailable; heap/RAM telemetry remains active. "
                            + error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
    }

    private static int mapModSources() {
        int mapped = 0;
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String modId = container.getMetadata().getId();
            try {
                ModOrigin origin = container.getOrigin();
                if (!"PATH".equals(origin.getKind().name())) continue;
                for (Path path : origin.getPaths()) {
                    if (path == null) continue;
                    String key = normalizePath(path);
                    if (!key.isEmpty()) {
                        SOURCE_TO_MOD.putIfAbsent(key, modId);
                        mapped++;
                    }
                }
            } catch (Throwable ignored) {
                // Synthetic and nested mods may not expose a direct file-system origin.
            }
        }
        return mapped;
    }

    private static void acceptAllocation(RecordedEvent event) {
        try {
            long weight = Math.max(0L, event.getLong("weight"));
            if (weight == 0L) return;

            RecordedClass allocatedClass = event.getClass("objectClass");
            String className = allocatedClass == null ? "unknown" : allocatedClass.getName();
            String owner = resolveOwner(event.getStackTrace());

            ModStats stats = INTERVAL_STATS.computeIfAbsent(owner, ignored -> new ModStats());
            stats.add(weight, className);
            totalSamples++;
            totalEstimatedBytes += weight;
        } catch (Throwable ignored) {
            // A malformed or unsupported JFR event must not interrupt the game.
        }
    }

    private static void acceptRetainedObject(RecordedEvent event) {
        try {
            String owner = resolveOwner(event.getStackTrace());
            long ageSeconds = Math.max(0L, event.getDuration("objectAge").toSeconds());
            long lastHeap = event.getLong("lastKnownHeapUsage");
            String objectPath = oneLine(String.valueOf(event.getValue("object")), 380);
            String root = oneLine(String.valueOf(event.getValue("root")), 180);

            String candidate = "mod=" + owner
                    + " age=" + ageSeconds + "s"
                    + " lastHeap=" + formatBytes(lastHeap)
                    + " objectPath={" + objectPath + "}"
                    + " gcRoot={" + root + "}";
            RETAINED_CANDIDATES.addLast(candidate);
            retainedSamples++;
            while (RETAINED_CANDIDATES.size() > MAX_RETAINED_CANDIDATES) {
                RETAINED_CANDIDATES.pollFirst();
            }
        } catch (Throwable ignored) {
            // Old-object sampling is optional and must never affect normal gameplay.
        }
    }

    private static String resolveOwner(RecordedStackTrace stackTrace) {
        if (stackTrace == null) return "unmapped";
        String fallback = "unmapped";

        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (frame == null || frame.getMethod() == null || frame.getMethod().getType() == null) continue;
            String className = frame.getMethod().getType().getName();
            if (className == null || className.isBlank()) continue;

            String owner = ownerForClass(className);
            if (OWN_MOD_ID.equals(owner)) continue;
            if (!"unmapped".equals(owner)
                    && !"minecraft".equals(owner)
                    && !"JVM".equals(owner)
                    && !"fabric-loader".equals(owner)
                    && !"lwjgl".equals(owner)) {
                return owner;
            }
            if ("unmapped".equals(fallback) && !"unmapped".equals(owner)) fallback = owner;
        }
        return fallback;
    }

    private static String ownerForClass(String className) {
        String cached = CLASS_TO_OWNER.get(className);
        if (cached != null) return cached;

        String owner = "unmapped";
        try {
            Class<?> type = Class.forName(className, false, AiAllocationProfiler.class.getClassLoader());
            if (type.getProtectionDomain() != null && type.getProtectionDomain().getCodeSource() != null) {
                URL location = type.getProtectionDomain().getCodeSource().getLocation();
                if (location != null && "file".equalsIgnoreCase(location.getProtocol())) {
                    Path source = Path.of(location.toURI());
                    String matched = SOURCE_TO_MOD.get(normalizePath(source));
                    if (matched != null) owner = matched;
                }
            }
        } catch (Throwable ignored) {
            // Some transformed or dynamically loaded classes cannot be resolved by name.
        }

        if ("unmapped".equals(owner)) {
            if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) {
                owner = "minecraft";
            } else if (className.startsWith("java.") || className.startsWith("javax.")
                    || className.startsWith("jdk.") || className.startsWith("sun.")
                    || className.startsWith("com.sun.")) {
                owner = "JVM";
            } else if (className.startsWith("net.fabricmc.")) {
                owner = "fabric-loader";
            } else if (className.startsWith("org.lwjgl.")) {
                owner = "lwjgl";
            }
        }

        String previous = CLASS_TO_OWNER.putIfAbsent(className, owner);
        return previous == null ? owner : previous;
    }

    private static String normalizePath(Path path) {
        try {
            String value = path.toAbsolutePath().normalize().toString();
            return isWindows() ? value.toLowerCase(Locale.ROOT) : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void reportSafely() {
        try {
            report();
        } catch (Throwable error) {
            AiLogClient.addCustomLocal("WARN", "MEMORY-PROFILER",
                    "Could not build allocation report: " + error.getClass().getSimpleName()
                            + ": " + safe(error.getMessage()));
        }
    }

    private static void report() {
        List<ModSnapshot> snapshots = new ArrayList<>();
        long intervalBytes = 0L;
        long intervalSamples = 0L;

        for (Map.Entry<String, ModStats> entry : INTERVAL_STATS.entrySet()) {
            ModSnapshot snapshot = entry.getValue().drain(entry.getKey());
            if (snapshot.samples() <= 0L) continue;
            snapshots.add(snapshot);
            intervalBytes += snapshot.bytes();
            intervalSamples += snapshot.samples();
        }

        snapshots.sort(Comparator.comparingLong(ModSnapshot::bytes).reversed());
        StringBuilder top = new StringBuilder();
        int visible = Math.min(7, snapshots.size());
        for (int index = 0; index < visible; index++) {
            ModSnapshot snapshot = snapshots.get(index);
            if (!top.isEmpty()) top.append(" | ");
            top.append(snapshot.modId()).append('=').append(formatBytes(snapshot.bytes()))
                    .append(" (").append(snapshot.samples()).append(" samples)");
            if (!snapshot.topClasses().isEmpty()) {
                top.append(" [");
                for (int classIndex = 0; classIndex < snapshot.topClasses().size(); classIndex++) {
                    if (classIndex > 0) top.append(", ");
                    ClassAmount amount = snapshot.topClasses().get(classIndex);
                    top.append(amount.className()).append(':').append(formatBytes(amount.bytes()));
                }
                top.append(']');
            }
        }

        String message = "window=" + REPORT_INTERVAL_SECONDS + "s"
                + " samples=" + intervalSamples
                + " weightedAllocation≈" + formatBytes(intervalBytes)
                + " topMods={" + (top.isEmpty() ? "no samples" : top) + "}"
                + " sessionSamples=" + totalSamples
                + " sessionWeighted≈" + formatBytes(totalEstimatedBytes)
                + ". Weighted allocation is not retained heap size.";
        AiLogClient.addCustomLocal("INFO", "JFR-ALLOC", message);

        String candidate;
        while ((candidate = RETAINED_CANDIDATES.pollFirst()) != null) {
            AiLogClient.addCustomLocal("INFO", "JFR-RETAINED", candidate
                    + ". Old-object sample is a lead, not proof of a leak.");
        }
        if (retainedSamples == 0L) {
            // No forced GC is issued; samples appear only when the JVM's leak profiler has candidates to report.
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) return "n/a";
        double mib = bytes / 1048576.0;
        if (mib >= 1024.0) return String.format(Locale.ROOT, "%.2fGiB", mib / 1024.0);
        return String.format(Locale.ROOT, "%.1fMiB", mib);
    }

    private static String oneLine(String value, int maxLength) {
        if (value == null) return "n/a";
        String safe = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll("\\s+", " ").trim();
        return safe.length() > maxLength ? safe.substring(0, maxLength) + "…" : safe;
    }

    private static String safe(String value) {
        return value == null ? "no details" : oneLine(value, 240);
    }

    private static final class ModStats {
        private final LongAdder bytes = new LongAdder();
        private final LongAdder samples = new LongAdder();
        private final ConcurrentMap<String, LongAdder> classes = new ConcurrentHashMap<>();

        private void add(long weight, String className) {
            bytes.add(weight);
            samples.increment();
            classes.computeIfAbsent(className, ignored -> new LongAdder()).add(weight);
        }

        private ModSnapshot drain(String modId) {
            long byteCount = bytes.sumThenReset();
            long sampleCount = samples.sumThenReset();
            List<ClassAmount> classAmounts = new ArrayList<>();
            for (Map.Entry<String, LongAdder> entry : classes.entrySet()) {
                long classBytes = entry.getValue().sumThenReset();
                if (classBytes > 0L) classAmounts.add(new ClassAmount(entry.getKey(), classBytes));
            }
            classAmounts.sort(Comparator.comparingLong(ClassAmount::bytes).reversed());
            if (classAmounts.size() > 3) classAmounts = new ArrayList<>(classAmounts.subList(0, 3));
            return new ModSnapshot(modId, byteCount, sampleCount, classAmounts);
        }
    }

    private record ClassAmount(String className, long bytes) {}
    private record ModSnapshot(String modId, long bytes, long samples, List<ClassAmount> topClasses) {}
}
