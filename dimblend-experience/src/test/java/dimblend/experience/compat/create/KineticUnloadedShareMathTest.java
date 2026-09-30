package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 未加载份额扣账口径（见 {@link KineticUnloadedShareMath}）：与 Create addSilently 同口径。 */
class KineticUnloadedShareMathTest {

    @Test
    void sharesUseAbsoluteSpeed() {
        assertEquals(64.0F, KineticUnloadedShareMath.stressShare(4.0F, -16.0F));
        assertEquals(64.0F, KineticUnloadedShareMath.stressShare(4.0F, 16.0F));
        assertEquals(512.0F, KineticUnloadedShareMath.capacityShare(32.0F, -16.0F));
        assertEquals(0.0F, KineticUnloadedShareMath.capacityShare(32.0F, 0.0F));
    }

    @Test
    void releaseClampsAtZero() {
        assertEquals(36.0F, KineticUnloadedShareMath.released(100.0F, 64.0F));
        assertEquals(0.0F, KineticUnloadedShareMath.released(40.0F, 64.0F));
        assertEquals(0.0F, KineticUnloadedShareMath.released(0.0F, 0.0F));
    }

    @Test
    void releaseKeepsNaNLikeCreate() {
        // Create 的 "if (x < 0) x = 0" 对 NaN 不成立，NaN 原样保留
        assertTrue(Float.isNaN(KineticUnloadedShareMath.released(Float.NaN, 1.0F)));
    }

    @Test
    void memberCountFloorsAtZero() {
        assertEquals(2, KineticUnloadedShareMath.releasedMember(3));
        assertEquals(0, KineticUnloadedShareMath.releasedMember(1));
        assertEquals(0, KineticUnloadedShareMath.releasedMember(0));
    }
}
