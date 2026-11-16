package dimblend.dragonchase.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Flight maths of the prologue and chase modes (§3.2, §3.4). */
class ChaseDragonFlightTest {
    private static final double EPSILON = 1.0E-6D;
    /** Nest anchor altitude used by the chase simulations (track y 65 + the nest's 19). */
    private static final double NEST_Y = 84.0D;
    /** 64 m/s, the chase maximum the director's nest reaches. */
    private static final double NEST_SPEED = 3.2D;

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
    void smoothTurnIsDampedLimitedAndNeverOvershoots() {
        float limit = ChaseDragonFlight.MAX_TURN_DEGREES_PER_TICK;
        float yaw = 0.0F;
        float rate = 0.0F;
        for (int tick = 0; tick < 200; tick++) {
            ChaseDragonFlight.TurnStep step = ChaseDragonFlight.smoothTurn(yaw, rate, 90.0F, limit);
            float delta = Mth.wrapDegrees(step.yaw() - yaw);
            assertTrue(Math.abs(delta) <= limit + 1.0E-4F, "tick " + tick + " turned " + delta);
            assertTrue(Math.abs(step.yawVelocity()) <= limit + 1.0E-4F, "tick " + tick + " rate " + step.yawVelocity());
            assertTrue(delta >= 0.0F, "the turn reversed at tick " + tick);
            // The renderer's 5-tick latency window must stay inside 16 degrees, i.e. roll <= 24.
            assertTrue(step.yaw() <= 90.0F + 1.0E-4F, "overshoot to " + step.yaw() + " at tick " + tick);
            if (tick == 0) {
                assertTrue(delta < limit, "the first tick must ramp up instead of snapping to the limit: " + delta);
            }
            yaw = step.yaw();
            rate = step.yawVelocity();
        }
        assertEquals(90.0F, yaw, 1.0E-3F);
        assertEquals(0.0F, rate, 1.0E-3F);
    }

    @Test
    void smoothTurnCrossesTheHalfTurnSeamWithoutAJump() {
        float yaw = 170.0F;
        float rate = 0.0F;
        for (int tick = 0; tick < 40; tick++) {
            ChaseDragonFlight.TurnStep step = ChaseDragonFlight.smoothTurn(yaw, rate, -170.0F, 3.2F);
            float delta = Mth.wrapDegrees(step.yaw() - yaw);
            assertTrue(Math.abs(delta) <= 3.2F + 1.0E-4F, "seam tick " + tick + " turned " + delta);
            yaw = step.yaw();
            rate = step.yawVelocity();
        }
        assertEquals(-170.0F, yaw, 1.0E-3F);
    }

    @Test
    void chaseForwardYawStaysForwardWhileSwayingAndRetreating() {
        assertEquals(90.0F, ChaseDragonFlight.chaseForwardYaw(new Vec3(4.0D, 0.0D, 0.0D)), 1.0E-4F);
        // A halted dragon keeps looking down the corridor.
        assertEquals(90.0F, ChaseDragonFlight.chaseForwardYaw(Vec3.ZERO), 1.0E-4F);
        // Pulling back to the train front: the world velocity is negative, the nose is not.
        assertEquals(90.0F, ChaseDragonFlight.chaseForwardYaw(new Vec3(-2.4D, 0.0D, 0.0D)), 1.0E-4F);
        assertEquals(90.0F, ChaseDragonFlight.chaseForwardYaw(new Vec3(-3.0D, 0.5D, 0.0D)), 1.0E-4F);

        Vec3[] headings = {
                new Vec3(4.0D, 0.0D, 3.2D),
                new Vec3(4.0D, 0.0D, -3.2D),
                new Vec3(0.8D, 0.0D, 2.0D),
                new Vec3(-3.0D, 0.0D, 2.0D),
                new Vec3(-1.0D, 0.0D, -2.0D),
        };
        for (Vec3 heading : headings) {
            float yaw = ChaseDragonFlight.chaseForwardYaw(heading);
            assertTrue(yaw >= 60.0F && yaw <= 120.0F, "heading " + heading + " gave yaw " + yaw);
        }
        // The lateral clamp is what bounds the lean: 90 +- 29.5 degrees, never -90.
        assertEquals(119.5F, ChaseDragonFlight.chaseForwardYaw(new Vec3(1.5D, 0.0D, 0.85D)), 0.1F);
        assertEquals(60.5F, ChaseDragonFlight.chaseForwardYaw(new Vec3(1.5D, 0.0D, -0.85D)), 0.1F);
    }

