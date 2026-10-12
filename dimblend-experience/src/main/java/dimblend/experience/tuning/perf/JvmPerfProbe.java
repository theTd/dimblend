package dimblend.experience.tuning.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Collector, heap-headroom and machine-memory steps for the JVM this code runs in. Shared by
 * the server checks and the client checks (a remote client is its own JVM, a singleplayer
 * client is not and reuses the server's results).
 */
public final class JvmPerfProbe {
    public static List<PerfCheckResult> collect(PerfScope scope) {
        return List.of(collector(scope), heap(scope), memory(scope));
    }

    private static PerfCheckResult collector(PerfScope scope) {
        List<String> names = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList();
        PerfRules.Collector collector = PerfRules.classifyCollector(names);
        return PerfCheckResult.of(PerfCheck.GC_COLLECTOR, scope, PerfRules.collectorStatus(collector),
                PerfRules.collectorAdvice(collector), false, String.join(", ", names));
    }

    private static PerfCheckResult heap(PerfScope scope) {
        long max = Runtime.getRuntime().maxMemory();
        long live = liveHeapBytes();
        PerfStatus status = PerfRules.heapStatus(live, max);
        long percent = max <= 0 ? 0 : Math.round(live * 100.0D / max);
        return PerfCheckResult.of(PerfCheck.HEAP_HEADROOM, scope, status, PerfRules.severityAdvice(status), false,
                PerfRules.gib(live), PerfRules.gib(max), Long.toString(percent),
                Integer.toString(PerfRules.recommendedHeapGib(live, max)));
    }

    /**
     * Heap still occupied right after the most recent collection of each heap pool (what the
     * collector cannot free); falls back to current usage when no pool reports it.
     */
    static long liveHeapBytes() {
        long live = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage afterCollection = pool.getCollectionUsage();
            if (afterCollection != null) {
                live += afterCollection.getUsed();
            }
        }
        if (live > 0) {
            return live;
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static PerfCheckResult memory(PerfScope scope) {
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)) {
            return PerfCheckResult.of(PerfCheck.SYSTEM_MEMORY, scope, PerfStatus.UNAVAILABLE, "ok", false,
                    "?", "?", "?");
        }
        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        long swapUsed = Math.max(0L, os.getTotalSwapSpaceSize() - os.getFreeSwapSpaceSize());
        PerfStatus status = PerfRules.memoryStatus(free, total);
        return PerfCheckResult.of(PerfCheck.SYSTEM_MEMORY, scope, status, PerfRules.severityAdvice(status), false,
                PerfRules.gib(free), PerfRules.gib(total), PerfRules.gib(swapUsed));
    }

    private JvmPerfProbe() {
    }
}
