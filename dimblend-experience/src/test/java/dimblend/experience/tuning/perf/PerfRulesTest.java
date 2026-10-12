package dimblend.experience.tuning.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PerfRulesTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void collectorIsClassifiedFromTheMxBeanNamesEachJvmReports() {
        assertEquals(PerfRules.Collector.G1, PerfRules.classifyCollector(
                List.of("G1 Young Generation", "G1 Concurrent GC", "G1 Old Generation")));
        assertEquals(PerfRules.Collector.ZGC_GENERATIONAL, PerfRules.classifyCollector(
                List.of("ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses")));
        assertEquals(PerfRules.Collector.ZGC_SINGLE, PerfRules.classifyCollector(List.of("ZGC Cycles", "ZGC Pauses")));
        assertEquals(PerfRules.Collector.OTHER, PerfRules.classifyCollector(List.of("PS Scavenge", "PS MarkSweep")));
        assertEquals(PerfStatus.OK, PerfRules.collectorStatus(PerfRules.Collector.ZGC_GENERATIONAL));
        assertEquals(PerfStatus.WARN, PerfRules.collectorStatus(PerfRules.Collector.G1));
    }

    @Test
    void everyCollectorAdviceExistsOnTheStep() {
        for (PerfRules.Collector collector : PerfRules.Collector.values()) {
            assertTrue(PerfCheck.GC_COLLECTOR.advices().contains(PerfRules.collectorAdvice(collector)), collector.name());
        }
    }

    @Test
    void heapHeadroomBandsMatchTheThresholds() {
        assertEquals(PerfStatus.OK, PerfRules.heapStatus(6 * GIB, 10 * GIB));
        assertEquals(PerfStatus.WARN, PerfRules.heapStatus(7 * GIB, 10 * GIB));
        assertEquals(PerfStatus.PROBLEM, PerfRules.heapStatus(8 * GIB, 10 * GIB));
        // 7 GiB live needs 7 / 0.6 = 11.7 -> 12 GiB to get back under 60 %.
        assertEquals(12, PerfRules.recommendedHeapGib(7 * GIB, 10 * GIB));
        // Never recommends shrinking below the current maximum.
        assertEquals(10, PerfRules.recommendedHeapGib(2 * GIB, 10 * GIB));
    }

    @Test
    void machineMemoryBandsMatchTheThresholds() {
        assertEquals(PerfStatus.OK, PerfRules.memoryStatus(24 * GIB, 64 * GIB));
        assertEquals(PerfStatus.WARN, PerfRules.memoryStatus(5 * GIB, 64 * GIB));
        assertEquals(PerfStatus.PROBLEM, PerfRules.memoryStatus(GIB / 2, 64 * GIB));
        for (PerfStatus status : new PerfStatus[] {PerfStatus.OK, PerfStatus.WARN, PerfStatus.PROBLEM}) {
            assertTrue(PerfCheck.HEAP_HEADROOM.advices().contains(PerfRules.severityAdvice(status)));
            assertTrue(PerfCheck.SYSTEM_MEMORY.advices().contains(PerfRules.severityAdvice(status)));
        }
    }

    @Test
    void sparkBackgroundProfilerDefaultsToOnLikeSparkItself() {
        assertTrue(PerfRules.sparkBackgroundEnabled(null));
        assertTrue(PerfRules.sparkBackgroundEnabled("{\"_header\": \"x\"}"));
        assertTrue(PerfRules.sparkBackgroundEnabled("{\"backgroundProfiler\": true}"));
        assertTrue(PerfRules.sparkBackgroundEnabled("not json {"));
        assertFalse(PerfRules.sparkBackgroundEnabled("{\"backgroundProfiler\": false}"));
    }

    @Test
    void distanceAndInterpolationStatuses() {
        assertEquals(PerfStatus.OK, PerfRules.distanceStatus(8, PerfRules.SIMULATION_DISTANCE_MAX));
        assertEquals(PerfStatus.WARN, PerfRules.distanceStatus(12, PerfRules.SIMULATION_DISTANCE_MAX));
        assertEquals(PerfStatus.OK, PerfRules.distanceStatus(12, PerfRules.RENDER_DISTANCE_MAX));
        assertEquals(PerfStatus.INFO, PerfRules.interpolationStatus(1.5D));
        assertEquals(PerfStatus.OK, PerfRules.interpolationStatus(3.0D));
    }

    @Test
    void argumentsAreKeptWithinTheNetworkLimitSoTheReportAlwaysEncodes() {
        // 2026-10-12 regression: generational ZGC's four MXBean names (70 characters) exceeded the
        // old 64-character limit, the report failed to encode and the player was disconnected.
        String zgc = "ZGC Minor Cycles, ZGC Minor Pauses, ZGC Major Cycles, ZGC Major Pauses";
        assertEquals(zgc, PerfCheckResult.of(PerfCheck.GC_COLLECTOR, PerfScope.SERVER, PerfStatus.OK,
                "zgc_generational", false, zgc).args().get(0));
        String huge = "x".repeat(PerfCheckResult.MAX_ARG_LENGTH * 2);
        String cut = PerfCheckResult.of(PerfCheck.GC_COLLECTOR, PerfScope.SERVER, PerfStatus.WARN, "other", false, huge)
                .args().get(0);
        assertTrue(cut.length() <= PerfCheckResult.MAX_ARG_LENGTH);
        assertTrue(cut.endsWith("…"));
    }

    @Test
    void resultsRejectAdvicesAndFixesTheStepDoesNotDeclare() {
        PerfCheckResult ok = PerfCheckResult.of(PerfCheck.RENDER_DISTANCE, PerfScope.CLIENT, PerfStatus.WARN,
                "high", true, "16", "12");
        assertEquals(PerfCheck.PREFIX + "render_distance.advice.high", ok.adviceKey());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PerfCheckResult.of(
                PerfCheck.RENDER_DISTANCE, PerfScope.CLIENT, PerfStatus.WARN, "nonsense", false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PerfCheckResult.of(
                PerfCheck.GC_COLLECTOR, PerfScope.SERVER, PerfStatus.WARN, "g1", true));
    }
}
