package dimblend.experience.compat.create.stockkeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 售货员玩偶吱声音高：{@code [0,1)} → {@code [0.75,1.25)} 的线性映射（纯函数）。 */
class DollSqueakPitchTest {

    @Test
    void lowerBoundIsThreeQuarters() {
        assertEquals(0.75F, DollStockKeeperCompat.computeSqueakPitch(0.0F));
    }

    @Test
    void midIsOne() {
        assertEquals(1.0F, DollStockKeeperCompat.computeSqueakPitch(0.5F));
    }

    @Test
    void upperBoundApproachesOneAndAQuarter() {
        assertEquals(1.25F, DollStockKeeperCompat.computeSqueakPitch(1.0F));
    }

    @Test
    void staysInsideTheLegalPitchRangeForEveryRandomUnit() {
        float[] samples = {0.0F, 0.01F, 0.25F, 0.5F, 0.75F, 0.99F, 1.0F};
        for (float unit : samples) {
            float pitch = DollStockKeeperCompat.computeSqueakPitch(unit);
            assertTrue(pitch >= 0.75F && pitch <= 1.25F, "pitch out of range for unit=" + unit);
        }
    }
}
