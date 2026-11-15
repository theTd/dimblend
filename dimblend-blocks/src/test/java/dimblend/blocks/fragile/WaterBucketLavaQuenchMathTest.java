package dimblend.blocks.fragile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.FlowTraceKind;
import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.FlowTraceResult;
import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.LavaCellSample;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 水桶 / 蓝冰冷却岩浆纯判定自检：3x3x3 源头立方体、岩浆流 <=16 / >16 上游回溯、冷却物消耗规则。
 */
class WaterBucketLavaQuenchMathTest {

    @Test
    void cube3x3x3CoversTwentySevenDistinctCells() {
        BlockPos center = new BlockPos(10, 64, -7);
        List<BlockPos> cells = WaterBucketLavaQuenchMath.cube3x3x3(center);

        assertEquals(27, cells.size());
        Set<BlockPos> unique = new HashSet<>(cells);
        assertEquals(27, unique.size(), "3x3x3 不允许重复格");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    assertTrue(unique.contains(center.offset(dx, dy, dz)),
                            "缺格 dx=" + dx + " dy=" + dy + " dz=" + dz);
                }
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> cells.add(BlockPos.ZERO));
    }

    @Test
    void sourceCubeOnlySolidifiesPureLavaSources() {
        assertTrue(WaterBucketLavaQuenchMath.shouldSolidifyInSourceCube(true, true, true));
        assertFalse(WaterBucketLavaQuenchMath.shouldSolidifyInSourceCube(true, false, true),
                "3x3x3 内流动岩浆不应被源头分支固化");
        assertFalse(WaterBucketLavaQuenchMath.shouldSolidifyInSourceCube(false, true, true));
        assertFalse(WaterBucketLavaQuenchMath.shouldSolidifyInSourceCube(true, true, false));
    }

    @Test
    void traceHorizontalFlowFindsSourceWithinSixteenSteps() {
        Map<BlockPos, LavaCellSample> world = new HashMap<>();
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos p1 = new BlockPos(1, 64, 0);
        BlockPos p2 = new BlockPos(2, 64, 0);
        BlockPos clicked = new BlockPos(3, 64, 0);
        world.put(source, LavaCellSample.source());
        world.put(p1, LavaCellSample.flowing(6));
        world.put(p2, LavaCellSample.flowing(4));
        world.put(clicked, LavaCellSample.flowing(2));

        FlowTraceResult trace = WaterBucketLavaQuenchMath.traceUpstream(
                clicked, pos -> world.getOrDefault(pos, LavaCellSample.NON_LAVA));

        assertEquals(FlowTraceKind.SOURCE_WITHIN_16, trace.kind());
        assertEquals(3, trace.steps());
        assertEquals(List.of(clicked, p2, p1, source), trace.pathFromClickedToUpstream());
    }

    @Test
    void traceWaterfallExactlySixteenStepsStillSolidifies() {
        Map<BlockPos, LavaCellSample> world = new HashMap<>();
        BlockPos clicked = new BlockPos(5, 50, 5);
        world.put(clicked, LavaCellSample.flowing(5));
        BlockPos fallBase = new BlockPos(4, 50, 5);
        world.put(fallBase, LavaCellSample.falling());
        for (int dy = 1; dy <= 14; dy++) {
            world.put(fallBase.above(dy), LavaCellSample.falling());
        }
        BlockPos source = fallBase.above(15); // 1 (horizontal) + 15 (vertical) = 16 steps
        world.put(source, LavaCellSample.source());

        FlowTraceResult trace = WaterBucketLavaQuenchMath.traceUpstream(
                clicked, pos -> world.getOrDefault(pos, LavaCellSample.NON_LAVA));

        assertEquals(FlowTraceKind.SOURCE_WITHIN_16, trace.kind());
        assertEquals(16, trace.steps());
        assertEquals(clicked, trace.pathFromClickedToUpstream().get(0));
        assertEquals(source, trace.pathFromClickedToUpstream().get(trace.pathFromClickedToUpstream().size() - 1));
    }

    @Test
    void traceWaterfallSeventeenStepsTriggersSmokeTrailOverSixteen() {
        Map<BlockPos, LavaCellSample> world = new HashMap<>();
        BlockPos clicked = new BlockPos(0, 40, 0);
        for (int dy = 0; dy < 17; dy++) {
            world.put(clicked.above(dy), LavaCellSample.falling());
        }
        BlockPos source = clicked.above(17);
        world.put(source, LavaCellSample.source());

        FlowTraceResult trace = WaterBucketLavaQuenchMath.traceUpstream(
                clicked, pos -> world.getOrDefault(pos, LavaCellSample.NON_LAVA));

        assertEquals(FlowTraceKind.SOURCE_OVER_16, trace.kind());
        assertEquals(17, trace.steps());
        assertEquals(clicked, trace.pathFromClickedToUpstream().get(0));
        assertEquals(source, trace.pathFromClickedToUpstream().get(17));
    }

    @Test
    void coolantConsumptionFollowsGameModeAndOver16Toggle() {
        assertTrue(WaterBucketLavaQuenchMath.CONSUME_WHEN_SOURCE_OVER_16, "lava-over-16-consume locked to true");
        assertTrue(WaterBucketLavaQuenchMath.shouldConsumeCoolant(false, false));
        assertEquals(WaterBucketLavaQuenchMath.CONSUME_WHEN_SOURCE_OVER_16,
                WaterBucketLavaQuenchMath.shouldConsumeCoolant(false, true));
        assertFalse(WaterBucketLavaQuenchMath.shouldConsumeCoolant(true, false));
        assertFalse(WaterBucketLavaQuenchMath.shouldConsumeCoolant(true, true));
    }
}
