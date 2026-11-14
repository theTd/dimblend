package dimblend.dragonchase.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Flight maths of the prologue and chase modes (§3.2, §3.4). */
class ChaseDragonFlightTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void facingYawUsesTheVanillaDragonForwardAxis() {
        assertEquals(90.0F, ChaseDragonFlight.facingYaw(new Vec3(1.0D, 0.0D, 0.0D)), 1.0E-4F);
        assertEquals(-90.0F, ChaseDragonFlight.facingYaw(new Vec3(-1.0D, 0.0D, 0.0D)), 1.0E-4F);
        assertEquals(0.0F, ChaseDragonFlight.facingYaw(new Vec3(0.0D, 0.0D, -1.0D)), 1.0E-4F);
        assertEquals(180.0F, ChaseDragonFlight.facingYaw(new Vec3(0.0D, 0.0D, 1.0D)), 1.0E-4F);
        // Vertical-only directions keep a stable yaw.
        assertEquals(0.0F, ChaseDragonFlight.facingYaw(new Vec3(0.0D, 5.0D, 0.0D)), 1.0E-4F);
    }

    @Test
    void turnTowardHonoursTheLimitAndWrapsAroundZero() {
        assertEquals(4.0F, ChaseDragonFlight.turnToward(0.0F, 170.0F, 4.0F), 1.0E-4F);
        // Mth.wrapDegrees returns [-180, 180): 354 degrees is reported as -6.
        assertEquals(-6.0F, ChaseDragonFlight.turnToward(350.0F, 10.0F, 4.0F), 1.0E-4F);
        assertEquals(6.0F, ChaseDragonFlight.turnToward(10.0F, 5.0F, 4.0F), 1.0E-4F);
        assertEquals(90.0F, ChaseDragonFlight.turnToward(85.0F, 90.0F, 10.0F), 1.0E-4F);
    }

    @Test
    void desiredVelocityChasesTheOffsetAtFullSpeed() {
        Vec3 velocity = ChaseDragonFlight.desiredVelocity(new Vec3(1000.0D, 0.0D, 0.0D), Vec3.ZERO,
                ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED);
        assertEquals(ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, velocity.x, EPSILON);
        assertEquals(0.0D, velocity.y, EPSILON);
        assertEquals(0.0D, velocity.z, EPSILON);
    }

    @Test
    void desiredVelocityDoesNotOvershootSmallOffsets() {
        Vec3 velocity = ChaseDragonFlight.desiredVelocity(new Vec3(0.5D, 0.0D, 0.0D), Vec3.ZERO,
                ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED);
        assertEquals(0.5D, velocity.x, EPSILON);
    }

    @Test
    void desiredVelocityClampsVerticalAndAddsFeedForward() {
        Vec3 climb = ChaseDragonFlight.desiredVelocity(new Vec3(0.0D, 100.0D, 0.0D), Vec3.ZERO,
                ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED);
        assertEquals(ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED, climb.y, EPSILON);

        Vec3 follow = ChaseDragonFlight.desiredVelocity(Vec3.ZERO, new Vec3(3.2D, 0.5D, 0.0D),
                ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED);
        assertEquals(3.2D, follow.x, EPSILON);
        assertEquals(0.5D, follow.y, EPSILON);
    }

    @Test
    void desiredVelocityStaysUnderTheCapWithFeedForward() {
        Vec3 velocity = ChaseDragonFlight.desiredVelocity(new Vec3(
                ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, 0.0D, 0.0D), new Vec3(3.0D, 0.0D, 0.0D),
                ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED);
        assertEquals(ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z),
                EPSILON);
    }

    @Test
    void approachAcceleratesAndStopsOnTheDesiredVelocity() {
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < 40; tick++) {
            velocity = ChaseDragonFlight.approach(velocity, new Vec3(4.0D, 0.0D, 0.0D), 0.25D);
        }
        assertEquals(4.0D, velocity.x, EPSILON);
    }

    @Test
    void approachNeverOvershoots() {
        Vec3 velocity = ChaseDragonFlight.approach(Vec3.ZERO, new Vec3(4.0D, 0.0D, 0.0D), 10.0D);
        assertEquals(4.0D, velocity.x, EPSILON);
    }

    @Test
    void swayStaysInsideItsAmplitudeAndIsSmooth() {
        double amplitude = 32.0D;
        double maximum = 0.0D;
        for (int tick = 0; tick < 1000; tick++) {
            double sway = ChaseDragonFlight.swayZ(tick, 42L, amplitude);
            assertTrue(Math.abs(sway) <= amplitude + EPSILON, "sway out of range at tick " + tick + ": " + sway);
            maximum = Math.max(maximum, Math.abs(sway));
            double next = ChaseDragonFlight.swayZ(tick + 1, 42L, amplitude);
            assertTrue(Math.abs(next - sway) < amplitude * 0.05D, "sway jump at tick " + tick);
        }
        assertTrue(maximum > amplitude * 0.5D, "sway never uses its range: " + maximum);
    }

    @Test
    void swayIsDeterministicPerSeed() {
        assertEquals(ChaseDragonFlight.swayZ(123, 7L, 32.0D), ChaseDragonFlight.swayZ(123, 7L, 32.0D), EPSILON);
        assertNotEquals(ChaseDragonFlight.swayZ(123, 7L, 32.0D), ChaseDragonFlight.swayZ(123, 8L, 32.0D));
    }

    @Test
    void followAmplitudesMatchTheSpecNumbers() {
        // §3.4 / decision dragon-position: z +-32, y +-16 around nest + 32. Pinned here because the
        // bob was once 6 instead of 16 (inlined constants: a regression fails this assertion).
        assertEquals(32.0D, ChaseDragon.FOLLOW_SWAY_AMPLITUDE);
        assertEquals(16.0D, ChaseDragon.FOLLOW_BOB_AMPLITUDE);
        assertEquals(32.0D, ChaseDragon.FOLLOW_HEIGHT);
    }

    @Test
    void bobStaysInsideItsAmplitude() {
        for (int tick = 0; tick < 1000; tick++) {
            double bob = ChaseDragonFlight.bobY(tick, 9L, ChaseDragon.FOLLOW_BOB_AMPLITUDE);
            assertTrue(Math.abs(bob) <= ChaseDragon.FOLLOW_BOB_AMPLITUDE + EPSILON,
                    "bob out of range at tick " + tick + ": " + bob);
        }
    }

    @Test
    void breathSteeringKeepsAheadOfAFleeingNest() {
        // ChaseDragon.breathFollowSpeed() is private; pin the rule's inputs here and exercise the
        // rule through the shared steering maths in breathHoverCatchesAndRidesAFleeingNest.
        assertEquals(3.0D, ChaseDragon.BREATH_MIN_HOVER_SPEED);
        assertEquals(1.6D, ChaseDragon.BREATH_SPEED_MARGIN);
        assertEquals(1.4D, ChaseDragon.BREATH_MAX_VERTICAL_SPEED);
        assertEquals(0.35D, ChaseDragon.BREATH_ACCELERATION);
    }

    @Test
    void breathHoverCatchesAndRidesAFleeingNest() {
        // Pure horizontal copy of the breath steering: a nest at the chase maximum (64 m/s) flees
        // for the whole 3 s breath while the dragon keeps station above it. The dragon starts
        // 20 blocks behind and must both catch up and hold that station for the animation.
        double nestSpeed = 3.2D;
        double horizontalCap = Math.max(ChaseDragon.BREATH_MIN_HOVER_SPEED, nestSpeed + ChaseDragon.BREATH_SPEED_MARGIN);
        double nestX = 0.0D;
        Vec3 position = new Vec3(-20.0D, 0.0D, 0.0D);
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < 140; tick++) {
            Vec3 hover = new Vec3(nestX, 0.0D, 0.0D);
            Vec3 desired = ChaseDragonFlight.desiredVelocity(hover.subtract(position), new Vec3(nestSpeed, 0.0D, 0.0D),
                    horizontalCap, ChaseDragon.BREATH_MAX_VERTICAL_SPEED);
            velocity = ChaseDragonFlight.approach(velocity, desired, ChaseDragon.BREATH_ACCELERATION);
            position = position.add(velocity);
            nestX += nestSpeed;
        }
        assertEquals(0.0D, position.x - nestX, 1.0D, "the approach never caught the fleeing nest");

        for (int tick = 0; tick < 60; tick++) {
            Vec3 hover = new Vec3(nestX, 0.0D, 0.0D);
            Vec3 desired = ChaseDragonFlight.desiredVelocity(hover.subtract(position), new Vec3(nestSpeed, 0.0D, 0.0D),
                    horizontalCap, ChaseDragon.BREATH_MAX_VERTICAL_SPEED);
            velocity = ChaseDragonFlight.approach(velocity, desired, ChaseDragon.BREATH_ACCELERATION);
            position = position.add(velocity);
            nestX += nestSpeed;
        }
        assertEquals(0.0D, position.x - nestX, 1.0D, "the dragon fell behind during the 3 s breath");
    }

    @Test
    void orbitPointSitsOnTheCircle() {
        Vec3 center = new Vec3(10.0D, 0.0D, -20.0D);
        Vec3 atZero = ChaseDragonFlight.orbitPoint(center, 50.0D, 0.0D, 120.0D);
        assertEquals(60.0D, atZero.x, EPSILON);
        assertEquals(120.0D, atZero.y, EPSILON);
        assertEquals(-20.0D, atZero.z, EPSILON);

        Vec3 atQuarter = ChaseDragonFlight.orbitPoint(center, 50.0D, Math.PI / 2.0D, 120.0D);
        assertEquals(10.0D, atQuarter.x, 1.0E-9D);
        assertEquals(30.0D, atQuarter.z, 1.0E-9D);
        assertEquals(50.0D, Math.sqrt(
                (atQuarter.x - center.x) * (atQuarter.x - center.x) + (atQuarter.z - center.z) * (atQuarter.z - center.z)),
                EPSILON);
    }

    @Test
    void wrapAngleStaysInOneTurn() {
        assertTrue(ChaseDragonFlight.wrapAngle(-0.1D) > Math.PI * 2.0D - 0.2D);
        assertEquals(Math.PI, ChaseDragonFlight.wrapAngle(Math.PI * 7.0D), 1.0E-9D);
        assertEquals(0.0D, ChaseDragonFlight.wrapAngle(0.0D), EPSILON);
    }

    @Test
    void departWaypointFirstLevelsOffThenClimbsAtFortyFiveDegrees() {
        Vec3 below = ChaseDragonFlight.departWaypoint(new Vec3(0.0D, 100.0D, 3.0D), 128.0D);
        assertEquals(16.0D, below.x, EPSILON);
        assertEquals(128.0D, below.y, EPSILON);
        assertEquals(3.0D, below.z, EPSILON);

        Vec3 climb = ChaseDragonFlight.departWaypoint(new Vec3(0.0D, 200.0D, 3.0D), 128.0D);
        assertEquals(16.0D, climb.x, EPSILON);
        assertEquals(216.0D, climb.y, EPSILON);
        assertEquals(16.0D, climb.x, EPSILON);
        assertEquals(16.0D, climb.y - 200.0D, EPSILON);
    }
}
