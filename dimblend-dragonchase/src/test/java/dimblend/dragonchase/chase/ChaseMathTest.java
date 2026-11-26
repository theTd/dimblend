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
    void nestGrowsHalfAMetrePerSecondEveryDefaultInterval() {
        assertEquals(0.5D, ChaseMath.MIN_SPEED_GROWTH_STEP, 0.0D);
        // requirements.md §3.1 after item 17: the interval defaults to 1024 blocks (256-4096 tunable).
        assertEquals(10.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 1023.9D, 1024.0D), 1.0E-9D);
        assertEquals(10.5D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 1024.0D, 1024.0D), 1.0E-9D);
        assertEquals(11.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 2048.0D, 1024.0D), 1.0E-9D);
        assertEquals(12.0D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 4096.0D, 1024.0D), 1.0E-9D);
        // The smallest tunable interval reaches the same 0.5 m/s step every 256 blocks.
        assertEquals(10.5D, ChaseMath.minSpeed(32.0D, 1, 1.0D, 256.0D, 256.0D), 1.0E-9D);
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
        assertEquals(128.0D, ChaseMath.CHASE_START_DISTANCE, 0.0D,
                "the chase still starts at 128 blocks, independently of the keep-away distance");
        assertEquals(96.0D, ChaseMath.DEFAULT_KEEP_AWAY_DISTANCE, 0.0D);

        // Default 96: desired gap is 96 + 32 = 128.
        // Gap 200 is beyond it: the nest only holds its minimum speed.
        assertEquals(10.0D, ChaseMath.nestTargetSpeed(10.0D, 96.0D, 1200.0D, 1000.0D, 8.0D), 1.0E-9D);
        // Gap 100: leader 10 + 0.5 * (128 - 100) = 24.
        assertEquals(24.0D, ChaseMath.nestTargetSpeed(10.0D, 96.0D, 1100.0D, 1000.0D, 10.0D), 1.0E-9D);
        // A fast leader with a shrinking gap would ask for 50 + 0.5 * 68 = 84, capped at 50 + 24.
        assertEquals(74.0D, ChaseMath.nestTargetSpeed(10.0D, 96.0D, 1060.0D, 1000.0D, 50.0D), 1.0E-9D);
        // The formula can ask for less than the minimum; the minimum wins.
        assertEquals(30.0D, ChaseMath.nestTargetSpeed(30.0D, 96.0D, 1120.0D, 1000.0D, 0.0D), 1.0E-9D);
        // A gap that has already collapsed is the strongest correction, capped and still floored.
        assertEquals(34.0D, ChaseMath.nestTargetSpeed(10.0D, 96.0D, 1000.0D, 1000.0D, 10.0D), 1.0E-9D);
    }

    @Test
    void keepAwayHonoursTheTunedDistanceAcrossItsWholeRange() {
        // Smallest tunable gap (32, desired 64): a 100-block gap is already comfortable.
        assertEquals(10.0D, ChaseMath.nestTargetSpeed(10.0D, 32.0D, 1100.0D, 1000.0D, 10.0D), 1.0E-9D);
        // Gap 40 misses 24 of the 64 desired: 18 + 0.5 * 24 = 30.
        assertEquals(30.0D, ChaseMath.nestTargetSpeed(10.0D, 32.0D, 1040.0D, 1000.0D, 18.0D), 1.0E-9D);

        // Largest tunable gap (256, desired 288): at the 128-block start gap the raw gain would ask
        // for +80 m/s; the cap keeps it at leader + 24, far below the 64 m/s speed cap.
        double target = ChaseMath.nestTargetSpeed(10.0D, 256.0D, 1128.0D, 1000.0D, 10.0D);
        assertEquals(10.0D + ChaseMath.KEEP_AWAY_MAX_BOOST, target, 1.0E-9D);
        assertEquals(24.0D, ChaseMath.KEEP_AWAY_MAX_BOOST, 0.0D);
        assertTrue(target < 64.0D, "a 256-block keep-away distance must never exceed the speed cap");

        // Gap 250 still misses 38 of the 288 desired: 16 + 0.5 * 38 = 35, below the cap.
        assertEquals(35.0D, ChaseMath.nestTargetSpeed(10.0D, 256.0D, 1250.0D, 1000.0D, 16.0D), 1.0E-9D);
    }

    @Test
    void topUpNeverDecelerates() {
        assertEquals(1.5D, ChaseMath.topUpDelta(0.5D, 2.0D), 1.0E-9D);
        assertEquals(0.0D, ChaseMath.topUpDelta(2.0D, 2.0D), 1.0E-9D);
        assertEquals(0.0D, ChaseMath.topUpDelta(3.0D, 2.0D), 1.0E-9D);
    }

    @Test
    void speedFloorNeedsACrewThatIsEntirelyAboard() {
        // Nobody online is not "everybody aboard": the floor stays off.
        assertFalse(ChaseMath.speedFloorAllowed(0, 0), "an empty server must not get the floor");

        // One non-spectator: the floor is offered only while that player is on a sub-level.
        assertTrue(ChaseMath.speedFloorAllowed(1, 1));
        assertFalse(ChaseMath.speedFloorAllowed(1, 0), "a lone player flying after the dragon holds the floor off");

        // Several non-spectators: all of them, not just one, must be aboard.
        assertTrue(ChaseMath.speedFloorAllowed(2, 2));
        assertTrue(ChaseMath.speedFloorAllowed(4, 4));
        assertFalse(ChaseMath.speedFloorAllowed(2, 1), "one player fell off the car: the train waits");
        assertFalse(ChaseMath.speedFloorAllowed(4, 3));
        assertFalse(ChaseMath.speedFloorAllowed(4, 0));

        // A count above the eligible total cannot happen, and never unlocks the floor.
        assertFalse(ChaseMath.speedFloorAllowed(2, 3));
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
