package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** B6 读档宽限（见 {@link CdgOverloadMath#isWithinLoadGrace}）：开服前 N tick 内不判爆（纯函数，零依赖）。 */
class CdgLoadGraceTest {

    private static final int FIVE_SECONDS = 5 * 20;

    @Test
    void firstFiveSecondsAreInsideGrace() {
        assertTrue(CdgOverloadMath.isWithinLoadGrace(0, FIVE_SECONDS));
        assertTrue(CdgOverloadMath.isWithinLoadGrace(FIVE_SECONDS - 1, FIVE_SECONDS));
    }

    @Test
    void graceEndsExactlyAtFiveSeconds() {
        assertFalse(CdgOverloadMath.isWithinLoadGrace(FIVE_SECONDS, FIVE_SECONDS));
        assertFalse(CdgOverloadMath.isWithinLoadGrace(100_000, FIVE_SECONDS));
    }

    @Test
    void zeroOrNegativeGraceDisablesIt() {
        assertFalse(CdgOverloadMath.isWithinLoadGrace(0, 0));
        assertFalse(CdgOverloadMath.isWithinLoadGrace(0, -20));
    }
}
