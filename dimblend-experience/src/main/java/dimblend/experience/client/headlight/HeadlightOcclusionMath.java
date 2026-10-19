package dimblend.experience.client.headlight;

/**
 * Pure parts of headlight occlusion, mirrored by {@code occlusion_trace.fsh} / {@code occlusion.glsl}:
 * the occluder predicate, toroidal clipmap addressing and the voxel DDA used to trace each lamp's
 * distance map.
 */
public final class HeadlightOcclusionMath {
    /** Partial blocks (stairs) count as occluders from this fraction of a full cube; slabs/panes do not. */
    public static final double OCCLUDING_COVERAGE = 0.75;
    /** Returned by {@link #trace} when the segment leaves the grid or ends without entering an occupied cell. */
    public static final double NO_HIT = -1.0;
    /** Shader loop bound; a 64-block diagonal segment crosses at most ~3 * 64 cells. */
    public static final int MAX_TRACE_STEPS = 192;
    public static final int BRICK = 16;

    @FunctionalInterface
    public interface Occupancy {
        boolean occupied(int x, int y, int z);
    }

    /**
     * Whether a block occludes headlight light. Glass, leaves, fluids, plants and the lamps themselves never do;
     * otherwise the block must be able to occlude and its occlusion shape must cover most of the cell.
     */
    public static boolean occludes(boolean frontlight, boolean canOcclude, double coverage) {
        return !frontlight && canOcclude && coverage >= OCCLUDING_COVERAGE;
    }

    /** Texel of a world coordinate in a toroidal (wrap-around) grid axis of {@code size} texels. */
    public static int wrap(int world, int size) {
        return Math.floorMod(world, size);
    }

    /** Minimum brick of a camera-centred window {@code bricks} wide: the camera sits within half a brick of its centre. */
    public static int windowOriginBrick(double camera, int bricks) {
        return (int) Math.floor(camera / BRICK + 0.5) - bricks / 2;
    }

    /**
     * 3D DDA from {@code a} to {@code b} (grid units, grid box [0, size)). Returns the segment fraction at which
     * the first occupied cell is entered, or {@link #NO_HIT}. When {@code a} lies inside the box, its own cell is
     * skipped (the lamp lens cell never shadows its own beam). A segment that starts outside is clipped to the box
     * and the entry cell is tested.
     */
    public static double trace(double ax, double ay, double az, double bx, double by, double bz,
                               int sx, int sy, int sz, Occupancy grid) {
        double[] a = {ax, ay, az};
        double[] d = {bx - ax, by - ay, bz - az};
        int[] size = {sx, sy, sz};
        double t0 = 0.0, t1 = 1.0;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-8) {
                if (a[k] < 0.0 || a[k] >= size[k]) return NO_HIT;
            } else {
                double ta = -a[k] / d[k];
                double tb = (size[k] - a[k]) / d[k];
                t0 = Math.max(t0, Math.min(ta, tb));
                t1 = Math.min(t1, Math.max(ta, tb));
            }
        }
        if (t0 >= t1) return NO_HIT;
        int[] cell = new int[3];
        int[] step = new int[3];
        double[] tMax = new double[3];
        double[] tDelta = new double[3];
        for (int k = 0; k < 3; k++) {
            cell[k] = Math.min(Math.max((int) Math.floor(a[k] + d[k] * t0), 0), size[k] - 1);
            if (d[k] > 1e-8) {
                step[k] = 1;
                tMax[k] = (cell[k] + 1 - a[k]) / d[k];
                tDelta[k] = 1.0 / d[k];
            } else if (d[k] < -1e-8) {
                step[k] = -1;
                tMax[k] = (cell[k] - a[k]) / d[k];
                tDelta[k] = -1.0 / d[k];
            } else {
                tMax[k] = 1e30;
                tDelta[k] = 1e30;
            }
        }
        boolean skipStart = t0 <= 0.0;
        double entry = t0;
        for (int i = 0; i < MAX_TRACE_STEPS; i++) {
            if (!(skipStart && i == 0) && grid.occupied(cell[0], cell[1], cell[2])) return entry;
            int k = tMax[0] < tMax[1] && tMax[0] < tMax[2] ? 0 : tMax[1] < tMax[2] ? 1 : 2;
            entry = tMax[k];
            if (entry > t1) return NO_HIT;
            cell[k] += step[k];
            if (cell[k] < 0 || cell[k] >= size[k]) return NO_HIT;
            tMax[k] += tDelta[k];
        }
        return NO_HIT;
    }

    private HeadlightOcclusionMath() {}
}