    @Test
    void orbitTangentYawFollowsTheCounterClockwiseCircle() {
        // orbitPoint(a) = (cx + cos a * R, y, cz + sin a * R) with a growing: the tangent is
        // (-sin a, 0, cos a), i.e. +Z at a = 0 and -X at a = pi / 2.
        assertEquals(180.0F, ChaseDragonFlight.orbitTangentYaw(0.0D), 1.0E-4F);
        assertEquals(-90.0F, ChaseDragonFlight.orbitTangentYaw(Math.PI / 2.0D), 1.0E-4F);
        assertEquals(0.0F, ChaseDragonFlight.orbitTangentYaw(Math.PI), 1.0E-4F);
        assertEquals(90.0F, ChaseDragonFlight.orbitTangentYaw(Math.PI * 1.5D), 1.0E-4F);

        // Flying one block along the tangent from any spawn angle keeps the radius and advances the
        // angle: the spawn heading is the flight direction, not a fixed axis.
        Vec3 centre = new Vec3(3.0D, 0.0D, -7.0D);
        for (double angle : new double[]{0.0D, 0.4D, 1.3D, 2.7D, 5.9D}) {
            Vec3 point = ChaseDragonFlight.orbitPoint(centre, 50.0D, angle, 120.0D);
            float yawRad = ChaseDragonFlight.orbitTangentYaw(angle) * (float) (Math.PI / 180.0D);
            Vec3 next = point.add((double) Mth.sin(yawRad), 0.0D, (double) -Mth.cos(yawRad));
            assertEquals(50.0D, Math.sqrt((next.x - centre.x) * (next.x - centre.x)
                    + (next.z - centre.z) * (next.z - centre.z)), 0.05D);
            assertTrue(Math.atan2(next.z - centre.z, next.x - centre.x)
                    > Math.atan2(point.z - centre.z, point.x - centre.x), "angle did not advance at " + angle);
        }
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
    void retreatVelocityCapsOnlyTheMotionRelativeToTheAnchor() {
        // The nest flees at 3.2 b/t: retreating at the 2.4 b/t cap still leaves a forward world
        // velocity. Re-clamping the total to the cap would drop the dragon behind instead.
        Vec3 velocity = ChaseDragonFlight.retreatVelocity(new Vec3(-100.0D, 0.0D, 0.0D),
                new Vec3(NEST_SPEED, 0.0D, 0.0D), ChaseDragon.FIREBALL_RETREAT_SPEED,
                ChaseDragon.FIREBALL_MAX_VERTICAL_SPEED);
        assertEquals(NEST_SPEED - ChaseDragon.FIREBALL_RETREAT_SPEED, velocity.x, EPSILON);

        // A stationary anchor lets the dragon actually fly backwards.
        Vec3 backwards = ChaseDragonFlight.retreatVelocity(new Vec3(-50.0D, 0.0D, 0.0D), Vec3.ZERO,
                ChaseDragon.FIREBALL_RETREAT_SPEED, ChaseDragon.FIREBALL_MAX_VERTICAL_SPEED);
        assertEquals(-ChaseDragon.FIREBALL_RETREAT_SPEED, backwards.x, EPSILON);

        // The goal is not overshot, and the vertical error stays under its own cap.
        Vec3 near = ChaseDragonFlight.retreatVelocity(new Vec3(-0.4D, 8.0D, 0.0D), Vec3.ZERO,
                ChaseDragon.FIREBALL_RETREAT_SPEED, ChaseDragon.FIREBALL_MAX_VERTICAL_SPEED);
        assertEquals(-0.4D, near.x, EPSILON);
        assertEquals(ChaseDragon.FIREBALL_MAX_VERTICAL_SPEED, near.y, EPSILON);
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
    void fireballRetreatXStaysBehindTheImpactLine() {
        double nestX = 1000.0D;
        // The shot lands 12 blocks behind the nest; the mouth is 7.5 ahead of the station, so the
        // station must stay at least 16.5 blocks behind the impact line to fire along +X.
        for (double gap : new double[]{32.0D, 96.0D, 128.0D, 256.0D}) {
            double stationX = ChaseDragonFlight.fireballRetreatX(nestX, nestX - gap);
            assertTrue(stationX < nestX - 12.0D, "gap " + gap + " put the station at " + (stationX - nestX));
            assertTrue(stationX + 7.5D <= nestX - 16.5D + EPSILON, "gap " + gap + " puts the mouth past the impact");
        }
        // Just ahead of the train front once the gap allows it, and never closer than 24 to the nest.
        assertEquals(nestX - 120.0D, ChaseDragonFlight.fireballRetreatX(nestX, nestX - 128.0D), EPSILON);
        assertEquals(nestX - 24.0D, ChaseDragonFlight.fireballRetreatX(nestX, nestX - 32.0D), EPSILON);
        // A train front ahead of the nest still cannot pull the station past the 24-block floor.
        assertEquals(nestX - 24.0D, ChaseDragonFlight.fireballRetreatX(nestX, nestX + 40.0D), EPSILON);
    }

    @Test
    void breathStationSitsHalfWayAndTheZoneStartsBeyondTheMouth() {
        double nestX = 1000.0D;
        for (double gap : new double[]{32.0D, 96.0D, 256.0D}) {
            double stationX = ChaseDragonFlight.breathRetreatX(nestX, nestX - gap);
            assertTrue(stationX <= nestX - 28.0D + EPSILON, "gap " + gap + " put the station at " + (stationX - nestX));
            ChaseDragonFlight.BreathZone zone = ChaseDragonFlight.breathZoneRange(nestX, stationX);
            assertTrue(zone.fromX() >= stationX + 7.5D - EPSILON, "gap " + gap + " sprays behind the mouth");
            assertTrue(zone.toX() <= nestX - 12.0D + EPSILON, "gap " + gap + " sprays past the nest");
            assertTrue(zone.toX() - zone.fromX() >= 4.0D - EPSILON, "gap " + gap + " leaves an empty span");
        }
        // The 128-block gap of a real chase: half way, 40 blocks of track right in front of the mouth.
        assertEquals(nestX - 64.0D, ChaseDragonFlight.breathRetreatX(nestX, nestX - 128.0D), EPSILON);
        ChaseDragonFlight.BreathZone zone = ChaseDragonFlight.breathZoneRange(nestX, nestX - 64.0D);
        assertEquals(nestX - 56.0D, zone.fromX(), EPSILON);
        assertEquals(nestX - 16.0D, zone.toX(), EPSILON);
    }

    @Test
    void catchUpNumbersArePinned() {
        assertEquals(0.9D, ChaseDragon.CATCH_UP_SPEED_SURPLUS);
        assertEquals(0.08D, ChaseDragon.CATCH_UP_ACCELERATION);
        assertEquals(8.0D, ChaseDragon.CATCH_UP_ARRIVAL_X);
        assertEquals(20.0D, ChaseDragon.FIREBALL_RETREAT_HEIGHT);
        assertEquals(10.0D, ChaseDragon.BREATH_HOVER_HEIGHT);
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

    @Test
    void breathStationIsReachableWithinTheApproachTimeout() {
        double nestX = 0.0D;
        Vec3 position = new Vec3(0.0D, NEST_Y + ChaseDragon.FOLLOW_HEIGHT, 0.0D);
        Vec3 velocity = new Vec3(NEST_SPEED, 0.0D, 0.0D);
        int ticks = 0;
        while (ticks < ChaseDragon.BREATH_APPROACH_TIMEOUT) {
            nestX += NEST_SPEED;
            ticks++;
            double stationX = ChaseDragonFlight.breathRetreatX(nestX, nestX - 128.0D);
            Vec3 hover = new Vec3(stationX, NEST_Y + ChaseDragon.BREATH_HOVER_HEIGHT, 0.0D);
            // Half of the nest plus half of the train, both fleeing at 64 m/s.
            Vec3 feedForward = new Vec3(NEST_SPEED, 0.0D, 0.0D);
            velocity = accelerate(velocity, ChaseDragonFlight.retreatVelocity(hover.subtract(position), feedForward,
                    ChaseDragon.BREATH_RETREAT_SPEED, ChaseDragon.BREATH_MAX_VERTICAL_SPEED),
                    ChaseDragon.BREATH_ACCELERATION);
            position = position.add(velocity);
            if (Math.abs(position.x - stationX) <= 5.0D && Math.abs(position.y - hover.y) <= 4.0D) {
                break;
            }
        }
        assertTrue(ticks < ChaseDragon.BREATH_APPROACH_TIMEOUT,
                "the low station was never reached, still at " + (position.x - nestX));
        assertEquals(NEST_Y + ChaseDragon.BREATH_HOVER_HEIGHT, position.y, 4.0D);
    }

    /**
     * Full attack cycle: the dragon holds the follow altitude, pulls back past the nest to the
     * train front, fires at the predicted track point and then closes back up on a nest that keeps
     * fleeing at 64 m/s. Mirrors {@code tickFireballRetreat} / {@code tickCatchUp} with the same
     * per-axis acceleration.
     */
    @Test
    void fireballRetreatThenCatchUpClosesOnAFleeingNest() {
        double nestX = 0.0D;
        Vec3 position = new Vec3(0.0D, NEST_Y + ChaseDragon.FOLLOW_HEIGHT, 0.0D);
        Vec3 velocity = new Vec3(NEST_SPEED, 0.0D, 0.0D);
        Vec3 nestVelocity = new Vec3(NEST_SPEED, 0.0D, 0.0D);

        int retreatTicks = 0;
        double goalX = 0.0D;
        while (retreatTicks < ChaseDragon.FIREBALL_RETREAT_TIMEOUT) {
            nestX += NEST_SPEED;
            retreatTicks++;
            goalX = ChaseDragonFlight.fireballRetreatX(nestX, nestX - 128.0D);
            Vec3 goal = new Vec3(goalX, NEST_Y, 0.0D);
            velocity = accelerate(velocity, ChaseDragonFlight.retreatVelocity(goal.subtract(position), nestVelocity,
                    ChaseDragon.FIREBALL_RETREAT_SPEED, ChaseDragon.FIREBALL_MAX_VERTICAL_SPEED),
                    ChaseDragon.FIREBALL_RETREAT_ACCELERATION);
            position = position.add(velocity);
            if (position.x <= goalX + 4.0D && Math.abs(position.z) <= 8.0D) {
                break;
            }
        }
        assertTrue(retreatTicks < ChaseDragon.FIREBALL_RETREAT_TIMEOUT,
                "the retreat never reached the train front, still at " + (position.x - goalX));
        assertTrue(retreatTicks <= 70, "a 128-block pull-back should take about 2-3 s, took " + retreatTicks);
        assertTrue(position.x - nestX <= -100.0D, "the dragon must be well behind the nest: " + (position.x - nestX));
        // Everything in front of the mouth must be the impact line, so the shot travels +X.
        double impactX = Math.max(nestX - 12.0D, position.x + 16.0D);
        assertTrue(impactX > position.x + 7.5D, "the impact point sits behind the mouth: " + (impactX - position.x));

        int catchUpTicks = 0;
        while (catchUpTicks < 260) {
            nestX += NEST_SPEED;
            catchUpTicks++;
            Vec3 target = new Vec3(nestX, NEST_Y + ChaseDragon.FOLLOW_HEIGHT, 0.0D);
            double maxSpeed = Math.max(1.4D, NEST_SPEED + ChaseDragon.CATCH_UP_SPEED_SURPLUS);
            velocity = accelerate(velocity, ChaseDragonFlight.desiredVelocity(target.subtract(position), nestVelocity,
                    maxSpeed, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED), ChaseDragon.CATCH_UP_ACCELERATION);
            position = position.add(velocity);
            if (Math.abs(position.x - nestX) <= ChaseDragon.CATCH_UP_ARRIVAL_X) {
                break;
            }
        }
        assertTrue(Math.abs(position.x - nestX) <= ChaseDragon.CATCH_UP_ARRIVAL_X,
                "the chase never closed, still " + (position.x - nestX) + " away");
        assertTrue(catchUpTicks <= 220, "catch-up took " + catchUpTicks + " ticks");
        assertTrue(catchUpTicks > 60, "the catch-up must be gradual, not a snap back: " + catchUpTicks);
    }

    /** Same split as {@code ChaseDragon#accelerate}: horizontal and vertical accelerate separately. */
    private static Vec3 accelerate(Vec3 velocity, Vec3 desired, double acceleration) {
        Vec3 horizontal = ChaseDragonFlight.approach(new Vec3(velocity.x, 0.0D, velocity.z),
                new Vec3(desired.x, 0.0D, desired.z), acceleration);
        double vertical = velocity.y + Mth.clamp(desired.y - velocity.y, -acceleration, acceleration);
        return new Vec3(horizontal.x, vertical, horizontal.z);
    }
}
