package dimblend.experience.compat.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * M3 / T-10：载入期岩浆结算的纯判定（见 {@link LavaSettleMath}）。
 *
 * <p>口径：answers.md 第 5 题选了「载入期结算到稳态」，M3.md 的 T-10 DoD 第 4 条要求
 * 单测覆盖「预算为 0 不处理 / 预算不足时按格子数截断 / 无岩浆时零开销」。几何常量
 * 抄自 {@code dimblend.worldgen.OakTrackCorridor}：轨道 z=0、{@code TRACK_Y = 64}、
 * 隧道半宽 {@code VAULT_RADIUS = 9}、平顶在轨道上方 {@code VAULT_APEX_DY = 17} 格。</p>
 */
class LavaSettleMathTest {

    @Test
    void zeroBudgetProcessesNothing() {
        // 预算 0：一格都不调度，候选全部丢弃（不是排队）
        LavaSettleMath.Plan plan = LavaSettleMath.plan(100, 0);
        assertEquals(0, plan.processed());
        assertEquals(100, plan.dropped());
        assertTrue(plan.idle());

        assertEquals(0, LavaSettleMath.plan(0, 0).processed());
        assertEquals(0, LavaSettleMath.plan(0, 0).dropped());
    }

    @Test
    void hundredCandidatesWithBudgetSixtyFourProcessesSixtyFour() {
        // DoD 例子：100 个候选、预算 64 → 只处理 64，余 36 丢弃
        assertEquals(64, LavaSettleMath.CELL_BUDGET);
        LavaSettleMath.Plan plan = LavaSettleMath.plan(100, LavaSettleMath.CELL_BUDGET);
        assertEquals(64, plan.processed());
        assertEquals(36, plan.dropped());
        assertFalse(plan.idle());
    }

    @Test
    void noLavaCostsNothing() {
        // 无岩浆：没有要处理的格，也没有丢弃，调用方连日志都不该打
        LavaSettleMath.Plan plan = LavaSettleMath.plan(0, LavaSettleMath.CELL_BUDGET);
        assertEquals(0, plan.processed());
        assertEquals(0, plan.dropped());
        assertTrue(plan.idle());
        assertEquals(0, LavaSettleMath.chunksThisTick(0));
    }

    @Test
    void perTickBudgetTakesAtMostChunksPerTick() {
        // 每 tick 的区块预算：待处理再多也只消化 CHUNKS_PER_TICK 个，其余留到下 tick
        assertEquals(LavaSettleMath.CHUNKS_PER_TICK, LavaSettleMath.chunksThisTick(10));
        assertEquals(1, LavaSettleMath.chunksThisTick(1));
        assertEquals(0, LavaSettleMath.chunksThisTick(-3));
    }

    @Test
    void fixedPassBudgetIsBounded() {
        // 固定轮数、不检查收敛（不是「跑到不动点」）：每区块最多 PASS_BUDGET 轮
        assertEquals(8, LavaSettleMath.PASS_BUDGET);
        assertEquals(LavaSettleMath.PASS_BUDGET, LavaSettleMath.passesLeft(0, LavaSettleMath.PASS_BUDGET));
        assertEquals(1, LavaSettleMath.passesLeft(LavaSettleMath.PASS_BUDGET - 1, LavaSettleMath.PASS_BUDGET));
        assertEquals(0, LavaSettleMath.passesLeft(LavaSettleMath.PASS_BUDGET, LavaSettleMath.PASS_BUDGET));
        assertEquals(0, LavaSettleMath.passesLeft(LavaSettleMath.PASS_BUDGET + 5, LavaSettleMath.PASS_BUDGET));
        assertEquals(0, LavaSettleMath.passesLeft(0, 0));
        // 最坏单区块开销：8 轮 × 64 格 = 512 个原版流体 step
        assertEquals(512, LavaSettleMath.PASS_BUDGET * LavaSettleMath.CELL_BUDGET);
    }

    @Test
    void bandFollowsCorridorGeometry() {
        // 轨道 z=0 / y=64；隧道半宽 9 + 岩浆单步水平可达 4 = |z| ≤ 13
        assertEquals(0, LavaSettleMath.TRACK_Z);
        assertEquals(64, LavaSettleMath.TRACK_Y);
        assertEquals(13, LavaSettleMath.BAND_MAX_Z);
        assertEquals(-13, LavaSettleMath.BAND_MIN_Z);

        assertTrue(LavaSettleMath.inBand(64, 0));
        assertTrue(LavaSettleMath.inBand(64, 13));
        assertTrue(LavaSettleMath.inBand(64, -13));
        // 平顶 81 再上一格：顶盖开口上方灌进来的岩浆也算在带内
        assertTrue(LavaSettleMath.inBand(82, 0));

        assertFalse(LavaSettleMath.inBand(64, 14));
        assertFalse(LavaSettleMath.inBand(64, -14));
        // vault 地板 dy=-1 仍是地形（OakTrackCorridor.VAULT_FLOOR_DY），不扫
        assertFalse(LavaSettleMath.inBand(63, 0));
        assertFalse(LavaSettleMath.inBand(83, 0));
    }

    @Test
    void onlyCorridorChunksAreScanned() {
        // 走廊沿 x 走：只有 chunkZ = -1 与 0 的 z 范围与 [-13, 13] 相交
        assertTrue(LavaSettleMath.chunkTouchesBand(0));
        assertTrue(LavaSettleMath.chunkTouchesBand(-1));
        assertFalse(LavaSettleMath.chunkTouchesBand(1));
        assertFalse(LavaSettleMath.chunkTouchesBand(-2));

        // 交集边界：-1 号区块是 z ∈ [-13, -1]，0 号区块是 z ∈ [0, 13]
        assertEquals(-13, LavaSettleMath.bandMinZ(-1));
        assertEquals(-1, LavaSettleMath.bandMaxZ(-1));
        assertEquals(0, LavaSettleMath.bandMinZ(0));
        assertEquals(13, LavaSettleMath.bandMaxZ(0));
    }
}
