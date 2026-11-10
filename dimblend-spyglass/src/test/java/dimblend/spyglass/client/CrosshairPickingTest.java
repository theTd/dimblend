package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CrosshairPickingTest {
    private static final double TOLERANCE = 4.0;
    private static final double[] NORMAL_HALF_WIDTH = {CrosshairPicking.HALF_BOX};

    @Test
    void aMarkCenteredOnTheScreenIsHit() {
        assertEquals(0, CrosshairPicking.hit(
                new double[] {100.0},
                new double[] {100.0},
                NORMAL_HALF_WIDTH,
                1,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void theBoxPlusToleranceIsStillAHit() {
        // 16 (half box) + 4 (tolerance) = 20; 19.9 is inside.
        assertEquals(0, CrosshairPicking.hit(
                new double[] {119.9},
                new double[] {100.0},
                NORMAL_HALF_WIDTH,
                1,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void justPastTheLimitIsNotAHit() {
        assertEquals(-1, CrosshairPicking.hit(
                new double[] {120.1},
                new double[] {100.0},
                NORMAL_HALF_WIDTH,
                1,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void theSelectedBracketedMarkHasAWiderBox() {
        // 22 (half width of a 44 px selected box) + 4 = 26; the same 21 px offset misses a plain box.
        assertEquals(0, CrosshairPicking.hit(
                new double[] {121.0},
                new double[] {100.0},
                new double[] {22.0},
                1,
                100.0,
                100.0,
                TOLERANCE));
        assertEquals(-1, CrosshairPicking.hit(
                new double[] {121.0},
                new double[] {100.0},
                NORMAL_HALF_WIDTH,
                1,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void theBoxIsNotRadialSoACornerInsideItIsAHit() {
        // 19 pixels off on both axes: distance 26.9, but inside the 20-pixel box plus tolerance.
        assertEquals(0, CrosshairPicking.hit(
                new double[] {119.0},
                new double[] {119.0},
                NORMAL_HALF_WIDTH,
                1,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void theMarkNearerToTheCenterWins() {
        // Both are within the box; the second is closer to the screen center.
        assertEquals(1, CrosshairPicking.hit(
                new double[] {110.0, 102.0},
                new double[] {100.0, 100.0},
                new double[] {16.0, 16.0},
                2,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void aTieKeepsTheEarlierInput() {
        assertEquals(0, CrosshairPicking.hit(
                new double[] {105.0, 95.0},
                new double[] {100.0, 100.0},
                new double[] {16.0, 16.0},
                2,
                100.0,
                100.0,
                TOLERANCE));
    }

    @Test
    void nothingInRangeReturnsMinusOne() {
        assertEquals(-1, CrosshairPicking.hit(
                new double[] {140.0},
                new double[] {140.0},
                NORMAL_HALF_WIDTH,
                1,
                100.0,
                100.0,
                TOLERANCE));
        assertEquals(-1, CrosshairPicking.hit(
                new double[0], new double[0], new double[0], 0, 100.0, 100.0, TOLERANCE));
    }
}
