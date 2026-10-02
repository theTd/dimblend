package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;

/**
 * All coordinates and distances are in world space, including structure hits. A hit names the
 * {@link AcousticMaterials} index of the block it entered (absorption), the path length through
 * the solid run behind the entry ({@code thickness}) and the run's low/mid/high transmission,
 * combined over every material in it. Non-hits carry zero thickness and full transmission.
 * The transmission array is shared and must not be modified.
 */
public record AcousticRay(Kind kind, Vec3 position, Vec3 normal, int material, float thickness, float[] transmission) {
    public enum Kind { HIT, MISS, UNKNOWN }

    private static final float[] NONE = {1, 1, 1};

    /** A hit on one block of {@code material}. */
    public AcousticRay(Kind kind, Vec3 position, Vec3 normal, int material) {
        this(kind, position, normal, material, 1, AcousticMaterials.transmission(material, 1));
    }

    public static AcousticRay miss(Vec3 end) {
        return new AcousticRay(Kind.MISS, end, Vec3.ZERO, AcousticMaterials.STONE, 0, NONE);
    }

    public static AcousticRay unknown(Vec3 position) {
        return new AcousticRay(Kind.UNKNOWN, position, Vec3.ZERO, AcousticMaterials.STONE, 0, NONE);
    }

    public static Vec3 reflect(Vec3 direction, Vec3 normal) {
        return direction.subtract(normal.scale(2 * direction.dot(normal))).normalize();
    }
}
