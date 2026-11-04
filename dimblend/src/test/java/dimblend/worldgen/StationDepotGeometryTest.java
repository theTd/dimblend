package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.StationDepotCurves;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * R6 station depot layout, pinned as pure data. Every coordinate here is a block
 * the worldgen pass must write, so a drift in the layout is a test failure rather
 * than something somebody has to notice in game.
 */
class StationDepotGeometryTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;
    private static final int B = 2048;

    /** Scripted lanes: -2..1 surface, 2..3 underground. */
    private static final IntFunction<String> LANES = region -> switch (region) {
        case -2, -1, 0, 1 -> "surface";
        case 2, 3 -> "underground";
        default -> "nether";
    };

    @Test
    void onlyASurfaceSurfaceBoundaryCarriesADepot() {
        assertTrue(StationDepotGeometry.isSurfaceBoundary(2048, BAND, LANES), "regions 0/1 both surface");
        assertTrue(StationDepotGeometry.isSurfaceBoundary(0, BAND, LANES), "regions -1/0 both surface");
        assertTrue(StationDepotGeometry.isSurfaceBoundary(-2048, BAND, LANES), "regions -2/-1 both surface");
        assertFalse(StationDepotGeometry.isSurfaceBoundary(4096, BAND, LANES), "surface/underground is walled");
        assertFalse(StationDepotGeometry.isSurfaceBoundary(6144, BAND, LANES), "underground/surface is walled");
        assertFalse(StationDepotGeometry.isSurfaceBoundary(2049, BAND, LANES), "not a boundary column");
    }

    @Test
    void theSpawnBoundaryIsSkippedSoTheStarterDepotKeepsItsWall() {
        assertFalse(StationDepotGeometry.buildsDepot(0, BAND, LANES), "X=0 keeps the starter depot wall at X=-16");
        assertTrue(StationDepotGeometry.buildsDepot(2048, BAND, LANES));
        assertTrue(StationDepotGeometry.buildsDepot(-2048, BAND, LANES));
        assertFalse(StationDepotGeometry.buildsDepot(4096, BAND, LANES));
    }

    @Test
    void thePlatformIsThirtyWideAndCentredOnTheMainLine() {
        List<StationDepotGeometry.Cell> cells = StationDepotGeometry.placedCells(B);
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (var cell : cells) {
            if (!StationDepotGeometry.isDeck(cell)) {
                continue;
            }
            assertEquals(StationDepotGeometry.DECK_DY, cell.dy(), "the deck is one layer");
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }
        assertEquals(-15, minZ, "platform starts at z=-15");
        assertEquals(15, maxZ, "platform ends at z=+15");
    }

    @Test
    void theDeckIsCobbleInTheMiddleAndStoneBricksAtTheEdge() {
        List<StationDepotGeometry.Cell> cells = StationDepotGeometry.placedCells(B);
        for (var cell : cells) {
            if (!StationDepotGeometry.isDeck(cell)) {
                continue;
            }
            var expected = Math.abs(cell.z()) >= StationDepotGeometry.BRICK_OUTER_DZ
                    ? StationDepotGeometry.Kind.BRICK
                    : StationDepotGeometry.Kind.COBBLE;
            assertEquals(expected, cell.kind(), "banding at z=" + cell.z());
        }
    }

    @Test
    void theDeckSitsBelowTheRailsSoItNeverEntersAChassisFootprint() {
        assertEquals(-1, StationDepotGeometry.DECK_DY);
        for (var cell : StationDepotGeometry.placedCells(B)) {
            if (!StationDepotGeometry.isDeck(cell)) {
                continue;
            }
            assertFalse(cell.dy() >= 0, "no deck block at rail level or above: " + cell);
        }
    }

    @Test
    void bothSidingsRunFortyEightBlocksAlongTheMainLine() {
        List<StationDepotGeometry.Cell> cells = StationDepotGeometry.placedCells(B);
        for (int side : new int[] {-1, 1}) {
            int z = side * StationDepotGeometry.SIDING_DZ;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (var cell : cells) {
                if (cell.kind() != StationDepotGeometry.Kind.TRACK_XO || cell.z() != z) {
                    continue;
                }
                min = Math.min(min, cell.x());
                max = Math.max(max, cell.x());
            }
            assertEquals(B - 24, min, "siding " + side + " starts at B-24");
            assertEquals(B + 23, max, "siding " + side + " ends at B+23");
            assertEquals(48, max - min + 1, "siding " + side + " is 48 blocks long");
        }
    }

    @Test
    void thePictureFurnitureIsFourLanternsAndNoWorkbench() {
        List<StationDepotGeometry.Cell> cells = StationDepotGeometry.placedCells(B);
        assertEquals(4, count(cells, StationDepotGeometry.Kind.LANTERN));
        // The requirement asked to move the workbench across the track from the switch machine;
        // the user's 2026-11-03 answer was to remove it instead (PRD Corrections). The WORKBENCH
        // kind no longer exists, so the absence is pinned through the kinds that remain: every
        // furniture cell must be a lantern or the pillar under it.
        for (var cell : cells) {
            boolean furniture = cell.kind() == StationDepotGeometry.Kind.LANTERN
                    || cell.kind() == StationDepotGeometry.Kind.PILLAR;
            assertTrue(furniture || cell.kind() == StationDepotGeometry.Kind.COBBLE
                            || cell.kind() == StationDepotGeometry.Kind.BRICK
                            || cell.kind() == StationDepotGeometry.Kind.BUFFER
                            || cell.kind() == StationDepotGeometry.Kind.TRACK_XO,
                    "unexpected furniture cell: " + cell);
        }
    }

    @Test
    void everyLanternStandsOnItsOwnPillarClearOfTheSidings() {
        for (var cell : StationDepotGeometry.placedCells(B)) {
            if (cell.kind() != StationDepotGeometry.Kind.LANTERN) {
                continue;
            }
            assertEquals(StationDepotGeometry.LANTERN_DY, cell.dy());
            assertTrue(Math.abs(cell.z()) != StationDepotGeometry.SIDING_DZ, "no lantern on a siding");
            assertTrue(Math.abs(cell.z()) < StationDepotGeometry.PLATFORM_HALF_WIDTH, "lantern on the deck");
        }
    }

    @Test
    void theCarriageGoesOnThePositiveSidingAndTheNegativeOneStaysEmpty() {
        assertEquals(B, StationDepotGeometry.carriageChassisX(B), "the carriage is centred on the boundary");
        assertEquals(StationDepotGeometry.SIDING_DZ, StationDepotGeometry.carriageChassisZ(),
                "the positive siding carries it");
        assertEquals(OakTrackCorridor.TRACK_Y + 1, StationDepotGeometry.chassisY());
        assertTrue(StationDepotGeometry.carriageChassisZ() > 0, "user chose the Z+ side");
    }

    @Test
    void theCarriageFootprintFitsInsideTheSidingSoAssemblyCanSucceed() {
        int centreX = StationDepotGeometry.carriageChassisX(B);
        int centreZ = StationDepotGeometry.carriageChassisZ();
        // Chassis site box: x +-24, z +-3 or +-4, y .. y+2 (see ChassisSite.bounds).
        assertTrue(Math.abs(centreZ + 3) != StationDepotGeometry.SIDING_DZ
                        && Math.abs(centreZ - 3) != StationDepotGeometry.SIDING_DZ,
                "the box itself may only hold its own blocks and track");
        for (var cell : StationDepotGeometry.placedCells(B)) {
            if (!StationDepotGeometry.isDeck(cell) && cell.kind() != StationDepotGeometry.Kind.TRACK_XO) {
                continue;
            }
            boolean insideBox = Math.abs(cell.x() - centreX) <= 24
                    && Math.abs(cell.z() - centreZ) <= 3
                    && cell.dy() >= 0 && cell.dy() <= 2;
            assertFalse(insideBox && StationDepotGeometry.isDeck(cell),
                    "deck inside the chassis box would block assembly: " + cell);
        }
    }

    @Test
    void noFurnitureStandsInsideAChassisFootprintBox() {
        // The box the assembly checks is x +-24, |z - centreZ| <= 4, y .. y+2 around
        // each centre. A pillar or workbench in there makes assembly fail forever,
        // and it fails silently, so it is pinned here.
        for (var cell : StationDepotGeometry.placedCells(B)) {
            if (StationDepotGeometry.isDeck(cell) || cell.kind() == StationDepotGeometry.Kind.TRACK_XO) {
                continue;
            }
            assertFalse(insideChassisBox(StationDepotGeometry.carriageChassisX(B),
                            StationDepotGeometry.carriageChassisZ(), cell),
                    "furniture inside the carriage box blocks assembly: " + cell);
        }
    }

    @Test
    void thePowerChassisBoxIsClearAtX32() {
        // The power chassis sits at x=32 on the main line, which the depot at X=0 would
        // overlap, but X=0 is excluded. Pinned so a future boundary change cannot put
        // depot furniture into that box.
        for (var cell : StationDepotGeometry.placedCells(StationDepotGeometry.POWER_CHASSIS_X)) {
            if (StationDepotGeometry.isDeck(cell) || cell.kind() == StationDepotGeometry.Kind.TRACK_XO) {
                continue;
            }
            assertFalse(insideChassisBox(StationDepotGeometry.POWER_CHASSIS_X,
                            StationDepotGeometry.POWER_CHASSIS_Z, cell),
                    "furniture inside the power box blocks assembly: " + cell);
        }
    }

    private static boolean insideChassisBox(int centreX, int centreZ, StationDepotGeometry.Cell cell) {
        return Math.abs(cell.x() - centreX) <= 24
                && Math.abs(cell.z() - centreZ) <= 4
                && cell.dy() >= 0 && cell.dy() <= 2;
    }

    @Test
    void thePowerChassisSitsAtX32OnTheMainLine() {
        assertEquals(32, StationDepotGeometry.POWER_CHASSIS_X);
        assertEquals(OakTrackCorridor.CORRIDOR_Z, StationDepotGeometry.POWER_CHASSIS_Z);
        assertEquals(OakTrackCorridor.TRACK_Y + 1, StationDepotGeometry.chassisY());
    }

    @Test
    void everyPlacedCellIsInsideTheCarvedChamber() {
        for (var cell : StationDepotGeometry.placedCells(B)) {
            assertTrue(StationDepotGeometry.carves(B, cell.x(), cell.dy(), cell.z()),
                    "cell outside the carve: " + cell);
        }
    }

    @Test
    void everyPlacedCellSitsInsideTheChunkSpanThePassCanWrite() {
        int min = StationDepotGeometry.minBlockX(B);
        int max = StationDepotGeometry.maxBlockX(B);
        for (var cell : StationDepotGeometry.placedCells(B)) {
            assertTrue(cell.x() >= min && cell.x() <= max, "cell outside the write span: " + cell);
        }
    }

    @Test
    void theChamberStopsShortOfTheNextBoundaryAndOfTheVaultCeiling() {
        assertTrue(StationDepotGeometry.carves(B, B, 0, 0));
        assertTrue(StationDepotGeometry.carves(B, B + StationDepotGeometry.CAVERN_MAX_DX, 0, 15));
        assertTrue(StationDepotGeometry.carves(B, B + StationDepotGeometry.CAVERN_MIN_DX, 0, -15));
        assertTrue(StationDepotGeometry.carves(B, B, StationDepotGeometry.DECK_DY, 0),
                "the deck layer is inside the carve");
        assertFalse(StationDepotGeometry.carves(B, B + StationDepotGeometry.CAVERN_MIN_DX - 1, 0, 0));
        assertFalse(StationDepotGeometry.carves(B, B + StationDepotGeometry.CAVERN_MAX_DX + 1, 0, 0));
        assertFalse(StationDepotGeometry.carves(B, B, 0, 16));
        assertFalse(StationDepotGeometry.carves(B, B, StationDepotGeometry.CAVERN_APEX_DY + 1, 0));
        assertFalse(StationDepotGeometry.carves(B, B, StationDepotGeometry.DECK_DY - 1, 0),
                "nothing below the deck layer");
    }

    @Test
    void theChamberNeverReachesTheNextBoundaryColumn() {
        int boundary = 2048;
        int next = boundary + BAND;
        int previous = boundary - BAND;
        assertTrue(StationDepotGeometry.maxBlockX(boundary) < next, "cannot touch X=4096");
        assertTrue(StationDepotGeometry.minBlockX(boundary) > previous, "cannot touch X=0");
    }

    /**
     * 2026-11-02. The hall used to run eleven blocks past the dead ends, and to stop one
     * column short of the further merge. The user asked for one open column behind each
     * buffer stop, then for clearance east of the merge: one column, and two more after
     * trying it in game, because the cars still could not drive out.
     */
    @Test
    void theDeadEndsKeepOneOpenColumnAndTheEastEndClearsTheLastMerge() {
        assertEquals(StationDepotGeometry.SIDING_WEST_DX - 1, StationDepotGeometry.BUFFER_MOUTH_DX,
                "one open column sits between the rail end and the buffer stop");
        assertEquals(StationDepotGeometry.SIDING_WEST_DX - 2, StationDepotGeometry.BUFFER_BACK_DX,
                "the buffer stop's back wall stands on the chamber's west edge");
        assertFalse(StationDepotGeometry.carves(B, B + StationDepotGeometry.SIDING_WEST_DX - 3, 0, 0),
                "nothing is carved further west than the back wall");
        assertEquals(StationDepotCurves.NEGATIVE_MERGE_DX + 3, StationDepotGeometry.CAVERN_MAX_DX,
                "the east edge clears the further merge by three columns");
        assertTrue(StationDepotGeometry.carves(B, B + StationDepotCurves.NEGATIVE_MERGE_DX + 3, 0, 0));
        assertFalse(StationDepotGeometry.carves(B, B + StationDepotGeometry.CAVERN_MAX_DX + 1, 0, 0));
        // 2026-11-03: the sealed shell is gone, so the write span no longer reaches one column past
        // the carve. It ends exactly where the hall ends.
        assertEquals(B + StationDepotGeometry.CAVERN_MAX_DX, StationDepotGeometry.writeMaxBlockX(B),
                "no shell column past the east edge any more");
        assertEquals(B + StationDepotGeometry.CAVERN_MIN_DX, StationDepotGeometry.writeMinBlockX(B),
                "and none past the west edge");
    }

    @Test
    void eachDeadEndGetsAFireplaceShapedBufferStop() {
        List<StationDepotGeometry.Cell> cells = StationDepotGeometry.placedCells(B);
        int back = B + StationDepotGeometry.BUFFER_BACK_DX;
        int mouth = B + StationDepotGeometry.BUFFER_MOUTH_DX;
        for (int side : new int[] {-1, 1}) {
            int z = side * StationDepotGeometry.SIDING_DZ;
            for (int dy = 0; dy < StationDepotGeometry.BUFFER_HEIGHT; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    assertEquals(StationDepotGeometry.Kind.BUFFER, kindAt(cells, back, dy, z + dz),
                            "back wall side=" + side + " dy=" + dy + " dz=" + dz);
                    assertEquals(dz == 0 ? null : StationDepotGeometry.Kind.BUFFER,
                            kindAt(cells, mouth, dy, z + dz),
                            "wings leave the mouth open, side=" + side + " dy=" + dy + " dz=" + dz);
                }
                assertNull(kindAt(cells, back, StationDepotGeometry.BUFFER_HEIGHT, z),
                        "the top stays open, side=" + side + " dy=" + StationDepotGeometry.BUFFER_HEIGHT);
            }
        }
        assertEquals(2 * (3 * 3 + 2 * 3), count(cells, StationDepotGeometry.Kind.BUFFER),
                "two buffer stops, each a 3x3 back wall plus four wing columns");
    }

    private static StationDepotGeometry.Kind kindAt(
            List<StationDepotGeometry.Cell> cells, int x, int dy, int z) {
        return cells.stream()
                .filter(cell -> cell.x() == x && cell.dy() == dy && cell.z() == z)
                .map(StationDepotGeometry.Cell::kind)
                .findFirst()
                .orElse(null);
    }

    @Test
    void theSwitchSitsOnTheMainLineWhereBothCurvesMeet() {
        int junction = StationDepotGeometry.junctionX(B);
        assertEquals(B + StationDepotGeometry.JUNCTION_DX, junction,
                "the switch stands at the positive-siding merge column");
        assertEquals(OakTrackCorridor.CORRIDOR_Z, 0, "the switch targets the main line");
        assertEquals(OakTrackCorridor.TRACK_Y + 1, StationDepotGeometry.controlY(),
                "rail-mounted controls sit one above the rail");
        assertTrue(StationDepotGeometry.carves(B, junction, 0, 0), "the junction is inside the carve");
        assertEquals(StationDepotGeometry.SIDING_EAST_DX, 23, "siding ends at B+23");
        assertTrue(StationDepotGeometry.JUNCTION_DX > StationDepotGeometry.SIDING_EAST_DX,
                "the curve starts east of the siding's straight run");
    }

    private static long count(List<StationDepotGeometry.Cell> cells, StationDepotGeometry.Kind kind) {
        return cells.stream().filter(cell -> cell.kind() == kind).count();
    }
}