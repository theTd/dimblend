package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;

/**
 * All coordinates and distances are in world space, including structure hits. {@code thickness}
 * is the path length through the solid run a hit enters (1 when unknown).
 */
public record AcousticRay(Kind kind, Vec3 position, Vec3 normal, float reflectivity, float thickness) {
    public enum Kind { HIT, MISS, UNKNOWN }

    public AcousticRay(Kind kind, Vec3 position, Vec3 normal, float reflectivity) {
        this(kind, position, normal, reflectivity, 1);
    }

    public static AcousticRay miss(Vec3 end) {
        return new AcousticRay(Kind.MISS, end, Vec3.ZERO, 0, 0);
    }

    public static AcousticRay unknown(Vec3 position) {
        return new AcousticRay(Kind.UNKNOWN, position, Vec3.ZERO, 0, 0);
    }

    public static Vec3 reflect(Vec3 direction, Vec3 normal) {
        return direction.subtract(normal.scale(2 * direction.dot(normal))).normalize();
    }
}
