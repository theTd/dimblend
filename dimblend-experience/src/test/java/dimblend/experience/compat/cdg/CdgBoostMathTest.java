package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** B9 柴油机 boost 启动（见 {@link CdgBoostMath}）：对齐目标/触发判据/逆推档位（纯函数，零依赖）。 */
class CdgBoostMathTest {

    private static float stepped(int rampTicks) {
        return 16.0F + 2.0F * (rampTicks / 80);
    }

    @Test
    void boostTargetIsNetworkCappedAt80PercentOfRated() {
        // min(|net|, 0.8×rated)：128/96→76.8（封顶 80% 留 20% 自爬）、50/96→50（网络低于封顶）
        assertEquals(76.8F, CdgBoostMath.boostTarget(128.0F, 96.0F), 0.0001F);
        assertEquals(50.0F, CdgBoostMath.boostTarget(50.0F, 96.0F), 0.0001F);
        // 额定口径按调用方实参（普通/组合式 upgrade.getSpeed×throttle，巨型 |fuelSpeed×throttle|）
        assertEquals(80.0F, CdgBoostMath.boostTarget(200.0F, 100.0F), 0.0001F);
    }

    @Test
    void rampTicksForNeverOvershootsTarget() {
        // 76.8 → floor((76.8-16)×40)=2432：阶梯 76 ≤ 76.8，且再上一档（+80 tick）即 78 超目标
        int ticks = CdgBoostMath.rampTicksFor(76.8F);
        assertEquals(2432, ticks);
        assertEquals(76.0F, stepped(ticks), 0.0001F);
        assertTrue(stepped(ticks) <= 76.8F);
        assertTrue(stepped(ticks + 80) > 76.8F);
        // 整档目标 50：floor(34×40)=1360，阶梯恰好 50
        assertEquals(1360, CdgBoostMath.rampTicksFor(50.0F));
        assertEquals(50.0F, stepped(1360), 0.0001F);
    }

    @Test
    void doesNotTriggerWhenConsumedOrNotAheadOrBelowIgnition() {
        // 已消费不触发
        assertFalse(CdgBoostMath.shouldBoost(true, 76.8F, 18.0F));
        // 网络 ≤ 自身（目标 == 当前阶梯，不超前）不触发
        assertFalse(CdgBoostMath.shouldBoost(false, 18.0F, 18.0F));
        // 对齐目标 ≤ 当前 rampTarget 不触发
        assertFalse(CdgBoostMath.shouldBoost(false, 20.0F, 22.0F));
        // 目标 ≤ 起步 16 不触发
        assertFalse(CdgBoostMath.shouldBoost(false, 16.0F, 16.0F));
        assertFalse(CdgBoostMath.shouldBoost(false, 12.0F, 16.0F));
        // 正常触发：目标超前当前阶梯且 > 16
        assertTrue(CdgBoostMath.shouldBoost(false, 76.8F, 18.0F));
    }

    @Test
    void rampContinuesFromBoostedStepToRated() {
        // 触发后阶梯从 76（76.8 向下取精档）续爬：每 80gt +2rpm，到额定 96 共 768 tick
        int ticks = CdgBoostMath.rampTicksFor(CdgBoostMath.boostTarget(128.0F, 96.0F));
        assertEquals(76.0F, stepped(ticks), 0.0001F);
        int toRated = 0;
        while (stepped(ticks + toRated) < 96.0F) {
            toRated++;
        }
        assertEquals(768, toRated); // 2432→3200：76→96 共 9.6 个 80gt 步（向上取整到 tick）
        assertEquals(96.0F, stepped(ticks + toRated), 0.0001F);
    }
}
