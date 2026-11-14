package dimblend.dragonchase.entity;

import net.minecraft.world.phys.Vec3;

/**
 * Side-effect-free trajectory of a scripted {@link ChaseFireball}: a straight line from the launch
 * point to the target, reached after exactly {@code travelTicks} ticks and clamped afterwards so
 * the shot never overshoots (requirements.md §3.4: the fireball must land exactly on the track
 * point behind the nest). Unit-tested in {@code FireballPathTest}.
 *
 * <p>Server thread only, but stateless; a reload restores {@code launch}, {@code target} and the
 * tick counters from NBT and calls the same maths.
 */
public final class FireballPath {
    /** Position {@code elapsed} ticks after launch; a non-positive {@code travelTicks} is one tick. */
    public static Vec3 positionAt(Vec3 launch, Vec3 target, int elapsed, int travelTicks) {
        double progress = Math.min(1.0D, Math.max(0.0D, (double) elapsed / (double) Math.max(1, travelTicks)));
        return launch.lerp(target, progress);
    }

    /** True once the shot has reached its target and must report its impact. */
    public static boolean arrived(int elapsed, int travelTicks) {
        return elapsed >= Math.max(1, travelTicks);
    }

    private FireballPath() {
    }
}
