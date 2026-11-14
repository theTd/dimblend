package dimblend.dragonchase.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Side-effect-free flight maths for {@link ChaseDragon}: heading, steering, sway and the prologue
 * departure path. Unit-tested in {@code ChaseDragonFlightTest}.
 *
 * <p>Heading convention is the vanilla {@code EnderDragon}: the model's head points along
 * {@code (sin(yaw), 0, -cos(yaw))}, so {@code yaw = 90} faces +X and {@code yaw = -90} faces -X
 * (vanilla {@code EnderDragon.aiStep} builds the same forward vector,
 * {@code E:\misc\mcsrc-1.21.1\net\minecraft\world\entity\boss\enderdragon\EnderDragon.java:288-292}).
 */
public final class ChaseDragonFlight {
    /** Follow speed cap in blocks per tick (80 m/s, comfortably above the nest's 64 m/s). */
    public static final double FOLLOW_MAX_HORIZONTAL_SPEED = 4.0D;
    /** Vertical follow cap in blocks per tick; keeps the neck/tail latency bend in the model sane. */
    public static final double FOLLOW_MAX_VERTICAL_SPEED = 1.0D;
    /** Follow acceleration in blocks per tick per tick. */
    public static final double FOLLOW_ACCELERATION = 0.25D;
    /** Yaw change allowed per tick while following. */
    public static final float MAX_TURN_DEGREES_PER_TICK = 4.0F;
    /** Prologue departure altitude (§3.2). */
    public static final double DEPART_TARGET_Y = 512.0D;

    /** Yaw that makes the dragon face {@code direction}; 0 when the direction is vertical. */
    public static float facingYaw(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontal < 1.0E-6D) {
            return 0.0F;
        }
        return (float) Math.toDegrees(Math.atan2(direction.x, -direction.z));
    }

    /** Rotates {@code currentYaw} toward {@code targetYaw} by at most {@code maxTurn} degrees. */
    public static float turnToward(float currentYaw, float targetYaw, float maxTurn) {
        float delta = Mth.clamp(Mth.wrapDegrees(targetYaw - currentYaw), -maxTurn, maxTurn);
        return Mth.wrapDegrees(currentYaw + delta);
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
