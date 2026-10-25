package dimblend.experience.compat.create;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N6 分液池下界蒸发的单拍扣量（纯函数，不启动游戏）：默认 50 mB/s 每 10 tick 恰扣
 * 25 mB；非 2 倍数速率靠余数跨拍累加，长期平均等于配置值；0 不蒸发。
 */
class ItemDrainNetherEvaporationTest {

    private static final int BEAT = ItemDrainNetherEvaporation.TICKS_PER_BEAT;

    @Test
    void defaultRateDrains25MbPer10Ticks() {
        ItemDrainNetherEvaporation.Charge charge = ItemDrainNetherEvaporation.charge(0, 50, BEAT);
        assertEquals(25, charge.amountMb());
        assertEquals(0, charge.remainder());
    }

    @Test
    void defaultRateAverages50MbPerSecond() {
        int remainder = 0;
        int total = 0;
        // 一秒 = 20 tick = 2 拍
        for (int beat = 0; beat < 2; beat++) {
            ItemDrainNetherEvaporation.Charge charge = ItemDrainNetherEvaporation.charge(remainder, 50, BEAT);
            total += charge.amountMb();
            remainder = charge.remainder();
        }
        assertEquals(50, total);
        assertEquals(0, remainder);
    }

    @Test
    void oddRateKeepsExactLongRunAverage() {
        // 1 mB/s：单拍 0.5 mB，向下取整会让每拍都是 0；余数累加应为 0,1,0,1...
        int remainder = 0;
        int total = 0;
        for (int beat = 0; beat < 40; beat++) {
            ItemDrainNetherEvaporation.Charge charge = ItemDrainNetherEvaporation.charge(remainder, 1, BEAT);
            total += charge.amountMb();
            remainder = charge.remainder();
        }
        // 40 拍 = 20 秒 = 20 mB
        assertEquals(20, total);
    }

    @Test
    void carriesFractionAcrossBeats() {
        ItemDrainNetherEvaporation.Charge first = ItemDrainNetherEvaporation.charge(0, 3, BEAT);
        // 3 mB/s = 1.5 mB/拍：首拍 1，余 10/20
        assertEquals(1, first.amountMb());
        assertEquals(10, first.remainder());
        ItemDrainNetherEvaporation.Charge second = ItemDrainNetherEvaporation.charge(first.remainder(), 3, BEAT);
        assertEquals(2, second.amountMb());
        assertEquals(0, second.remainder());
    }

    @Test
    void zeroRateNeverDrains() {
        ItemDrainNetherEvaporation.Charge charge = ItemDrainNetherEvaporation.charge(0, 0, BEAT);
        assertEquals(0, charge.amountMb());
        assertEquals(0, charge.remainder());
    }

    @Test
    void maximumRateFitsOneBeat() {
        // 1500 mB/s = 750 mB/拍，不超过 1500 mB 水箱
        ItemDrainNetherEvaporation.Charge charge = ItemDrainNetherEvaporation.charge(0, 1500, BEAT);
        assertEquals(750, charge.amountMb());
        assertEquals(0, charge.remainder());
    }

    @Test
    void invalidTickCadenceIsInert() {
        assertEquals(0, ItemDrainNetherEvaporation.charge(0, 50, 0).amountMb());
        assertEquals(0, ItemDrainNetherEvaporation.charge(0, 50, -10).amountMb());
    }
}
