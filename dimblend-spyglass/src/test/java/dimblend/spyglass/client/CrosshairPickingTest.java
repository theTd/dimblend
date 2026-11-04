package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CrosshairPickingTest {
    private static final double TOLERANCE = 4.0;

    @Test
    void aMarkCenteredOnTheScreenIsHit() {
        assertEquals(0, CrosshairPicking.hit(
                new double[] {100.0}, new double[] {100.0}, 1, 100.0, 100.0, TOLERANCE));
    }

    @Test
    void theBoxPlusToleranceIsStillAHit() {
        // 16 (half box) + 4 (tolerance) = 20; 19.9 is inside.
        assertEquals(0, CrosshairPicking.hit(
                new double[] {119.9}, new double[] {100.0}, 1, 100.0, 100.0, TOLERANCE));
    }

    @Test
    void justPastTheLimitIsNotAHit() {
        assertEquals(-1, CrosshairPicking.hit(
                new double[] {120.1}, new double[] {100.0}, 1, 100.0, 100.0, TOLERANCE));
    }

    @Test
    void theMarkNearerToTheCenterWins() {
        // Both are within the box; the second is closer to the screen center.
        assertEquals(1, CrosshairPicking.hit(
                new double[] {110.0, 102.0}, new double[] {100.0, 100.0}, 2, 100.0, 100.0, TOLERANCE));
    }

    @Test
    void aTieKeepsTheEarlierInput() {
        assertEquals(0, CrosshairPicking.hit(
                new double[] {105.0, 95.0}, new double[] {100.0, 100.0}, 2, 100.0, 100.0, TOLERANCE));
    }

    @Test
    void nothingInRangeReturnsMinusOne() {
        assertEquals(-1, CrosshairPicking.hit(
                new double[] {140.0}, new double[] {140.0}, 1, 100.0, 100.0, TOLERANCE));
        assertEquals(-1, CrosshairPicking.hit(
                new double[0], new double[0], 0, 100.0, 100.0, TOLERANCE));
    }
}
