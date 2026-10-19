package dimblend.experience.client.headlight;

import net.minecraft.world.phys.Vec3;

/**
 * CPU reference for volume.glsl / fog.fsh: unit ray/axis, cone truncated by the surface pass's range sphere,
 * and the art-directed haze model.
 *
 * <p>Haze model (all constants mirrored in volume.glsl):
 * <ul>
 * <li>{@link #beamFalloff}: exponential along the beam ({@code exp(-2 d / range)}, about 13% at the end) with a
 * soft end at range. The surface pass's flatter attenuation made the whole cone an even grey wall.</li>
 * <li>{@link #fogCone}: cross-section feathered across the whole cone radius, so the shaft has a soft edge and a
 * brighter core.</li>
 * <li>{@link #scatterPhase}: 70% isotropic plus 30% tight Henyey-Greenstein lobe (g = 0.85), relative to
 * isotropic. Side views keep a visible shaft (the old pure g = 0.5 lobe left them at 1/11 of the into-lamp
 * value), and looking into the lamp gives a concentrated glow, not a uniformly lit screen.</li>
 * <li>{@link #nearFade}: in-scatter within 4 blocks of the camera is faded out. Standing inside a beam, that
 * direction-independent slab was what veiled the whole screen, sky included.</li>
 * <li>{@link #haze}: soft exponential cap (0.5) on the display brightness.</li>
 * <li>{@link #march}: equi-angular samples about the ray's closest approach to the lens, so the default
 * 8 samples resolve the near-lamp peak without banding.</li>
 * </ul>
 */
public final class HeadlightVolumeMath {
    public record Segment(double start, double end) {}

    /** Below this |a| the cone quadratic is solved as linear; the dropped root lies beyond any range sphere. */
    public static final double CONE_LINEAR_EPSILON = 1e-4;

    public static Segment clip(Vec3 origin, Vec3 ray, Vec3 lens, Vec3 axis,
                               double range, double outerCosine, double maxDistance) {
        Vec3 p = origin.subtract(lens);
        double b = p.dot(ray);
        double discriminant = b * b - p.lengthSqr() + range * range;
        if (discriminant <= 0) return null;
        double root = Math.sqrt(discriminant);
        double lo = Math.max(0, -b - root);
        double hi = Math.min(maxDistance, -b + root);
        double z = p.dot(axis);
        double dz = ray.dot(axis);
        if (Math.abs(dz) < 1e-6) {
            if (z < 0) return null;
        } else if (dz > 0) lo = Math.max(lo, -z / dz);
        else hi = Math.min(hi, -z / dz);
        if (hi <= lo) return null;

        double cos2 = outerCosine * outerCosine;
        double a = cos2 - dz * dz;
        double qb = cos2 * b - z * dz;
        double c = cos2 * p.lengthSqr() - z * z;
        if (Math.abs(a) < CONE_LINEAR_EPSILON) {
            if (Math.abs(qb) < 1e-6) {
                // f(t) ~ a t^2 + c: from (almost) the apex, a decides; otherwise the origin side does.
                if (c > 0 || (c > -1e-6 && a > 0)) return null;
            } else if (qb > 0) hi = Math.min(hi, -c / (2 * qb));
            else lo = Math.max(lo, -c / (2 * qb));
        } else {
            double d = qb * qb - a * c;
            if (d < 0) {
                if (a > 0) return null;
            } else {
                // Plain roots, as in volume.glsl: the q = -(b + sign(b) sqrt(d)) form collapsed to ~0 on the GPU
                // for rays through the apex and cut the march short (the black spot at the lamp).
                double h = Math.sqrt(d);
                double t0 = (-qb - h) / a;
                double t1 = (-qb + h) / a;
                double lower = Math.min(t0, t1);
                double upper = Math.max(t0, t1);
                if (a > 0) {
                    lo = Math.max(lo, lower);
                    hi = Math.min(hi, upper);
                // For a < 0 the two roots bound opposite cone nappes. Select by axis direction,
                // not a rounded lo/root comparison at the apex (which can select the rear nappe).
                } else if (dz > 0) lo = Math.max(lo, upper);
                else hi = Math.min(hi, lower);
            }
        }
        return hi > lo ? new Segment(lo, hi) : null;
    }

