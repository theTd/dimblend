package dimblend.band;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link BandLaneSync#pushKey}: the "前方到达" push must re-send when an override is written or
 * cleared while the player is already in the previous region, so the override state is part of
 * the dedupe key (requirements §3.3/§4). Without it the HUD keeps "未知" next to a forced End.
 */
class BandLaneSyncPushKeyTest {
    @Test
    void sameInputsGiveTheSameKey() {
        assertEquals(BandLaneSync.pushKey(39, false, false, false), BandLaneSync.pushKey(39, false, false, false));
    }

    @Test
    void overrideOnTheOutwardRegionForcesAResend() {
        int plain = BandLaneSync.pushKey(39, false, false, false);
        assertNotEquals(plain, BandLaneSync.pushKey(39, false, false, true),
                "下一区被强制成末地时必须重发，「未知」→「末地」");
        assertNotEquals(plain, BandLaneSync.pushKey(39, false, true, false),
                "本区被强制时当前区域名也要重发");
    }

    @Test
    void creativeFlagIsStillSeparated() {
        assertNotEquals(BandLaneSync.pushKey(40, false, true, true), BandLaneSync.pushKey(40, true, true, true));
    }

    @Test
    void everyCombinationOfFlagsAndBothSidesHasItsOwnKey() {
        Set<Integer> keys = new HashSet<>();
        for (int region = -50; region <= 50; region++) {
            for (boolean creative : new boolean[] {false, true}) {
                for (boolean barrenHere : new boolean[] {false, true}) {
                    for (boolean barrenNext : new boolean[] {false, true}) {
                        assertTrue(keys.add(BandLaneSync.pushKey(region, creative, barrenHere, barrenNext)),
                                "collision at region " + region + " creative=" + creative
                                        + " here=" + barrenHere + " next=" + barrenNext);
                    }
                }
            }
        }
    }
}
