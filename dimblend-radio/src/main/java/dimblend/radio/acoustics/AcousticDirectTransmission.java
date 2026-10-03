package dimblend.radio.acoustics;

import java.util.function.BiFunction;
import net.minecraft.world.phys.Vec3;

/**
 * Transmission of the direct path, traced through the voxel scene rather than by Steam Audio's
 * solver, and averaged over a bundle of rays.
 * <p>
 * Steam Audio's solver casts alternately from either end and gives up after a fixed number of hits:
 * with several obstacles on the line (a cave full of pillars), which hits it counts, and how often,
 * hinges on exact geometry, and the level jumps by whole pillars between neighbouring positions.
 * A single line is also too sharp a probe: its chord through each corner it clips swings from zero
 * to a block and a half within a few steps. Here every ray multiplies the transmission of each
 * solid run it crosses, once, and the bundle averages the rays' amplitudes per band, so the level
 * follows how much of a block-wide beam the obstacles cover. The bundle is a spindle: it widens over
 * the first and last {@link #TAPER} blocks, so no ray starts inside a wall beside either end.
 */
public final class AcousticDirectTransmission {
    /** Rays in the bundle. */
    static final int RAYS = 12;
    /** Bundle radius, in blocks, between the tapered ends. */
    static final double RADIUS = 0.5;
    /** Distance from either end over which the bundle widens to {@link #RADIUS}. */
    static final double TAPER = 1;
    /** Runs counted per ray; past a few, nothing audible is left. */
    private static final int MAX_RUNS = 16;
    /** How far past a run's exit the next cast starts. */
    private static final double STEP = 1e-3;
    /** Sunflower disk: offsets (u, v) at unit radius, evenly covering the disk. */
    private static final double[][] OFFSETS = new double[RAYS][2];
    static {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < RAYS; i++) {
            double r = Math.sqrt((i + 0.5) / RAYS), angle = i * golden;
            OFFSETS[i][0] = r * Math.cos(angle);
            OFFSETS[i][1] = r * Math.sin(angle);
        }
    }

    /**
     * @param tracer world-space cast returning the first open-to-solid entry, its run's length and
     *        transmission; a ray starting inside a solid run skips it
     * @return low/mid/high amplitude through everything between the two points; 1 when open
     */
    public static float[] between(Vec3 listener, Vec3 source, BiFunction<Vec3, Vec3, AcousticRay> tracer) {
        double length = listener.distanceTo(source);
        float[] sum = new float[3];
        if (length < 1e-6) {
            return new float[] {1, 1, 1};
        }
        Vec3 direction = source.subtract(listener).scale(1 / length);
        Vec3 side = direction.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1e-6) {
            side = direction.cross(new Vec3(1, 0, 0));
        }
        Vec3 u = side.normalize(), v = direction.cross(u);
        double taper = Math.min(TAPER, length / 2);
        double radius = RADIUS * taper / TAPER;
        Vec3 near = listener.add(direction.scale(taper)), far = source.subtract(direction.scale(taper));
        float[] ray = new float[3];
        for (double[] offset : OFFSETS) {
            Vec3 shift = u.scale(offset[0] * radius).add(v.scale(offset[1] * radius));
            java.util.Arrays.fill(ray, 1);
            through(listener, near.add(shift), tracer, ray);
            through(near.add(shift), far.add(shift), tracer, ray);
            through(far.add(shift), source, tracer, ray);
            for (int band = 0; band < 3; band++) {
                sum[band] += ray[band];
            }
        }
        for (int band = 0; band < 3; band++) {
            sum[band] /= RAYS;
        }
        return sum;
    }

    /** Multiplies {@code result} by every solid run on the segment, each counted once. */
    private static void through(Vec3 from, Vec3 to, BiFunction<Vec3, Vec3, AcousticRay> tracer, float[] result) {
        double length = from.distanceTo(to);
        if (length < 1e-9) {
            return;
        }
        Vec3 direction = to.subtract(from).scale(1 / length);
        Vec3 at = from;
        for (int run = 0; run < MAX_RUNS; run++) {
            AcousticRay hit = tracer.apply(at, to);
            if (hit.kind() != AcousticRay.Kind.HIT) {
                return;
            }
            for (int band = 0; band < 3; band++) {
                result[band] *= hit.transmission()[band];
            }
            at = hit.position().add(direction.scale(hit.thickness() + STEP));
            if (at.subtract(from).dot(direction) >= length) {
                return;
            }
        }
    }

    private AcousticDirectTransmission() { }
}
