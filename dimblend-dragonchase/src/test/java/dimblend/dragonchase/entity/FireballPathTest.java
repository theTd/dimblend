package dimblend.dragonchase.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** §3.4: the fireball flies a straight line from the mouth and lands exactly on the target point. */
class FireballPathTest {
    private static final Vec3 LAUNCH = new Vec3(1536.0D, 96.0D, 0.0D);
    private static final Vec3 TARGET = new Vec3(1524.0D, 65.0D, 0.0D);
    private static final double EPSILON = 1.0E-9D;

    private static void assertPosition(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    @Test
    void startsAtTheLaunchPointAndEndsExactlyOnTheTarget() {
        assertPosition(LAUNCH, FireballPath.positionAt(LAUNCH, TARGET, 0, 20));
        assertPosition(TARGET, FireballPath.positionAt(LAUNCH, TARGET, 20, 20));
    }

    @Test
    void keepsTheLineBetweenTheTwoPoints() {
        assertPosition(new Vec3(1530.0D, 80.5D, 0.0D), FireballPath.positionAt(LAUNCH, TARGET, 10, 20));
    }

    @Test
    void neverOvershootsAfterArrival() {
        assertPosition(TARGET, FireballPath.positionAt(LAUNCH, TARGET, 40, 20));
        assertTrue(FireballPath.arrived(20, 20));
        assertTrue(FireballPath.arrived(40, 20));
        assertFalse(FireballPath.arrived(19, 20));
    }

    @Test
    void aMissingOrZeroTravelTimeIsOneTick() {
        assertPosition(TARGET, FireballPath.positionAt(LAUNCH, TARGET, 1, 0));
        assertPosition(LAUNCH, FireballPath.positionAt(LAUNCH, TARGET, 0, 0));
        assertTrue(FireballPath.arrived(1, 0));
    }

    @Test
    void negativeElapsedNeverGoesBehindTheLaunchPoint() {
        assertPosition(LAUNCH, FireballPath.positionAt(LAUNCH, TARGET, -5, 20));
    }
}
