package dimblend.dragonchase.chase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The director's arithmetic. Every rule here can be checked without a world, which is why the
 * numbers live in {@link ChaseMath} and not in the tick loop.
 */
class ChaseMathTest {
    @Test
    void minSpeedStartsAtBaseAndGrowsOncePerFullInterval() {
        assertEquals(10.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 0.0D, 512.0D), 1.0E-9D);
        assertEquals(10.5D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 512.0D, 512.0D), 1.0E-9D);
        assertEquals(11.5D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 1536.0D, 512.0D), 1.0E-9D);
        // Half an interval early earns nothing: the growth term floors, it does not interpolate.
        assertEquals(10.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 511.9D, 512.0D), 1.0E-9D);
        // Distance travelled can go backwards for a tick; it never reduces the floor below the base.
        assertEquals(10.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, -900.0D, 512.0D), 1.0E-9D);
    }

    @Test
    void minSpeedAppliesTheGenerationStep() {
        // Generation 4 at default step 1: 10 + 3 * 1.
        assertEquals(13.0D, ChaseMath.minSpeed(32.0D, 4, 1.0D, 0.0D, 512.0D), 1.0E-9D);
        // Generation 3 with step 3.5: 10 + 2 * 3.5.
        assertEquals(17.0D, ChaseMath.minSpeed(64.0D, 3, 3.5D, 0.0D, 512.0D), 1.0E-9D);
        // Generation 1 ignores the step entirely.
        assertEquals(10.0D, ChaseMath.minSpeed(64.0D, 1, 8.0D, 0.0D, 512.0D), 1.0E-9D);
    }

    @Test
    void minSpeedIsCapped() {
        assertEquals(32.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 1_000_000.0D, 512.0D), 1.0E-9D);
        // Generation 10 at step 1 asks for 19 but the cap is 16.
        assertEquals(16.0D, ChaseMath.minSpeed(16.0D, 10, 1.0D, 0.0D, 512.0D), 1.0E-9D);
        // A zero growth interval is a misconfiguration, not an infinity.
        assertEquals(20.0D, ChaseMath.minSpeed(20.0D, 2, 1.0D, 5000.0D, 0.0D), 1.0E-9D);
    }

    @Test
    void maxHealthUsesTheOnlineCountFixedAtSpawn() {
        assertEquals(400.0F, ChaseMath.maxHealth(1, 1.0D), 1.0E-4F);
        assertEquals(800.0F, ChaseMath.maxHealth(3, 1.0D), 1.0E-4F);
        assertEquals(300.0F, ChaseMath.maxHealth(2, 0.5D), 1.0E-4F);
        assertEquals(2400.0F, ChaseMath.maxHealth(11, 1.0D), 1.0E-4F);
        // An empty server must not produce a health pool below the one-player value.
        assertEquals(400.0F, ChaseMath.maxHealth(0, 1.0D), 1.0E-4F);
    }

    @Test
    void bossBarGenerationIsRomanToSixtyThreeThenArabic() {
        assertEquals("I", ChaseMath.romanOrArabic(1));
        assertEquals("IV", ChaseMath.romanOrArabic(4));
        assertEquals("IX", ChaseMath.romanOrArabic(9));
        assertEquals("XIV", ChaseMath.romanOrArabic(14));
        assertEquals("XL", ChaseMath.romanOrArabic(40));
        assertEquals("LXIII", ChaseMath.romanOrArabic(63));
        assertEquals("64", ChaseMath.romanOrArabic(64));
        assertEquals("128", ChaseMath.romanOrArabic(128));
    }

    @Test
    void eachHealthThresholdFiresExactlyOnce() {
        boolean[] fired = new boolean[ChaseMath.HEALTH_THRESHOLDS.length];
        assertEquals(0, ChaseMath.firstUnfiredThreshold(0.59D, fired));
        fired[0] = true;
        // Still above 40 %, so the same fraction reports nothing new.
        assertEquals(-1, ChaseMath.firstUnfiredThreshold(0.59D, fired));
        assertEquals(1, ChaseMath.firstUnfiredThreshold(0.39D, fired));
        fired[1] = true;
        assertEquals(2, ChaseMath.firstUnfiredThreshold(0.24D, fired));
        fired[2] = true;
        assertEquals(3, ChaseMath.firstUnfiredThreshold(0.09D, fired));
        fired[3] = true;
        assertEquals(-1, ChaseMath.firstUnfiredThreshold(0.0D, fired));
        // Exactly at the threshold is not "below" it.
        assertEquals(-1, ChaseMath.firstUnfiredThreshold(0.60D, new boolean[ChaseMath.HEALTH_THRESHOLDS.length]));
    }

    @Test
    void damageAccumulatesOutsideTheCooldownOnly() {
        double accumulated = 0.0D;
        accumulated = ChaseMath.accumulateDamage(accumulated, 12.0F, 100L, 0L);
        accumulated = ChaseMath.accumulateDamage(accumulated, 10.0F, 110L, 0L);
        assertEquals(22.0D, accumulated, 1.0E-9D);

        // Fireball fired at tick 120, cooldown until 280: these hits are discarded, not banked.
        accumulated = ChaseMath.accumulateDamage(accumulated, 40.0F, 130L, 280L);
        accumulated = ChaseMath.accumulateDamage(accumulated, 40.0F, 279L, 280L);
        assertEquals(22.0D, accumulated, 1.0E-9D);

        // The first hit at or after the deadline counts again.
        accumulated = ChaseMath.accumulateDamage(accumulated, 10.0F, 280L, 280L);
        assertEquals(32.0D, accumulated, 1.0E-9D);
        assertTrue(ChaseMath.fireballReady(accumulated, 32.0D));
        assertFalse(ChaseMath.fireballReady(31.9D, 32.0D));
    }

    @Test
    void keepAwayClosesTheGapAndNeverDropsBelowTheMinimum() {
        // Gap 200 is beyond 128 + 32: the nest only holds its minimum speed.
        assertEquals(10.0D, ChaseMath.nestTargetSpeed(10.0D, 1200.0D, 1000.0D, 8.0D), 1.0E-9D);
        // Gap 100: leader 10 + 0.5 * (160 - 100) = 40.
        assertEquals(40.0D, ChaseMath.nestTargetSpeed(10.0D, 1100.0D, 1000.0D, 10.0D), 1.0E-9D);
        // A fast leader with a shrinking gap: 50 + 0.5 * 60 = 80.
        assertEquals(80.0D, ChaseMath.nestTargetSpeed(10.0D, 1100.0D, 1000.0D, 50.0D), 1.0E-9D);
        // The formula can ask for less than the minimum; the minimum wins.
        assertEquals(25.0D, ChaseMath.nestTargetSpeed(25.0D, 1150.0D, 1000.0D, 0.0D), 1.0E-9D);
        // A gap that has already collapsed is the strongest correction, still floored.
        assertEquals(90.0D, ChaseMath.nestTargetSpeed(10.0D, 1000.0D, 1000.0D, 10.0D), 1.0E-9D);
    }

    @Test
    void topUpNeverDecelerates() {
        assertEquals(1.5D, ChaseMath.topUpDelta(0.5D, 2.0D), 1.0E-9D);
        assertEquals(0.0D, ChaseMath.topUpDelta(2.0D, 2.0D), 1.0E-9D);
        assertEquals(0.0D, ChaseMath.topUpDelta(3.0D, 2.0D), 1.0E-9D);
    }

    @Test
    void regionAndLocalMathUsesFloorDivision() {
        int band = 4096;
        assertEquals(0, ChaseMath.regionOf(1000.0D, band));
        assertEquals(1000.0D, ChaseMath.localX(1000.0D, band), 1.0E-9D);
        assertEquals(1, ChaseMath.regionOf(4096.0D, band));
        assertEquals(0.0D, ChaseMath.localX(4096.0D, band), 1.0E-9D);
        assertEquals(1, ChaseMath.regionOf(4608.0D, band));
        assertEquals(512.0D, ChaseMath.localX(4608.0D, band), 1.0E-9D);
        assertEquals(32, ChaseMath.regionOf(32.0D * band + 256.0D, band));
        assertEquals(256.0D, ChaseMath.localX(32.0D * band + 256.0D, band), 1.0E-9D);
        // Half-open boundary: the boundary block belongs to the outer region.
        assertEquals(2, ChaseMath.regionOf(2.0D * band, band));
        assertEquals(band - 1, (int) ChaseMath.localX(2.0D * band - 1.0D, band));
    }

    @Test
    void prologueTriggersAtRegion32Local256() {
        assertFalse(ChaseMath.prologueThresholdReached(32, 255.9D));
        assertTrue(ChaseMath.prologueThresholdReached(32, 256.0D));
        assertFalse(ChaseMath.prologueThresholdReached(31, 4000.0D));
        // Already past the scripted region: still one prologue per world.
        assertTrue(ChaseMath.prologueThresholdReached(33, 10.0D));
    }

    @Test
    void nestNeedsAnIsolatedEndRegionAtOrPastThirtyFour() {
        assertTrue(ChaseMath.nestRegionEligible(34, "end", "surface", "nether", false, false));
        assertTrue(ChaseMath.nestRegionEligible(50, "end", "nether", "twilight", false, false));
        // Neighbours must not be End on either side.
        assertFalse(ChaseMath.nestRegionEligible(34, "end", "end", "nether", false, false));
        assertFalse(ChaseMath.nestRegionEligible(34, "end", "surface", "end", false, false));
        // The region itself must be End.
        assertFalse(ChaseMath.nestRegionEligible(34, "nether", "surface", "end", false, false));
        // Region 33 is fixed Voidscape and region 32's neighbour is Voidscape: neither qualifies.
        assertFalse(ChaseMath.nestRegionEligible(33, "end", "surface", "nether", false, false));
        assertFalse(ChaseMath.nestRegionEligible(32, "end", "aether", "voidscape", false, false));
        // One chase at a time; a used region stays used.
        assertFalse(ChaseMath.nestRegionEligible(40, "end", "surface", "nether", true, false));
        assertFalse(ChaseMath.nestRegionEligible(40, "end", "surface", "nether", false, true));
    }

    @Test
    void corridorIsPositiveSideAndSixteenBlocksWide() {
        assertTrue(ChaseMath.onCorridor(100.0D, 0.0D));
        assertTrue(ChaseMath.onCorridor(100.0D, -16.0D));
        assertTrue(ChaseMath.onCorridor(100.0D, 16.0D));
        assertFalse(ChaseMath.onCorridor(100.0D, 16.5D));
        // Negative side never gets a prologue or a nest (§2.1).
        assertFalse(ChaseMath.onCorridor(-100.0D, 0.0D));
    }

    @Test
    void radiusChecksAreInclusive() {
        assertTrue(ChaseMath.withinRadius(512.0D, 0.0D, 0.0D, 0.0D, 512.0D));
        assertTrue(ChaseMath.withinRadius(0.0D, 0.0D, 0.0D, 0.0D, 512.0D));
        assertFalse(ChaseMath.withinRadius(512.5D, 0.0D, 0.0D, 0.0D, 512.0D));
        // Both axes matter, not just X.
        assertFalse(ChaseMath.withinRadius(300.0D, 450.0D, 0.0D, 0.0D, 512.0D));
    }

    @Test
    void bossBarNumbersUseOneDecimal() {
        assertEquals("12.3", ChaseMath.tenths(12.34D));
        assertEquals("10.0", ChaseMath.tenths(10.0D));
    }
}
