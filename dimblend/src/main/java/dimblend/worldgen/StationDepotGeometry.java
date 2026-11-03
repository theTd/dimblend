package dimblend.worldgen;

import dimblend.api.StationDepotCurves;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * R6 station depot ("起始站场") layout: a plain, deterministic description of the
 * blocks one surface/surface region boundary gets, so the worldgen pass writes
 * coordinates unit tests can pin and nobody has to eyeball in game.
 *
 * <p>The depot's <b>middle</b> sits on the boundary column {@code B} (a block X
 * where {@code X mod bandSize == 0}), which is what "中间对齐区域交界处" asks for.
 * Laterally it is centred on the main line at {@code z = 0} and spans
 * {@code z = -15 .. +15}. Vertically everything is expressed as {@code dy}
 * relative to {@link OakTrackCorridor#TRACK_Y}, so one instance serves every
 * boundary and both signs of X.
 *
 * <p><b>Why no curve is described here.</b> Create connects two track blocks only
 * when their node positions coincide exactly. A straight {@code XO} block exposes
 * nodes at {@code z + 0.5}, a diagonal {@code PD} block at integer corners, so a
 * direction change can never be assembled from adjacent straight or diagonal
 * blocks — Create itself only ever builds a turn as a {@code BezierConnection}
 * held by a {@code TrackBlockEntity}. A curve also has to join Create's track
 * graph, which a worker-thread decoration pass must not touch. So this class
 * describes only plain blocks; {@code StationDepotAssembly} on the server thread
 * builds the two merge curves and the switch. The coordinates both sides agree on
 * live in {@link StationDepotCurves}, and this class places the two main-line
 * landing blocks those curves attach to.
 *
 * <p><b>Deviations from the reference picture, recorded so they are not mistaken for
 * bugs.</b>
 * <ul>
 *   <li><b>The deck is one block below the rails</b> ({@code dy = -1}), not flush with
 *       them. A deck at rail height lands inside every chassis footprint box, and
 *       {@code ChassisSite.clear} then rejects the site, so the pre-placed Sable
 *       chassis could never be assembled. In game the platform therefore reads as a
 *       sunken apron around the track: the player stands one step below rail level.</li>
 *   <li><b>The platform is 30 wide on both sides of the main line</b> and carries a
 *       sealed stone shell. The hall is wider than the corridor's own 19-wide tunnel,
 *       so without that shell water and lava in the surrounding terrain flow in. The
 *       shell is necessary structure and is <em>not</em> part of the picture.</li>
 *   <li><b>Two separate merge points, not one.</b> Both sidings join the main line at
 *       staggered columns ({@link StationDepotCurves#POSITIVE_MERGE_DX} and
 *       {@link StationDepotCurves#NEGATIVE_MERGE_DX}) instead of converging on one
 *       block. One shared block would be a cross-shape junction whose two track axes
 *       sit at different heights; two plain turnouts are each a known-good straight
 *       block with a curve attached.</li>
 *   <li><b>Two stone-brick buffer stops, added 2026-11-02.</b> The picture has no buffer
 *       stops; the user asked for one at each siding's dead end, fireplace-shaped with the
 *       top open. They fill the two columns the hall keeps west of the rails.</li>
 * </ul>
 *
 * <p><b>Platform material banding.</b> The picture's cobble/stone-brick split is
 * reproduced as two lateral bands: cobblestone from the centre out to
 * {@link #BRICK_OUTER_DZ}, stone bricks beyond it.
 */
public final class StationDepotGeometry {
    /** Lateral offset of each siding from the main line. */
    public static final int SIDING_DZ = 10;
    /** A siding's straight run is 48 blocks: {@code B-24 .. B+23}. */
    public static final int SIDING_LENGTH = 48;
    public static final int SIDING_WEST_DX = -SIDING_LENGTH / 2;
    public static final int SIDING_EAST_DX = SIDING_LENGTH / 2 - 1;
    /** Length of the merge section east of the boundary column: the picture's 32. */
    public static final int MERGE_LENGTH = 32;
    /** Main-line column where the switch stands: the positive siding's curve landing. */
    public static final int JUNCTION_DX = StationDepotCurves.POSITIVE_MERGE_DX;
    /** Platform half-width: the picture's 30 wide deck is {@code z = -15 .. +15}. */
    public static final int PLATFORM_HALF_WIDTH = 15;
    /** Platform deck layer, one below the rails. */
    public static final int DECK_DY = -1;
    /** Deck columns at least this far from the centre are stone bricks. */
    public static final int BRICK_OUTER_DZ = 11;
    /**
     * Chamber west edge. The two sidings dead-end at {@link #SIDING_WEST_DX}, so this
     * leaves one column of open deck behind them (the buffer stop's mouth) plus the
     * column its brick back wall stands on. Before 2026-11-02 it reached
     * {@code SIDING_WEST_DX - 11}, i.e. eleven blocks of empty platform nobody wanted.
     */
    public static final int CAVERN_MIN_DX = SIDING_WEST_DX - 2;
    /**
     * Covers both curve landing columns, so the hall reaches the further merge, plus three
     * columns of clearance east of it. One column was added on 2026-11-02 because the cars
     * could not drive out of the hall with the wall one block off the last merge; the user
     * asked for two more the same day, after trying it in game. Derived from the curve
     * layout rather than repeated, so the two can never drift apart.
     */
    public static final int CAVERN_MAX_DX = StationDepotCurves.NEGATIVE_MERGE_DX + 3;
    /** Column the two dead ends stop at; the buffer stop's wings stand here. */
    public static final int BUFFER_MOUTH_DX = CAVERN_MIN_DX + 1;
    /** Column the buffer stop's back wall stands on: the chamber's west edge. */
    public static final int BUFFER_BACK_DX = CAVERN_MIN_DX;
    /** Buffer stop height above the rails. The top stays open, like a fireplace. */
    public static final int BUFFER_HEIGHT = 3;
    /**
     * Chamber height above the track floor: the corridor vault apex, so the hall is cut
     * all the way up to the tunnel ceiling. The user asked on 2026-11-03 for everything
     * above the structure to be air "up to the tunnel height, even through a mountain";
     * a lower ceiling left a rock lid over the hall.
     */
    public static final int CAVERN_APEX_DY = OakTrackCorridor.VAULT_APEX_DY;
    /** Four corner pillars, outside every chassis footprint box. */
    public static final int PILLAR_DX = 26;
    public static final int PILLAR_DZ = 14;
    public static final int LANTERN_DY = 2;
    /**
     * The workbench is gone. The requirement asked to move it to the mirror of the
     * switch machine across the track; the user's answer on 2026-11-03 was "remove the
     * workbench" (see the PRD's Corrections section), so the depot no longer writes one.
     */
    /** The one pre-placed Sable power chassis of every new world: x=32 on the main line. */
    public static final int POWER_CHASSIS_X = 32;
    public static final int POWER_CHASSIS_Z = OakTrackCorridor.CORRIDOR_Z;

    public enum Kind {
        /** Deck, plain cobblestone. */
        COBBLE,
        /** Deck edge, stone bricks. */
        BRICK,
        /** Corner pillar the lantern stands on, stone bricks. */
        PILLAR,
        /** Straight wide-gauge track running along X. */
        TRACK_XO,
        /** Lantern standing on a corner pillar. */
        LANTERN,
        /** Stone-brick buffer stop at a siding's dead end. */
        BUFFER
    }

    /** One block the worldgen pass writes. {@code z} is absolute; {@code dy} is relative to the track floor. */
    public record Cell(int x, int dy, int z, Kind kind) {
    }

    private StationDepotGeometry() {
    }

    /**
     * True when {@code blockX} is a region boundary column whose two sides are both
     * the surface lane. Walled boundaries (different lanes) are never touched: the
     * wall and its warp gate own those columns.
     */
    public static boolean isSurfaceBoundary(int blockX, int bandSize, IntFunction<String> laneNameOfRegion) {
        if (Math.floorMod(blockX, bandSize) != 0) {
            return false;
        }
        int region = Math.floorDiv(blockX, bandSize);
        return "surface".equals(laneNameOfRegion.apply(region - 1))
                && "surface".equals(laneNameOfRegion.apply(region));
    }

    /**
     * True when this boundary gets a depot. The spawn boundary at {@code X = 0} is
     * excluded: the starter depot and its bedrock wall live there, and a second
     * structure on that column is not what the picture asks for.
     */
    public static boolean buildsDepot(int blockX, int bandSize, IntFunction<String> laneNameOfRegion) {
        return blockX != 0 && isSurfaceBoundary(blockX, bandSize, laneNameOfRegion);
    }

    /** Chunk-local columns this depot can write into, as a closed block-X range. */
    public static int minBlockX(int boundaryX) {
        return boundaryX + CAVERN_MIN_DX;
    }

    public static int maxBlockX(int boundaryX) {
        return boundaryX + CAVERN_MAX_DX;
    }

    /** Block X on the main line where both curves meet and the switch stands. */
    public static int junctionX(int boundaryX) {
        return boundaryX + JUNCTION_DX;
    }

    /** Block X where each siding's straight run ends, i.e. where its merge curve begins. */
    public static int curveStartX(int boundaryX) {
        return boundaryX + SIDING_EAST_DX;
    }

    /**
     * The pass writes exactly the carved volume. The sealed shell was removed on
     * 2026-11-03: the user asked for the terrain to be cut open with no sealed edges.
     */
    public static int writeMinBlockX(int boundaryX) {
        return minBlockX(boundaryX);
    }

    public static int writeMaxBlockX(int boundaryX) {
        return maxBlockX(boundaryX);
    }

    /** Block span the pass may write in Z. */
    public static int writeMinBlockZ() {
        return -PLATFORM_HALF_WIDTH;
    }

    public static int writeMaxBlockZ() {
        return PLATFORM_HALF_WIDTH;
    }

    /**
     * True when the block at {@code (blockX, blockZ)} really lands inside the chunk's own
     * block span. {@code ChunkAccess#setBlockState} does not throw on a coordinate outside
     * the chunk: it wraps it with {@code & 15} and writes into an unrelated column of the
     * same chunk, so every write has to be filtered through this first.
     */
    public static boolean chunkContainsBlock(
            int blockX,
            int blockZ,
            int chunkMinBlockX,
            int chunkMaxBlockX,
            int chunkMinBlockZ,
            int chunkMaxBlockZ
    ) {
        return blockX >= chunkMinBlockX && blockX <= chunkMaxBlockX
                && blockZ >= chunkMinBlockZ && blockZ <= chunkMaxBlockZ;
    }

    /** True when the depot at {@code boundaryX} writes at least one block inside this chunk's X span. */
    public static boolean boundaryIntersectsChunkX(int boundaryX, int chunkMinBlockX, int chunkMaxBlockX) {
        return writeMaxBlockX(boundaryX) >= chunkMinBlockX && writeMinBlockX(boundaryX) <= chunkMaxBlockX;
    }

    /** True when any block of a depot can land inside this chunk's Z span. */
    public static boolean depotIntersectsChunkZ(int chunkMinBlockZ, int chunkMaxBlockZ) {
        return writeMaxBlockZ() >= chunkMinBlockZ && writeMinBlockZ() <= chunkMaxBlockZ;
    }

    /**
     * Every boundary column whose depot writes into this chunk's X span, ascending. A
     * depot is 76 blocks wide and a chunk is 16, so the caller must not assume one hit;
     * with {@link BandIndex#DEFAULT_BAND_SIZE} it is at most one, with a small band two.
     *
     * <p>Enumerating the boundaries whose <b>write span overlaps</b> the chunk is the
     * whole point: a boundary on the chunk's west edge is west of every boundary column
     * the chunk contains, so "the first boundary at or after the chunk's west edge" misses
     * it, and the west half of the hall is never built.
     */
    public static List<Integer> boundariesTouchingChunkX(int bandSize, int chunkMinBlockX, int chunkMaxBlockX) {
        List<Integer> boundaries = new ArrayList<>();
        if (bandSize <= 0 || chunkMaxBlockX < chunkMinBlockX) {
            return boundaries;
        }
        // A boundary b writes into [b + writeMinBlockX(0), b + writeMaxBlockX(0)], so it
        // can touch this chunk only when b >= chunkMinBlockX - writeMaxBlockX(0) and
        // b <= chunkMaxBlockX - writeMinBlockX(0). kFrom may name one boundary too far
        // west; the intersection test below drops it.
        int kFrom = (int) Math.floorDiv((long) chunkMinBlockX - writeMaxBlockX(0), bandSize);
        int kTo = (int) Math.floorDiv((long) chunkMaxBlockX - writeMinBlockX(0), bandSize);
        for (int k = kFrom; k <= kTo; k++) {
            long boundaryColumn = (long) k * (long) bandSize;
            if (boundaryColumn < Integer.MIN_VALUE || boundaryColumn > Integer.MAX_VALUE) {
                continue;
            }
            if (boundaryIntersectsChunkX((int) boundaryColumn, chunkMinBlockX, chunkMaxBlockX)) {
                boundaries.add((int) boundaryColumn);
            }
        }
        return boundaries;
    }

    /** Rail-mounted Create controls are separate blocks one above the rail. */
    public static int controlY() {
        return OakTrackCorridor.TRACK_Y + 1;
    }

    /** Carriage chassis centre: on the positive siding, centred on the boundary column. */
    public static int carriageChassisX(int boundaryX) {
        return boundaryX;
    }

    public static int carriageChassisZ() {
        return SIDING_DZ;
    }

    /** Chassis blueprints are placed one block above the rails. */
    public static int chassisY() {
        return OakTrackCorridor.TRACK_Y + 1;
    }

    /**
     * True when the depot carves this cell. The chamber can never reach the next
     * boundary column, never crosses {@code z = +-PLATFORM_HALF_WIDTH}, and never
     * rises past the corridor apex. It reaches one block below the rail so the deck
     * layer ({@code dy = -1}) has a cell of its own.
     */
    public static boolean carves(int boundaryX, int x, int dy, int z) {
        int dx = x - boundaryX;
        return dx >= CAVERN_MIN_DX && dx <= CAVERN_MAX_DX
                && Math.abs(z) <= PLATFORM_HALF_WIDTH
                && dy >= DECK_DY && dy <= CAVERN_APEX_DY;
    }

    /** True when {@code cell} belongs to the depot floor rather than its furniture. */
    public static boolean isDeck(Cell cell) {
        return cell.kind() == Kind.COBBLE || cell.kind() == Kind.BRICK;
    }

    /** Every block the worldgen pass writes at this boundary. */
    public static List<Cell> placedCells(int boundaryX) {
        List<Cell> cells = new ArrayList<>();
        addDeck(cells, boundaryX);
        addSidings(cells, boundaryX);
        addBuffers(cells, boundaryX);
        addFurniture(cells, boundaryX);
        return cells;
    }

    private static void addDeck(List<Cell> cells, int boundaryX) {
        for (int z = -PLATFORM_HALF_WIDTH; z <= PLATFORM_HALF_WIDTH; z++) {
            Kind kind = Math.abs(z) >= BRICK_OUTER_DZ ? Kind.BRICK : Kind.COBBLE;
            for (int dx = CAVERN_MIN_DX; dx <= CAVERN_MAX_DX; dx++) {
                cells.add(new Cell(boundaryX + dx, DECK_DY, z, kind));
            }
        }
    }

    private static void addSidings(List<Cell> cells, int boundaryX) {
        for (int side : new int[] {-1, 1}) {
            int z = side * SIDING_DZ;
            for (int dx = SIDING_WEST_DX; dx <= SIDING_EAST_DX; dx++) {
                cells.add(new Cell(boundaryX + dx, 0, z, Kind.TRACK_XO));
            }
        }
        // The two curve landing blocks on the main line. Create attaches a
        // BezierConnection to a real TrackBlockEntity, so the main-line end of each
        // curve has to exist as a track block before the server-thread fit-out runs.
        // OakTrackCorridor already writes one continuous XO row at z=0, so these are
        // normally already there; writing them again is idempotent and makes the
        // depot self-sufficient if the row was ever interrupted.
        cells.add(new Cell(boundaryX + StationDepotCurves.POSITIVE_MERGE_DX, 0,
                OakTrackCorridor.CORRIDOR_Z, Kind.TRACK_XO));
        cells.add(new Cell(boundaryX + StationDepotCurves.NEGATIVE_MERGE_DX, 0,
                OakTrackCorridor.CORRIDOR_Z, Kind.TRACK_XO));
    }

    /**
     * A stone-brick buffer stop at each siding's dead end: a back wall three wide, two
     * one-block wings one column nearer the track, and the top left open — the fireplace
     * shape the user drew. The mouth (the middle column of the wings) is the one block of
     * open deck behind the rails.
     */
    private static void addBuffers(List<Cell> cells, int boundaryX) {
        for (int side : new int[] {-1, 1}) {
            int z = side * SIDING_DZ;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy < BUFFER_HEIGHT; dy++) {
                    cells.add(new Cell(boundaryX + BUFFER_BACK_DX, dy, z + dz, Kind.BUFFER));
                }
            }
            for (int dz : new int[] {-1, 1}) {
                for (int dy = 0; dy < BUFFER_HEIGHT; dy++) {
                    cells.add(new Cell(boundaryX + BUFFER_MOUTH_DX, dy, z + dz, Kind.BUFFER));
                }
            }
        }
    }

    private static void addFurniture(List<Cell> cells, int boundaryX) {
        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                int x = boundaryX + sx * PILLAR_DX;
                int z = sz * PILLAR_DZ;
                for (int dy = 0; dy < LANTERN_DY; dy++) {
                    cells.add(new Cell(x, dy, z, Kind.PILLAR));
                }
                cells.add(new Cell(x, LANTERN_DY, z, Kind.LANTERN));
            }
        }
    }
}