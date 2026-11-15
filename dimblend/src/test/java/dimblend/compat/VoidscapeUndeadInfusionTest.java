package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoidscapeUndeadInfusionTest {
    @Test
    void bonusEssenceUsesTenPercentThreshold() {
        assertTrue(VoidscapeUndeadInfusion.shouldDropBonusEssence(0.0F));
        assertTrue(VoidscapeUndeadInfusion.shouldDropBonusEssence(0.09999F));
        assertFalse(VoidscapeUndeadInfusion.shouldDropBonusEssence(0.10F));
        assertFalse(VoidscapeUndeadInfusion.shouldDropBonusEssence(0.5F));
    }

    @Test
    void remoteInfusionStaysStrictlyBelowLethalCap() {
        assertEquals(500.0F, VoidscapeUndeadInfusion.clampRemoteInfusion(500.0F));
        assertEquals(590.0F, VoidscapeUndeadInfusion.clampRemoteInfusion(600.0F));
        assertTrue(VoidscapeUndeadInfusion.MAX_SAFE_REMOTE_INFUSION < 600.0F);
    }
}
