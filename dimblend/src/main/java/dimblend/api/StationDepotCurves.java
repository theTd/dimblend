package dimblend.api;

/**
 * Pure geometry for the R6 depot's two merge curves, shared by {@code dimblend}'s
 * layout tests and {@code dimblend-experience}'s curve builder.
 *
 * <p><b>Why the curves are parallel, not focal.</b> A siding runs along X at
 * {@code z = +-SIDING_DZ}; the main line runs along X at {@code z = 0}. Those two
 * lines are parallel, so there is no intersection point to aim both tangents at.
 * Create handles exactly this case (see {@code BezierConnection.Runtime#determineHandles}:
 * an angle of zero falls through to the parallel branch, handle length
 * {@code distance / 3}), so each curve is a lateral S-shift with both end tangents
 * horizontal and pointing <b>into</b> the curve:
 * {@code +X} at the siding end, {@code -X} at the main-line end.
 *
 * <p><b>Why the two main-line ends are staggered.</b> Both curves must join the main
 * line, and if they joined the <em>same</em> block that block would be a junction
 * ({@code TrackShape.CR_*}), which the picture does not show and which needs cross
 * shapes at two different heights. Staggering the two merge columns keeps every block
 * a plain straight track and still gives one fork. That is also what Railways'
 * {@code TrackSwitch#updateExits} expects: one switch edge point on the main line,
 * whose exits are the two curve ends it can reach going forward.
 */
public final class StationDepotCurves {
    /** Lateral offset of each siding: the carriage side is positive. */
    public static final int SIDING_DZ = StationDepotSites.SIDING_DZ;
    /**
     * Column where the positive-siding curve lands on the main line. The switch stands
     * on the main line here.
     */
    public static final int POSITIVE_MERGE_DX = 32;
    /**
     * Column where the negative-siding curve lands. Staggered east so the two curves
     * never share an endpoint block: a shared endpoint would need a cross-shape
     * junction ({@code TrackShape.CR_*}) whose two track axes sit at different heights,
     * and getting that shape wrong is a silent, unrecoverable track defect. Two plain
     * turnouts are each a known-good {@code XO} block with a curve attached.
     */
    public static final int NEGATIVE_MERGE_DX = 44;
    /** The two sidings' straight runs both end here; both curves start here. */
    public static final int CURVE_START_DX = StationDepotSites.SIDING_LENGTH / 2 - 1;

    /** One curve's endpoint pair, in blocks. */
    public record Endpoint(int x, int z) {
    }

    /** Everything the curve builder needs, all derivable from the boundary column. */
    public record Curve(int fromX, int fromZ, int toX, int toZ) {
        /** +1 for the positive siding, -1 for the negative one. */
        public int side() {
            return fromZ > 0 ? 1 : -1;
        }
    }

    private StationDepotCurves() {
    }

    /** The positive-siding curve: from the siding's east end to the first main-line merge. */
    public static Curve positive(int boundaryX) {
        return new Curve(boundaryX + CURVE_START_DX, SIDING_DZ,
                boundaryX + POSITIVE_MERGE_DX, 0);
    }

    /** The negative-siding curve: from the siding's east end to the second merge, further east. */
    public static Curve negative(int boundaryX) {
        return new Curve(boundaryX + CURVE_START_DX, -SIDING_DZ,
                boundaryX + NEGATIVE_MERGE_DX, 0);
    }

    /** The two curves of this depot. */
    public static java.util.List<Curve> all(int boundaryX) {
        return java.util.List.of(positive(boundaryX), negative(boundaryX));
    }

    /**
     * Horizontal unit tangent leaving the <b>siding</b> end, pointing east into the
     * curve. Create calls this {@code axis1}; the curve start is offset half a block
     * along it.
     */
    public static double[] fromTangent() {
        return new double[] {1.0D, 0.0D, 0.0D};
    }

    /**
     * Horizontal unit tangent leaving the <b>main-line</b> end, pointing west into the
     * curve (the curve lies to the west of both merge columns).
     */
    public static double[] toTangent() {
        return new double[] {-1.0D, 0.0D, 0.0D};
    }

    /**
     * Block-centre to curve-start offset used by {@code TrackBlock#getCurveStart}:
     * the curve starts half a block along its tangent from the block's centre, and the
     * track floor sits half a block below the block centre.
     */
    public static double[] curveStart(double[] blockCentredPosition, double[] tangent) {
        return new double[] {
                blockCentredPosition[0] + tangent[0] * 0.5D,
                blockCentredPosition[1] + tangent[1] * 0.5D,
                blockCentredPosition[2] + tangent[2] * 0.5D,
        };
    }

    /**
     * A curve is only plausible inside the depot if neither end sits inside a chassis
     * footprint box, and if its span stays inside the carved hall.
     */
    public static boolean insideCarvedHall(int boundaryX, Curve curve) {
        for (int[] end : new int[][] {{curve.fromX(), curve.fromZ()}, {curve.toX(), curve.toZ()}}) {
            int dx = end[0] - boundaryX;
            if (dx < StationDepotSites.CAVERN_MIN_DX || dx > StationDepotSites.CAVERN_MAX_DX) {
                return false;
            }
            if (Math.abs(end[1]) > StationDepotSites.SIDING_DZ) {
                return false;
            }
        }
        return true;
    }

    /** Straight-line span of a curve, used as the upper bound for its actual arc length. */
    public static double chordLength(Curve curve) {
        double dx = curve.toX() - curve.fromX();
        double dz = curve.toZ() - curve.fromZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}