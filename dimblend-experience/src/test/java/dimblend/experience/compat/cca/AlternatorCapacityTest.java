package dimblend.experience.compat.cca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dimblend.experience.Config;

/**
 * D9 交流发电机储能 500K + 存量流失判据（2026-11-11 用户需求）的纯规则自检。
 *
 * <p>这里钉的是三件事，都是运行期看不出来的口径：
 * ① 配置键默认 500000（500K），且与 CCA 原生 5000 明确区分；
 * ② 存量流失判据在「锁存中」与「零转速」两种情况下都为真（需求要求两段都扣）；
 * ③ 每 tick 扣减量是 250 FE（= 每秒 5000），与 D5 原有量一致，不因新增过载路径翻倍。
 *
 * <p>容量真正生效要看 mixin 是否作用到 CCA 的构造器（{@code AlternatorCapacityMixin}）
 * 与读档钳定——那需要 CCA 进 dev 运行的 GameTest / 实机，本类只锁纯数值与纯判据。</p>
 */
class AlternatorCapacityTest {

    /** D9：配置默认 500000 FE，而不是 CCA 的 generator_capacity 5000。 */
    @Test
    void configuredCapacityDefaultsToFiveHundredThousandFe() {
        assertEquals(500_000, Config.ALTERNATOR_CAPACITY_FE.getDefault(),
                "alternatorCapacityFe 默认必须是 500000（500K FE）");
        assertNotEquals(5_000, Config.ALTERNATOR_CAPACITY_FE.getDefault(),
                "5000 是 CCA 原生的 generator_capacity，需求明确要抬到 500K");
    }

    /** 需求「过载期间扣存量」：锁存中就扣，与转速/产能门无关。 */
    @Test
    void latchedAlternatorAlwaysDrainsStoredEnergy() {
        assertTrue(AlternatorOverstressLatch.drainsStoredEnergy(true, true),
                "锁存期即便 getSpeed() 回到理论转速（产能门为真）也必须扣存量");
        assertTrue(AlternatorOverstressLatch.drainsStoredEnergy(true, false),
                "锁存且不产电同样扣（不得重复扣，由唯一扣减点保证）");
    }

    /** 需求「零转速期间扣存量」：D5 原口径不得回退。 */
    @Test
    void zeroRpmStillDrainsAndProducingStillDoesNot() {
        assertTrue(AlternatorOverstressLatch.drainsStoredEnergy(false, false),
                "未锁存但本 tick 不产电（零转速 / 冻结读数 / 最低转速门未满足）必须继续扣");
        assertFalse(AlternatorOverstressLatch.drainsStoredEnergy(false, true),
                "正常产电且未锁存时不扣——有输入也漏电是 2026-09-25 已经被否掉的口径");
    }

    /** 扣减量与 D5 一致：250 FE/t = 5000 FE/s，且只有一份。 */
    @Test
    void drainPerTickMatchesFiveThousandPerSecond() {
        assertEquals(250, AlternatorOverstressLatch.STASH_DRAIN_PER_TICK);
        assertEquals(5_000, AlternatorOverstressLatch.STASH_DRAIN_PER_TICK * 20,
                "每 tick 250 FE × 20 tick 必须正好是需求里的每秒 5000 FE");
        // 锁存不另设扣减量：任何组合下都只走这一条路径
        assertTrue(AlternatorOverstressLatch.drainsStoredEnergy(true, false)
                        && AlternatorOverstressLatch.drainsStoredEnergy(true, true),
                "锁存期两条产电路径都并入同一判据，避免重复扣减");
    }

    /** 500K 容量在 5000 FE/t 的输出上限下不会被"一 tick 放空"的旧口径假设锁死。 */
    @Test
    void capacityIsNotDrainedInASingleTickAtAnythingUnderMaxOutput() {
        int capacity = Config.ALTERNATOR_CAPACITY_FE.getDefault();
        int maxOutput = 5_000; // CCA generator_max_output 默认，本需求不动
        assertTrue(capacity > maxOutput,
                "500K buffer 远大于 5000 FE/t 输出，锁存期存量不再一 tick 放空（旧 5000 FE 口径）");
        assertTrue(capacity >= 100 * maxOutput,
                "500K 至少是输出上限的 100 倍，才谈得上'储能'而不是瞬态 buffer");
    }
}