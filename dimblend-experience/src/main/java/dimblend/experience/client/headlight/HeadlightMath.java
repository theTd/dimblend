package dimblend.experience.client.headlight;

import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** CPU reference for the shader's photometry and frame selection. */
public final class HeadlightMath {
    public static final int MAX_LIGHTS = 8;

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

    public static List<HeadlightBeam> nearest(List<HeadlightBeam> beams, Vec3 camera) {
        // Position tie-breakers prevent equal-distance lamps flickering when hash iteration order changes.
        return beams.stream().sorted(Comparator
                .comparingDouble((HeadlightBeam beam) -> beam.lens().distanceToSqr(camera))
                .thenComparingDouble(beam -> beam.lens().x)
                .thenComparingDouble(beam -> beam.lens().y)
                .thenComparingDouble(beam -> beam.lens().z))
                .limit(MAX_LIGHTS).toList();
    }

    private HeadlightMath() {}
}
