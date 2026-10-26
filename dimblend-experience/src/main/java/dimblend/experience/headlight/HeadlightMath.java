package dimblend.experience.headlight;

import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * CPU reference for the shader's photometry and frame selection. Shared by the client renderer and the
 * server's marked-lava query so both agree on where a beam reaches and how strong it is there.
 */
public final class HeadlightMath {
    public static final int MAX_LIGHTS = 8;

    /** Lens position of a lamp: the block centre, shifted a quarter block opposite facing when there is no base plate. */
    public static Vec3 lens(Vec3 blockCenter, Vec3 facing, boolean noBase) {
        return noBase ? blockCenter.subtract(facing.scale(0.25)) : blockCenter;
    }

    public static double smoothstep(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** beam.glsl coneFactor: soft shoulder over the outer radius and a gently brighter centre (cosine space). */
    public static double cone(double cosine, double fullAngleDegrees) {
        double outer = Math.cos(Math.toRadians(fullAngleDegrees * 0.5));
        double t = Math.clamp((cosine - outer) / Math.max(1.0 - outer, 1e-4), 0.0, 1.0);
        return smoothstep(0.0, 0.75, t) * (0.8 + 0.2 * t * t);
    }

    public static double attenuation(double distance, double range, double intensity) {
        double d = Math.max(0.0, distance);
        double scaled = d / (range * 0.6);
        double linear = Math.max(0.0, intensity) * Math.exp(-d / range) / (1.0 + scaled * scaled);
        return -Math.expm1(-linear) * (1.0 - smoothstep(range * 0.8, range, d));
    }

    /**
     * Nearest {@link #MAX_LIGHTS} beams by lens distance. Position tie-breakers prevent equal-distance
     * lamps flickering when hash iteration order changes.
     */
    public static <T extends HeadlightRay> List<T> nearest(List<T> rays, Vec3 camera) {
        return rays.stream().sorted(Comparator
                .comparingDouble((T ray) -> ray.lens().distanceToSqr(camera))
                .thenComparingDouble(ray -> ray.lens().x)
                .thenComparingDouble(ray -> ray.lens().y)
                .thenComparingDouble(ray -> ray.lens().z))
                .limit(MAX_LIGHTS).toList();
    }

    private HeadlightMath() {}
}
