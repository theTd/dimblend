package dimblend.experience.client.headlight;

import dimblend.experience.headlight.HeadlightMath;
import net.minecraft.world.phys.Vec3;

/**
 * CPU reference for volume.glsl / fog.fsh: unit ray/axis, cone truncated by the surface pass's range sphere,
 * and the art-directed haze model.
 *
 * <p>Haze model (all constants mirrored in volume.glsl):
 * <ul>
 * <li>{@link #beamFalloff}: exponential along the beam ({@code exp(-2 d / range)}, about 13% at the end) with a
 * soft end at range, times a near-lens boost ({@code 1 + 2 / (1 + (d / 1.25)^2)}) so the shaft visibly leaves the
 * lamp and thins out along its length; from the driver's seat it reads as a beam going forward instead of a disc
 * floating ahead. The surface pass's flatter attenuation made the whole cone an even grey wall.</li>
 * <li>{@link #fogCone}: cross-section feathered across the whole cone radius with extra weight towards the axis
 * (floor 0.15, cubic core), so the shaft has a soft edge and a defined core, also seen from the side.</li>
 * <li>{@link #scatterPhase}: 70% isotropic plus 30% tight Henyey-Greenstein lobe (g = 0.85), relative to
 * isotropic. Side views keep a visible shaft (the old pure g = 0.5 lobe left them at 1/11 of the into-lamp
 * value), and looking into the lamp gives a concentrated glow, not a uniformly lit screen.</li>
 * <li>{@link #nearFade}: in-scatter within 4 blocks of the camera is faded out while the camera stands inside
 * the (slightly widened) cone ({@link #cameraInside}). Standing inside a beam, that direction-independent slab
 * was what veiled the whole screen, sky included; behind or beside the lamp nothing is faded, so the beam
 * starts at the lens.</li>
 * <li>{@link #haze}: Reinhard-style soft cap (0.5) on the display brightness, which keeps gradation in long
 * paths down the beam where an exponential knee flattened them into a uniform disc.</li>
 * <li>{@link #march}: equi-angular samples about the ray's closest approach to the lens, so the default
 * 8 samples resolve the near-lamp peak without banding. In-scatter behind a translucent surface (water, stained
 * glass; the march runs to the opaque surface) is dimmed by {@link #BEHIND_TRANSLUCENT}.</li>
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
    public static final double NEAR_BOOST = 2.0;
    public static final double NEAR_BOOST_RADIUS = 1.25;

    /** Fog-only radial falloff: exponential along the beam with a near-lens boost, soft end over the last 15%. */
    public static double beamFalloff(double distance, double range, double intensity) {
        double near = distance / NEAR_BOOST_RADIUS;
        return intensity * Math.exp(-FALLOFF_RATE * distance / range) * (1 + NEAR_BOOST / (1 + near * near))
                * (1 - HeadlightMath.smoothstep(range * 0.85, range, distance));
    }

    public static final double NEAR_FADE = 4.0;

    /** How much the camera stands inside the slightly widened forward cone (lens-to-camera cosine). */
    public static double cameraInside(double cosine, double outerCosine) {
        return HeadlightMath.smoothstep(outerCosine - 0.15, outerCosine + 0.05, cosine);
    }

    /** Near-camera fade, applied in proportion to {@link #cameraInside}. */
    public static double nearFade(double t, double inside) {
        return 1 + (HeadlightMath.smoothstep(0, NEAR_FADE, t) - 1) * inside;
    }

    public static final double CORE_FLOOR = 0.15;

    /** Fog cross-section: smoothstep across the whole cone radius in cosine space, weighted towards the axis. */
    public static double fogCone(double cosine, double outerCosine) {
        double t = Math.clamp((cosine - outerCosine) / Math.max(1 - outerCosine, 1e-4), 0, 1);
        return t * t * (3 - 2 * t) * (CORE_FLOOR + (1 - CORE_FLOOR) * t * t * t);
    }

    public static final double MIN_AXIS_DISTANCE = 0.05;

    /** volume.glsl HazeExposure / HazeCap: map integrated in-scatter to added display brightness. */
    public static final double HAZE_EXPOSURE = 0.65;
    public static final double HAZE_CAP = 0.5;

    /** Reinhard-style soft-capped haze brightness for a fog density and a {@link #march} sum. */
    public static double haze(double density, double scatter) {
        double x = HAZE_EXPOSURE * Math.max(0, density) * Math.max(0, scatter) / HAZE_CAP;
        return HAZE_CAP * x / (1 + x);
    }

    /** volume.glsl BehindTranslucent: weight of in-scatter seen through a translucent surface. */
    public static final double BEHIND_TRANSLUCENT = 0.6;

    /**
     * fog.fsh's per-lamp in-scatter estimate (visibility and vanilla fog fade = 1) along a unit ray from origin.
     * {@code jitter} in [0, 1) is the per-pixel sample offset.
     */
    public static double march(Vec3 origin, Vec3 ray, Vec3 lens, Vec3 axis, double range, double intensity,
                               double outerCosine, double maxDistance, int samples, double jitter) {
        return march(origin, ray, lens, axis, range, intensity, outerCosine, maxDistance, Double.POSITIVE_INFINITY,
                samples, jitter);
    }

    /**
     * As above with a translucent surface at {@code translucentDistance} in front of the opaque bound
     * {@code maxDistance}: samples behind it count with {@link #BEHIND_TRANSLUCENT}.
     */
    public static double march(Vec3 origin, Vec3 ray, Vec3 lens, Vec3 axis, double range, double intensity,
                               double outerCosine, double maxDistance, double translucentDistance, int samples,
                               double jitter) {
        Segment segment = clip(origin, ray, lens, axis, range, outerCosine, maxDistance);
        if (segment == null) return 0;
        Vec3 local = lens.subtract(origin);
        double inside = cameraInside(-local.dot(axis) / Math.max(local.length(), 1e-4), outerCosine);
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
            double behind = t > translucentDistance ? BEHIND_TRANSLUCENT : 1;
            sum += cone * beamFalloff(distance, range, intensity) * scatterPhase(direction.dot(ray.scale(-1)))
                    * nearFade(t, inside) * behind * weight;
        }
        return sum;
    }

    private HeadlightVolumeMath() {}
}
