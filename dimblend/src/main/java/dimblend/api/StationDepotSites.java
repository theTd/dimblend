package dimblend.api;

import dimblend.worldgen.StationDepotGeometry;
import java.util.function.IntFunction;

/**
 * Facade over the R6 station depot layout for {@code dimblend-experience}.
 *
 * <p>The depot blocks are written by {@code dimblend}'s worldgen pass; the two
 * pre-placed Sable chassis and the depot's track furniture are handed over on the
 * server thread by experience. Both sides must agree on the same coordinates, so
 * they agree here rather than each keeping its own copy. Kept in {@code api} for
 * the same reason {@link CorridorRails} is: experience reads worldgen facts
 * through a small facade instead of importing the generator.
 */
public final class StationDepotSites {
    /** The one power chassis of every new world: x = 32 on the main line. */
    public static final int POWER_CHASSIS_X = StationDepotGeometry.POWER_CHASSIS_X;
    public static final int POWER_CHASSIS_Z = StationDepotGeometry.POWER_CHASSIS_Z;
    /** Carriage chassis lateral offset: the positive Z siding (the negative one stays empty). */
    public static final int CARRIAGE_DZ = StationDepotGeometry.SIDING_DZ;
    /** Lateral offset of each siding from the main line. */
    public static final int SIDING_DZ = StationDepotGeometry.SIDING_DZ;
    /** One siding's straight run length. */
    public static final int SIDING_LENGTH = StationDepotGeometry.SIDING_LENGTH;
    /** Chassis blueprints are placed one block above the rails. */
    public static final int CHASSIS_Y = StationDepotGeometry.chassisY();
    /** Chamber extent along X, relative to the boundary column, for the fit-out's range checks. */
    public static final int CAVERN_MIN_DX = StationDepotGeometry.CAVERN_MIN_DX;
    public static final int CAVERN_MAX_DX = StationDepotGeometry.CAVERN_MAX_DX;

    private StationDepotSites() {
    }

    /** True when {@code blockX} is a boundary whose two sides are both the surface lane. */
    public static boolean isSurfaceBoundary(int blockX, int bandSize, IntFunction<String> laneNameOfRegion) {
        return StationDepotGeometry.isSurfaceBoundary(blockX, bandSize, laneNameOfRegion);
    }

    /** True when this boundary carries a depot; the spawn boundary at X=0 does not. */
    public static boolean buildsDepot(int blockX, int bandSize, IntFunction<String> laneNameOfRegion) {
        return StationDepotGeometry.buildsDepot(blockX, bandSize, laneNameOfRegion);
    }

    /** Carriage chassis centre X: centred on the boundary column. */
    public static int carriageChassisX(int boundaryX) {
        return StationDepotGeometry.carriageChassisX(boundaryX);
    }

    /** Carriage chassis centre Z: the positive siding. */
    public static int carriageChassisZ() {
        return StationDepotGeometry.carriageChassisZ();
    }

    /** Main-line block X where both merge curves meet and the switch stands. */
    public static int junctionX(int boundaryX) {
        return StationDepotGeometry.junctionX(boundaryX);
    }

    /** Block X where a siding's straight run ends, i.e. where its merge curve starts. */
    public static int curveStartX(int boundaryX) {
        return StationDepotGeometry.curveStartX(boundaryX);
    }

    /** Rail-mounted controls sit one block above the rail. */
    public static int controlY() {
        return StationDepotGeometry.controlY();
    }
}