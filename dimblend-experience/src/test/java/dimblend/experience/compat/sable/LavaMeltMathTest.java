package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** G6 岩浆熔毁速率（见 {@link LavaMeltMath}）：4 倍徒手速度、硬度负免疫、硬度 0 即熔。 */
class LavaMeltMathTest {

    @Test
    void meltIsFourTimesHandSpeed() {
        // 用户拍板例：徒手 16 秒（320 tick）的方块，岩浆浇 4 秒（80 tick）熔毁
        // 需正确工具的方块徒手术 divisor=100：h=3.2 → 徒手 320 tick → 熔毁 80 tick
        // （容差按 float 硬度值的精度量级放宽）
        assertEquals(80.0D, LavaMeltMath.ticksToMelt(3.2F, true), 1.0E-3D);
        // 任意硬度同比例：熔毁耗时恒为徒手 1/4（无需工具 divisor=30）
        assertEquals(3.2F * 100.0D / 4.0D, LavaMeltMath.ticksToMelt(3.2F, true), 1.0E-9D);
        assertEquals(0.5F * 30.0D / 4.0D, LavaMeltMath.ticksToMelt(0.5F, false), 1.0E-9D);
    }

    @Test
    void hardnessScalesMeltTime() {
        // 石头级（1.5，需工具）37.5 tick；黑曜石级（50）1250 tick ≈ 62.5 秒等效抗岩浆
        assertEquals(37.5D, LavaMeltMath.ticksToMelt(1.5F, true), 1.0E-3D);
        assertEquals(1250.0D, LavaMeltMath.ticksToMelt(50.0F, true), 1.0E-3D);
        // 速率与耗时互为倒数
        assertEquals(1.0D, LavaMeltMath.perTickProgress(1.5F, true)
                * LavaMeltMath.ticksToMelt(1.5F, true), 1.0E-9D);
    }

    @Test
    void negativeHardnessIsImmune() {
        assertFalse(LavaMeltMath.meltable(-1.0F));
        assertEquals(0.0D, LavaMeltMath.perTickProgress(-1.0F, false));
        assertEquals(Double.POSITIVE_INFINITY, LavaMeltMath.ticksToMelt(-1.0F, false));
    }

    @Test
    void zeroHardnessMeltsInstantly() {
        assertTrue(LavaMeltMath.meltable(0.0F));
        assertEquals(Double.POSITIVE_INFINITY, LavaMeltMath.perTickProgress(0.0F, false));
        assertEquals(0.0D, LavaMeltMath.ticksToMelt(0.0F, false));
    }
}
