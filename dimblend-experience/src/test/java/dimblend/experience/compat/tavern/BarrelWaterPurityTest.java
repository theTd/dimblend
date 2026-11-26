package dimblend.experience.compat.tavern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.dirtywater.DirtyWaterRules;
import org.junit.jupiter.api.Test;

/**
 * 酒桶水纯度口径：{@code 0 肮脏 / 1 过滤 / 2 杀菌 / 3 纯净}，酒桶水统一 {@code 1 = 过滤}。
 *
 * <p>只锁常量与取水作用域（这两条不碰 YAT）；真正的打标路径
 * （{@code addFluid}/{@code loadAdditional}/{@code removeFluid → FluidUtils.fillItem}）
 * 是 mixin，单测不应用 mixin，必须在整合包里实机走一遍。</p>
 */
class BarrelWaterPurityTest {

    @Test
    void barrelWaterIsFilteredNotSterilised() {
        assertEquals(1, BarrelWaterPurity.BARREL_WATER_PURITY, "酒桶水固定为 1 = 过滤");
    }

    @Test
    void filteredBarrelWaterStillPassesTheDirtyWaterGate() {
        // 用户口径：只拒 Purity 0。酒桶水必须能直接进食物链，否则倒进工作盆/配方会被拒收。
        assertFalse(DirtyWaterRules.isDirtyPurity(BarrelWaterPurity.BARREL_WATER_PURITY),
                "过滤水不能落进脏水区间");
        assertFalse(DirtyWaterRules.isDirtyWater(BarrelWaterPurity.BARREL_WATER_PURITY, DirtyWaterRules.WATER_ID));
    }

    @Test
    void takeScopeIsOffOutsideTheBarrelCallStack() {
        assertFalse(BarrelWaterPurity.isTakingBarrelWater(), "默认不在取水作用域内");
    }

    @Test
    void takeScopeWrapsTheCallAndIsAlwaysReset() {
        assertFalse(BarrelWaterPurity.isTakingBarrelWater());

        Boolean inside = BarrelWaterPurity.withinBarrelTake(BarrelWaterPurity::isTakingBarrelWater);
        assertTrue(inside, "作用域内必须为真");
        assertFalse(BarrelWaterPurity.isTakingBarrelWater(), "正常返回后必须复位");

        assertThrows(IllegalStateException.class,
                () -> BarrelWaterPurity.withinBarrelTake(BarrelWaterPurityTest::explode));
        assertFalse(BarrelWaterPurity.isTakingBarrelWater(), "异常退出也必须复位，否则同线程后续的榨汁桶取汁会被误覆写");
    }

    private static int explode() {
        throw new IllegalStateException("boom");
    }
}
