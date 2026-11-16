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
    void mobSpawnCapsFlagTheBandsThatKeepTheSpawnerBusy() {
        // At or below the max in every category is fine.
        assertEquals(PerfStatus.OK, PerfRules.mobSpawnCapsStatus(40, 6, 8));
        assertEquals(PerfStatus.OK, PerfRules.mobSpawnCapsStatus(0, 0, 0));
        // Vanilla's 70 / 15 / 20, and one category over its own max, are flagged.
        assertEquals(PerfStatus.WARN, PerfRules.mobSpawnCapsStatus(70, 15, 20));
        assertEquals(PerfStatus.WARN, PerfRules.mobSpawnCapsStatus(41, 0, 0));
        assertEquals(PerfStatus.WARN, PerfRules.mobSpawnCapsStatus(0, 7, 0));
        assertEquals(PerfStatus.WARN, PerfRules.mobSpawnCapsStatus(0, 0, 9));
    }

    @Test
    void mergeTargetCapsPreservesStricterPlayerCapsAndExistingMiscCap() {
        java.util.Map<String, Integer> mergedFromDefaults = EasyMobSpawnControlBridge.mergeTargetCaps(
                java.util.Map.of("monster", -1, "creature", 3, "water_ambient", 20));
        assertEquals(PerfRules.MONSTER_SPAWN_CAP_MAX, mergedFromDefaults.get("monster"));
        assertEquals(3, mergedFromDefaults.get("creature"), "stricter player cap must not be raised");
        assertEquals(6, mergedFromDefaults.get("water_ambient"));
        assertEquals(-1, mergedFromDefaults.get("misc"), "missing misc defaults to -1 sentinel");

        java.util.Map<String, Integer> withExplicitMisc = EasyMobSpawnControlBridge.mergeTargetCaps(
                java.util.Map.of("misc", 15, "monster", 70));
        assertEquals(15, withExplicitMisc.get("misc"), "existing positive misc cap must never be overwritten to -1");
        assertEquals(PerfRules.MONSTER_SPAWN_CAP_MAX, withExplicitMisc.get("monster"));
    }

    @Test
    void randomTickSpeedAndTickingChutesBands() {
        assertEquals(PerfStatus.OK, PerfRules.randomTickSpeedStatus(0));
        assertEquals(PerfStatus.OK, PerfRules.randomTickSpeedStatus(PerfRules.RANDOM_TICK_SPEED_MAX));
        assertEquals(PerfStatus.WARN, PerfRules.randomTickSpeedStatus(PerfRules.RANDOM_TICK_SPEED_MAX + 1));
        assertEquals(PerfStatus.OK, PerfRules.tickingChutesStatus(0));
        assertEquals(PerfStatus.OK, PerfRules.tickingChutesStatus(PerfRules.TICKING_CHUTES_MAX));
        assertEquals(PerfStatus.WARN, PerfRules.tickingChutesStatus(PerfRules.TICKING_CHUTES_MAX + 1));
    }

    @Test
    void onlyCreateChutesCountAsChuteTickers() {
        assertTrue(PerfRules.isCreateChuteTickerType("create:chute"));
        assertTrue(PerfRules.isCreateChuteTickerType("create:smart_chute"));
        assertFalse(PerfRules.isCreateChuteTickerType("create:funnel"));
        assertFalse(PerfRules.isCreateChuteTickerType("create_connected:inventory_access_port"));
        assertFalse(PerfRules.isCreateChuteTickerType(null));
    }

    @Test
    void newStepsAcceptTheAdvicesTheirChecksProduce() {
        // Mirrors ServerPerfChecks/ClientPerfChecks: each result those checks can build must name
        // an advice its step declares, and only fixable steps may report fixable.
        assertEquals(PerfCheck.PREFIX + "mob_spawn_caps.advice.ok",
                PerfCheckResult.of(PerfCheck.MOB_SPAWN_CAPS, PerfScope.SERVER, PerfStatus.OK,
                        "ok", false, "40", "5", "6", "40", "5", "6").adviceKey());
        assertTrue(PerfCheckResult.of(PerfCheck.MOB_SPAWN_CAPS, PerfScope.SERVER, PerfStatus.WARN,
                "high", true, "70", "15", "20", "40", "5", "6").fixable());
        PerfCheckResult missing = PerfCheckResult.of(PerfCheck.MOB_SPAWN_CAPS, PerfScope.SERVER,
                PerfStatus.INFO, "missing", false, "70", "15", "20", "40", "5", "6");
        assertEquals(PerfCheck.MOB_SPAWN_CAPS, missing.check());
        assertTrue(PerfCheckResult.of(PerfCheck.RANDOM_TICK_SPEED, PerfScope.SERVER, PerfStatus.WARN,
                "high", true, "5", "3").fixable());
        assertEquals(PerfCheck.PREFIX + "chute_contraption_scan.advice.absent",
                PerfCheckResult.of(PerfCheck.CHUTE_CONTRAPTION_SCAN, PerfScope.SERVER,
                        PerfStatus.UNAVAILABLE, "absent", false, "-", "16").adviceKey());
        PerfCheckResult manyChutes = PerfCheckResult.of(PerfCheck.CHUTE_CONTRAPTION_SCAN, PerfScope.SERVER,
                PerfStatus.WARN, "high", false, "17", "16");
        assertFalse(manyChutes.fixable());
        PerfCheckResult immediate = PerfCheckResult.of(PerfCheck.IMMEDIATELY_FAST, PerfScope.CLIENT,
                PerfStatus.INFO, "missing", false);
        assertFalse(immediate.fixable());
        assertEquals(PerfCheck.PREFIX + "immediately_fast.advice.missing", immediate.adviceKey());
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
