package dimblend.dragonchase.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Side-effect-free flight maths for {@link ChaseDragon}: heading, damped steering, retreat stations
 * and the prologue paths. Unit-tested in {@code ChaseDragonFlightTest}.
 *
 * <p>Heading convention is the vanilla {@code EnderDragon}: the model's head points along
 * {@code (sin(yaw), 0, -cos(yaw))}, so {@code yaw = 90} faces +X and {@code yaw = -90} faces -X
 * (vanilla {@code EnderDragon.aiStep} builds the same forward vector,
 * {@code E:\misc\mcsrc-1.21.1\net\minecraft\world\entity\boss\enderdragon\EnderDragon.java:288-292}).
 *
 * <p>The renderer reads the body attitude out of a 64-tick yaw ring buffer: the head points at
 * {@code getLatencyPos(7)}, the body rolls by {@code wrapDegrees(pos[5][0] - pos[10][0]) * 1.5} and
 * the neck/tail sections bend by the same 5-tick delta. A single hard {@code setYRot} therefore
 * leaves a 180 degree cliff in that window and folds the whole model, which is why every turn here
 * is rate limited and damped (see {@link #smoothTurn}).
 */
public final class ChaseDragonFlight {
    /**
     * Follow speed cap in blocks per tick (92 m/s), above the nest's own ceiling rather than level
     * with it: the nest may add {@code ChaseMath.KEEP_AWAY_MAX_BOOST} (24 m/s) to a full-speed
     * 64 m/s consist for 88 m/s, and a follow cap below that would make the dragon fall behind the
     * target it is supposed to ride.
     */
    public static final double FOLLOW_MAX_HORIZONTAL_SPEED = 4.6D;
    /** Vertical follow cap in blocks per tick; keeps the neck/tail latency bend in the model sane. */
    public static final double FOLLOW_MAX_VERTICAL_SPEED = 1.0D;
    /** Follow acceleration in blocks per tick per tick. */
    public static final double FOLLOW_ACCELERATION = 0.25D;
    /**
     * Yaw change allowed per tick while chasing. A 5-tick window of 3.2 deg/tick is 16 degrees, so
     * the renderer's roll stays within {@code 16 * 1.5 = 24} degrees and the neck/tail bend keeps
     * its shape instead of rolling the dragon onto its back.
     */
    public static final float MAX_TURN_DEGREES_PER_TICK = 3.2F;
    /** Damped steering gain: the target rate is {@code error * gain}, clamped to the turn limit. */
    private static final float TURN_GAIN = 0.25F;
    /** Damped steering inertia: how much of the previous yaw rate survives one tick. */
    private static final float TURN_RATE_LERP = 0.35F;
    /** Chase heading virtual forward component, so a retreating dragon still looks forward. */
    public static final double CHASE_FORWARD_MIN_X = 1.5D;
    /** Chase heading lateral clamp: {@code atan2(1.5, +-0.85)} keeps the yaw inside 90 +- 29.5. */
    public static final double CHASE_FORWARD_MAX_Z = 0.85D;
    /** Prologue departure altitude (§3.2). */
    public static final double DEPART_TARGET_Y = 512.0D;

    /** One tick of {@link #smoothTurn}: the new yaw plus the rate that produced it. */
    public record TurnStep(float yaw, float yawVelocity) {
    }

    /** Track X span covered by the breath stream, ordered from near to far along the track. */
    public record BreathZone(double fromX, double toX) {
    }

    /** Yaw that makes the dragon face {@code direction}; 0 when the direction is vertical. */
    public static float facingYaw(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontal < 1.0E-6D) {
            return 0.0F;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(direction.x, -direction.z));
        // atan2(-0.0, -1) is -pi while atan2(0, -1) is +pi: the same heading written two ways. Keep
        // the canonical 180 so a tangent generated from sin(0) does not flip the stored yaw.
        return yaw == -180.0F ? 180.0F : yaw;
    }

    /** Rotates {@code currentYaw} toward {@code targetYaw} by at most {@code maxTurn} degrees. */
    public static float turnToward(float currentYaw, float targetYaw, float maxTurn) {
        float delta = Mth.clamp(Mth.wrapDegrees(targetYaw - currentYaw), -maxTurn, maxTurn);
        return Mth.wrapDegrees(currentYaw + delta);
    }

    /**
     * One tick of damped steering: the yaw rate eases toward the shortest-arc error instead of
     * snapping to it, so a turn starts and ends smoothly.
     *
     * <p>Guarantees: the tick delta never exceeds {@code maxTurnPerTick}, the yaw never crosses the
     * target (no overshoot, no oscillation) and a turn across the +-180 seam stays continuous.
     *
     * @param currentYawVelocity the previous tick's rate, i.e. the dragon's {@code yRotA}
     */
    public static TurnStep smoothTurn(float currentYaw, float currentYawVelocity, float targetYaw, float maxTurnPerTick) {
        float limit = Math.max(0.0F, maxTurnPerTick);
        float delta = Mth.wrapDegrees(targetYaw - currentYaw);
        float desiredRate = Mth.clamp(delta * TURN_GAIN, -limit, limit);
        float velocity = Mth.clamp(Mth.lerp(TURN_RATE_LERP, currentYawVelocity, desiredRate), -limit, limit);
        // Bleed the carried rate off at the target instead of sailing past it.
        if (Math.abs(velocity) > Math.abs(delta)) {
            velocity = delta;
        }
        return new TurnStep(Mth.wrapDegrees(currentYaw + velocity), velocity);
    }

    /**
     * Chase heading: always the forward corridor. Lateral velocity tilts the nose at most
     * +-29.5 degrees while the dragon sways in z, and a retreating dragon (world {@code vx <= 0})
     * keeps aiming at 90 instead of turning round.
     */
    public static float chaseForwardYaw(Vec3 velocity) {
        return facingYaw(new Vec3(Math.max(CHASE_FORWARD_MIN_X, velocity.x), 0.0D,
                Mth.clamp(velocity.z, -CHASE_FORWARD_MAX_Z, CHASE_FORWARD_MAX_Z)));
    }

    /**
     * Velocity the dragon wants this tick.
     *
     * @param offset vector from the dragon to its goal, in blocks
     * @param feedForward the anchor's own per-tick velocity, so the dragon keeps up instead of
     *                    lagging by {@code speed / gain}
     * @param maxHorizontalSpeed cap on the horizontal component
     * @param maxVerticalSpeed cap on the vertical component
     */
    public static Vec3 desiredVelocity(Vec3 offset, Vec3 feedForward, double maxHorizontalSpeed, double maxVerticalSpeed) {
        Vec3 error = new Vec3(offset.x, 0.0D, offset.z);
        double errorLength = error.length();
        Vec3 desired = errorLength > 1.0E-6D
                ? error.scale(Math.min(maxHorizontalSpeed, errorLength) / errorLength)
                : Vec3.ZERO;
        desired = desired.add(feedForward.x, 0.0D, feedForward.z);
        double desiredLength = desired.length();
        if (desiredLength > maxHorizontalSpeed) {
            desired = desired.scale(maxHorizontalSpeed / desiredLength);
        }
        double vertical = Mth.clamp(offset.y + feedForward.y, -maxVerticalSpeed, maxVerticalSpeed);
        return new Vec3(desired.x, vertical, desired.z);
    }

    /**
     * Velocity a retreat wants: the goal error is closed at up to {@code maxRelativeRetreatSpeed}
     * blocks per tick <em>relative to the moving anchor</em>, so the absolute velocity is allowed
     * to point backwards (or to slow down) while the nest flees at up to 3.2 blocks per tick.
     */
    public static Vec3 retreatVelocity(Vec3 offset, Vec3 anchorVelocity, double maxRelativeRetreatSpeed, double maxVerticalSpeed) {
        Vec3 error = new Vec3(offset.x, 0.0D, offset.z);
        double errorLength = error.length();
        Vec3 desired = errorLength > 1.0E-6D
                ? error.scale(Math.min(maxRelativeRetreatSpeed, errorLength) / errorLength)
                : Vec3.ZERO;
        desired = desired.add(anchorVelocity.x, 0.0D, anchorVelocity.z);
        double vertical = Mth.clamp(offset.y + anchorVelocity.y, -maxVerticalSpeed, maxVerticalSpeed);
        return new Vec3(desired.x, vertical, desired.z);
    }

    /** Moves {@code current} toward {@code desired} by at most {@code maxDelta} blocks per tick. */
    public static Vec3 approach(Vec3 current, Vec3 desired, double maxDelta) {
        Vec3 delta = desired.subtract(current);
        double length = delta.length();
        if (length > maxDelta) {
            delta = delta.scale(maxDelta / length);
        }
        return current.add(delta);
    }

    /**
     * Smooth lateral sway in {@code [-amplitude, amplitude]}, deterministic for one dragon.
     */
    public static double swayZ(int tick, long seed, double amplitude) {
        return amplitude * (0.65D * Math.sin(tick * 0.011D + phaseFromSeed(seed, 0))
                + 0.35D * Math.sin(tick * 0.027D + phaseFromSeed(seed, 1)));
    }

    /** Smooth vertical bob in {@code [-amplitude, amplitude]}. */
    public static double bobY(int tick, long seed, double amplitude) {
        return amplitude * Math.sin(tick * 0.013D + phaseFromSeed(seed, 2));
    }

    /** Wraps an orbit angle into {@code [0, 2*pi)}. */
    public static double wrapAngle(double angle) {
        double twoPi = Math.PI * 2.0D;
        double wrapped = angle % twoPi;
        return wrapped < 0.0D ? wrapped + twoPi : wrapped;
    }

    /** Point on the horizontal circle of {@code radius} around {@code center}, at altitude {@code y}. */
    public static Vec3 orbitPoint(Vec3 center, double radius, double angle, double y) {
        return new Vec3(center.x + Math.cos(angle) * radius, y, center.z + Math.sin(angle) * radius);
    }

    /**
     * Heading of the counter-clockwise tangent at {@code angle} of {@link #orbitPoint}
     * ({@code d/da (cos a, sin a) = (-sin a, cos a)}), i.e. the nose direction of a dragon flying
     * the circle with its angle increasing every tick.
     */
    public static float orbitTangentYaw(double angle) {
        return facingYaw(new Vec3(-Math.sin(angle), 0.0D, Math.cos(angle)));
    }

    /**
     * X the dragon pulls back to before firing: just ahead of the train front (the cab windscreen),
     * but never closer than 24 blocks behind the nest so the shot still travels {@code +X}.
     */
    public static double fireballRetreatX(double nestX, double trainFrontX) {
        return Math.min(trainFrontX + 8.0D, nestX - 24.0D);
    }

    /**
     * X of the low breath station: half way between the nest and the train reference point, with
     * the 28-block floor that keeps the mouth ahead of the track span even at the minimum gap.
     */
    public static double breathRetreatX(double nestX, double trainFrontX) {
        return Math.min(0.5D * (nestX + trainFrontX), nestX - 28.0D);
    }

    /**
     * Track span sprayed from {@code stationX}: starts just past the mouth ({@code stationX + 7.5})
     * and stops 12 blocks short of the nest, so the stream always travels {@code +X} and never
     * falls behind the dragon's own jaws.
     */
    public static BreathZone breathZoneRange(double nestX, double stationX) {
        double fromX = Math.min(stationX + 8.0D, nestX - 16.0D);
        double toX = Math.min(stationX + 48.0D, nestX - 12.0D);
        return new BreathZone(Math.min(fromX, toX - 4.0D), toX);
    }

    /**
     * Waypoint of the prologue departure: first fly forward (+X) to {@code climbStartY}, then climb
     * along +X at exactly 45 degrees (equal dx and dy per waypoint).
     */
    public static Vec3 departWaypoint(Vec3 current, double climbStartY) {
        if (current.y < climbStartY - 0.75D) {
            return new Vec3(current.x + 16.0D, climbStartY, current.z);
        }
        return new Vec3(current.x + 16.0D, current.y + 16.0D, current.z);
    }

    private static double phaseFromSeed(long seed, int salt) {
        long mixed = seed * 6364136223846793005L + (salt + 1) * 1442695040888963407L;
        mixed ^= mixed >>> 33;
        return (double) ((mixed >>> 11) & 0xFFFFL) * (Math.PI * 2.0D / 65536.0D);
    }

    private ChaseDragonFlight() {
    }
}