    /** Normalized Henyey-Greenstein: incoming lamp-to-sample direction dot outgoing sample-to-camera direction. */
    public static double phase(double cosine, double g) {
        double denominator = 1 + g * g - 2 * g * Math.clamp(cosine, -1, 1);
        return (1 - g * g) / (4 * Math.PI * denominator * Math.sqrt(denominator));
    }

    public static final double PHASE_G = 0.85;
    public static final double PHASE_FORWARD = 0.3;

    /** Isotropic floor plus a tight forward lobe, relative to isotropic (isotropic = 1). */
    public static double scatterPhase(double cosine) {
        return (1 - PHASE_FORWARD) + PHASE_FORWARD * 4 * Math.PI * phase(cosine, PHASE_G);
    }

    public static final double FALLOFF_RATE = 2.0;

    /** Fog-only radial falloff: exponential along the beam, soft end over the last 15% of range. */
    public static double beamFalloff(double distance, double range, double intensity) {
        return intensity * Math.exp(-FALLOFF_RATE * distance / range)
                * (1 - HeadlightMath.smoothstep(range * 0.85, range, distance));
    }

    public static final double NEAR_FADE = 4.0;

    public static double nearFade(double t) { return HeadlightMath.smoothstep(0, NEAR_FADE, t); }

    /** Fog cross-section: smoothstep across the whole cone radius in cosine space. */
    public static double fogCone(double cosine, double outerCosine) {
        double t = Math.clamp((cosine - outerCosine) / Math.max(1 - outerCosine, 1e-4), 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static final double MIN_AXIS_DISTANCE = 0.05;

    /** volume.glsl HazeExposure / HazeCap: map integrated in-scatter to added display brightness. */
    public static final double HAZE_EXPOSURE = 0.4;
    public static final double HAZE_CAP = 0.5;

    /** Soft-capped haze brightness for a fog density and a {@link #march} sum. */
    public static double haze(double density, double scatter) {
        return -HAZE_CAP * Math.expm1(-HAZE_EXPOSURE * Math.max(0, density) * Math.max(0, scatter) / HAZE_CAP);
    }

    /**
     * fog.fsh's per-lamp in-scatter estimate (visibility and vanilla fog fade = 1) along a unit ray from origin.
     * {@code jitter} in [0, 1) is the per-pixel sample offset.
     */
    public static double march(Vec3 origin, Vec3 ray, Vec3 lens, Vec3 axis, double range, double intensity,
                               double outerCosine, double maxDistance, int samples, double jitter) {
        Segment segment = clip(origin, ray, lens, axis, range, outerCosine, maxDistance);
        if (segment == null) return 0;
        Vec3 local = lens.subtract(origin);
        double closest = local.dot(ray);
        double axisDistance = Math.max(local.subtract(ray.scale(closest)).length(), MIN_AXIS_DISTANCE);
        double angleStart = Math.atan((segment.start() - closest) / axisDistance);
        double angleRange = Math.atan((segment.end() - closest) / axisDistance) - angleStart;
        double sum = 0;
        for (int s = 0; s < samples; s++) {
            double t = closest + axisDistance * Math.tan(angleStart + (s + jitter) / samples * angleRange);
            Vec3 offset = ray.scale(t).subtract(local);
            double distance = offset.length();
            Vec3 direction = offset.scale(1 / Math.max(distance, 1e-4));
            double cone = fogCone(direction.dot(axis), outerCosine);
            if (cone <= 0) continue;
            double along = t - closest;
            double weight = angleRange * (axisDistance * axisDistance + along * along) / (axisDistance * samples);
            sum += cone * beamFalloff(distance, range, intensity) * scatterPhase(direction.dot(ray.scale(-1)))
                    * nearFade(t) * weight;
        }
        return sum;
    }

    private HeadlightVolumeMath() {}
}
