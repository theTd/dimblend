package dimblend.experience.tuning.perf;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;

/**
 * Pure thresholds behind the checklist. They encode what the 2026-10 TrainTripWorld spark captures
 * showed: spark's Java background sampler and G1 pauses are the largest tick stalls, ZGC needs
 * heap headroom, and new-terrain chunk work scales with simulation distance.
 */
public final class PerfRules {
    /** Simulation distance above which the ticking area (spawning, entities, new-chunk work) is flagged. */
    public static final int SIMULATION_DISTANCE_MAX = 8;
    /** Render (= singleplayer view) distance above which chunk loading per distance travelled is flagged. */
    public static final int RENDER_DISTANCE_MAX = 12;
    /** Sable snapshot buffer, in ticks, that hides stalls up to roughly (delay + 1) ticks. */
    public static final double SABLE_INTERPOLATION_RECOMMENDED = 3.0D;
    /** Live heap / max heap at or below which the collector has comfortable headroom. */
    public static final double HEAP_OK_RATIO = 0.60D;
    public static final double HEAP_CRITICAL_RATIO = 0.75D;
    public static final double MEMORY_OK_FREE_RATIO = 0.15D;
    public static final double MEMORY_CRITICAL_FREE_RATIO = 0.05D;
    private static final double GIB = 1024.0D * 1024.0D * 1024.0D;

    public enum Collector { ZGC_GENERATIONAL, ZGC_SINGLE, G1, OTHER }

    public static Collector classifyCollector(List<String> beanNames) {
        boolean zgc = false;
        boolean generational = false;
        for (String name : beanNames) {
            if (name.startsWith("G1 ")) {
                return Collector.G1;
            }
            if (name.startsWith("ZGC")) {
                zgc = true;
                generational |= name.contains("Minor") || name.contains("Major");
            }
        }
        if (zgc) {
            return generational ? Collector.ZGC_GENERATIONAL : Collector.ZGC_SINGLE;
        }
        return Collector.OTHER;
    }

    public static PerfStatus collectorStatus(Collector collector) {
        return collector == Collector.ZGC_GENERATIONAL ? PerfStatus.OK : PerfStatus.WARN;
    }

    public static String collectorAdvice(Collector collector) {
        return collector.name().toLowerCase(Locale.ROOT);
    }

    public static PerfStatus heapStatus(long liveBytes, long maxBytes) {
        double ratio = maxBytes <= 0 ? 1.0D : (double) liveBytes / maxBytes;
        if (ratio <= HEAP_OK_RATIO) {
            return PerfStatus.OK;
        }
        return ratio <= HEAP_CRITICAL_RATIO ? PerfStatus.WARN : PerfStatus.PROBLEM;
    }

    /** Smallest whole-GiB -Xmx that brings the live heap back to {@link #HEAP_OK_RATIO}. */
    public static int recommendedHeapGib(long liveBytes, long maxBytes) {
        int needed = (int) Math.ceil(liveBytes / HEAP_OK_RATIO / GIB);
        return Math.max(needed, (int) Math.ceil(maxBytes / GIB));
    }

    public static PerfStatus memoryStatus(long freeBytes, long totalBytes) {
        double free = totalBytes <= 0 ? 1.0D : (double) freeBytes / totalBytes;
        if (free >= MEMORY_OK_FREE_RATIO) {
            return PerfStatus.OK;
        }
        return free >= MEMORY_CRITICAL_FREE_RATIO ? PerfStatus.WARN : PerfStatus.PROBLEM;
    }

    public static String severityAdvice(PerfStatus status) {
        return switch (status) {
            case OK -> "ok";
            case WARN -> "low";
            default -> "critical";
        };
    }

    /**
     * Whether spark's config enables the background profiler. spark treats a missing key as
     * enabled; an unreadable file is treated the same, because spark falls back to its defaults.
     */
    public static boolean sparkBackgroundEnabled(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return true;
        }
        try {
            JsonElement root = JsonParser.parseString(configJson);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("backgroundProfiler")) {
                return true;
            }
            JsonElement value = root.getAsJsonObject().get("backgroundProfiler");
            return !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || value.getAsBoolean();
        } catch (JsonParseException | IllegalStateException e) {
            return true;
        }
    }

    public static PerfStatus distanceStatus(int current, int max) {
        return current <= max ? PerfStatus.OK : PerfStatus.WARN;
    }

    public static PerfStatus interpolationStatus(double delayTicks) {
        return delayTicks >= SABLE_INTERPOLATION_RECOMMENDED ? PerfStatus.OK : PerfStatus.INFO;
    }

    public static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / GIB);
    }

    private PerfRules() {
    }
}
