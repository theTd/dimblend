package dimblend.blocks.compat.firefighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.blocks.compat.firefighting.LavaSprayQuenchMath.RayKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 消防水雾浇岩浆的纯数学自检：射线去重键、表面优先替换、5gt 节流预算与严格过滤条件。
 */
class LavaSprayQuenchMathTest {

    @Test
    void rayKeyIsNullForAirSphereSweepSamples() {
        // 消防模组的 4 格空气球扫掠 / 路径扫掠样本 direction 传 null
        assertNull(LavaSprayQuenchMath.rayKey(new BlockPos(1, 64, 3), null));
        // 零向量 = 没有方向信息，同样不能当射线
        assertNull(LavaSprayQuenchMath.rayKey(new BlockPos(1, 64, 3), Vec3.ZERO));
        assertNull(LavaSprayQuenchMath.rayKey(null, new Vec3(0, -1, 0)));
    }

    @Test
    void rayKeyIgnoresDirectionMagnitudeButNotDirection() {
        BlockPos origin = new BlockPos(5, 70, -12);
        RayKey unit = LavaSprayQuenchMath.rayKey(origin, new Vec3(0, -1, 0));
        RayKey scaled = LavaSprayQuenchMath.rayKey(origin, new Vec3(0, -7.5, 0));
        assertEquals(unit, scaled, "同一方向不同长度必须归一到同一射线键");
        assertNotEquals(unit, LavaSprayQuenchMath.rayKey(origin, new Vec3(0, 1, 0)));
        assertNotEquals(unit, LavaSprayQuenchMath.rayKey(origin.offset(1, 0, 0), new Vec3(0, -1, 0)),
                "原点不同 = 不同射线");
    }

    @Test
    void quantizeSnapsDirectionComponents() {
        assertEquals(0, LavaSprayQuenchMath.quantize(0.0D));
        assertEquals(1000, LavaSprayQuenchMath.quantize(1.0D));
        assertEquals(-1000, LavaSprayQuenchMath.quantize(-1.0D));
        assertEquals(707, LavaSprayQuenchMath.quantize(0.7071D));
        assertEquals(0, LavaSprayQuenchMath.quantize(Double.NaN));
    }

    @Test
    void surfaceFirstKeepsOnlyStrictlyCloserHit() {
        assertTrue(LavaSprayQuenchMath.isBetterSurfaceHit(4.0D, 2.5D), "更近的命中必须替换");
        assertFalse(LavaSprayQuenchMath.isBetterSurfaceHit(2.5D, 4.0D), "更远的命中不得覆盖表面");
        assertFalse(LavaSprayQuenchMath.isBetterSurfaceHit(3.0D, 3.0D), "等距保留先到的");
        assertFalse(LavaSprayQuenchMath.isBetterSurfaceHit(3.0D, Double.NaN));
    }

    @Test
    void burstBudgetIsCappedAtEight() {
        assertEquals(0, LavaSprayQuenchMath.burstSize(0, LavaSprayQuenchMath.BURST_LIMIT));
        assertEquals(5, LavaSprayQuenchMath.burstSize(5, LavaSprayQuenchMath.BURST_LIMIT));
        assertEquals(8, LavaSprayQuenchMath.burstSize(40, LavaSprayQuenchMath.BURST_LIMIT));
        assertEquals(0, LavaSprayQuenchMath.burstSize(40, 0));
        assertEquals(0, LavaSprayQuenchMath.burstSize(-3, LavaSprayQuenchMath.BURST_LIMIT));
    }

    @Test
    void queueRoomHasHardCap() {
        assertTrue(LavaSprayQuenchMath.hasRoom(0));
        assertTrue(LavaSprayQuenchMath.hasRoom(LavaSprayQuenchMath.MAX_PENDING_PER_LEVEL - 1));
        assertFalse(LavaSprayQuenchMath.hasRoom(LavaSprayQuenchMath.MAX_PENDING_PER_LEVEL));
    }

    @Test
    void onlyPlainUnignitedWaterRaysQueue() {
        assertTrue(LavaSprayQuenchMath.shouldQueue(true, true, false, true, true));

        // 空气球扫掠（无方向）
        assertFalse(LavaSprayQuenchMath.shouldQueue(true, false, false, true, true));
        // 点燃的水雾
        assertFalse(LavaSprayQuenchMath.shouldQueue(true, true, true, true, true));
        // 非水（牛奶/药水/龙息/岩浆）
        assertFalse(LavaSprayQuenchMath.shouldQueue(true, true, false, false, true));
        // 命中不是纯岩浆
        assertFalse(LavaSprayQuenchMath.shouldQueue(true, true, false, true, false));
        // 客户端
        assertFalse(LavaSprayQuenchMath.shouldQueue(false, true, false, true, true));
    }

    @Test
    void burstIntervalIsFiveTicks() {
        assertEquals(5, LavaSprayQuenchMath.BURST_INTERVAL_TICKS, "锁定 5gt 去重窗口");
        assertEquals(8, LavaSprayQuenchMath.BURST_LIMIT, "锁定每轮 8 格上限");
    }
}
