package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.StationDepotCurves;
import dimblend.api.StationDepotSites;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The depot junction furniture and the two chassis sites have to agree with worldgen to
 * the block.
 */
class StationDepotSitesTest {
    private static final int B = 2048;

    /**
     * The switch machine stands beside the junction, never in a vehicle's way.
     *
     * <p>The switch first stood one block above the junction column and a user reported on
     * 2026-11-01 that it stopped the car — correct, because Railways' andesite switch is a
     * solid block (CRShapes:29 fills the whole footprint at 0..5px plus a post to 15px, and
     * TrackSwitchBlock#getCollisionShape returns it because CRBlocks:179 never calls
     * noCollission()). On 2026-11-02 the user said the switch is still wanted, only its
     * lever must not block the cars. So the block stays, the column it stands in moves out:
     * one block beyond the chassis's own lateral clearance ({@code SAFE_Z}), which is the
     * first column no car launched by this depot can reach.
     */
    @Test
    void theSwitchMachineStandsOutsideEveryVehicleFootprint() {
        assertTrue(StationDepotAssemblyData.fitsSwitch(), "the depot places its switch");
        BlockPos machine = StationDepotSwitch.machine(B);
        BlockPos target = StationDepotSwitch.target(B);
        assertEquals(StationDepotSites.CHASSIS_Y - 1, machine.getY(),
                "the machine stands on the depot floor, not one block above the rails");
        assertEquals(StationDepotSites.POWER_CHASSIS_Z, target.getZ(), "it works the main-line rail");
        assertTrue(Math.abs(machine.getZ()) > ChassisBlueprint.SAFE_Z,
                "the machine's column must lie outside the widest car's lateral clearance");
        assertEquals(ChassisBlueprint.SAFE_Z + 1 + StationDepotSwitch.MACHINE_DZ_SHIFT,
                Math.abs(machine.getZ()),
                "2026-11-03: the user moved the machine three blocks further out, so the column is"
                        + " SAFE_Z + 1 + 3, not the first free column any more");
        assertTrue(Math.abs(machine.getZ()) < StationDepotSites.SIDING_DZ - 2,
                "and it still has to stay outside the parked car's five-wide footprint");
        assertEquals(target.getX(), machine.getX(),
                "the machine stands in the same column as the rail it points its rod at");
    }

    /**
     * The switch works the carriage siding's merge — the junction the pre-built car uses.
     *
     * <p>A Railways switch disables every exit but the selected one
     * ({@code TrackSwitch#updateEdges}: {@code setEnabled(forceActive || getSwitchTarget() == to)})
     * and starts on the straight exit, so this machine closes the car's branch until somebody
     * throws it. That is the user's choice of 2026-11-02, taken after seeing the machine work
     * the empty siding: the switch belongs on the junction the depot's car actually uses.
     */
    @Test
    void theSwitchWorksTheCarriageSidingsMerge() {
        assertTrue(StationDepotCurves.POSITIVE_MERGE_DX != StationDepotCurves.NEGATIVE_MERGE_DX,
                "the two merges are staggered, so one switch can only ever serve one of them");
        assertEquals(B + StationDepotCurves.POSITIVE_MERGE_DX, StationDepotSwitch.target(B).getX());
        assertEquals(B + StationDepotCurves.POSITIVE_MERGE_DX, StationDepotSwitch.machine(B).getX());
        assertTrue(StationDepotSites.CARRIAGE_DZ > 0, "the car parks on the positive side");
        assertTrue(StationDepotSwitch.machine(B).getZ() > 0,
                "and the machine stands on that same side, not across the running line from its work");
    }

    /**
     * The machine moved three blocks toward positive Z on 2026-11-03. It must stay clear of a car
     * parked on the positive siding: that car is five wide and centred at {@code SIDING_DZ}, so its
     * near edge is {@code SIDING_DZ - 2}. A switch inside that footprint stops the car, which is
     * exactly what the first version did when it stood above the rails.
     */
    @Test
    void theSwitchMachineMovedThreeBlocksAndClearOfTheParkedCar() {
        assertEquals(ChassisBlueprint.SAFE_Z + 1 + 3, StationDepotSwitch.machine(B).getZ(),
                "the machine is three blocks further along +Z than the first placement");
        int carNearEdge = StationDepotSites.SIDING_DZ - 2;
        assertTrue(StationDepotSwitch.machine(B).getZ() < carNearEdge,
                "the machine must stay outside the parked car's footprint: machine z="
                        + StationDepotSwitch.machine(B).getZ() + " car near edge z=" + carNearEdge);
    }

    @Test
    void theSidingSiteIsThePositiveSidingAndTheNegativeOneIsEmpty() {
        BlockPos siding = StationDepotAssemblyData.sidingCenter(B);
        assertEquals(B, siding.getX(), "centred on the boundary column");
        assertEquals(StationDepotSites.CHASSIS_Y, siding.getY(), "one block above the rail");
        assertEquals(StationDepotSites.SIDING_DZ, siding.getZ(), "positive Z side");
        assertTrue(siding.getZ() > 0);
        assertTrue(StationDepotSites.SIDING_DZ > 0);
    }

    /**
     * The two cars the world hands out are not the same shell any more.
     *
     * <p>On 2026-11-02 the user asked for the carriage shell back at both sites, so both were
     * CARRIAGE and the drive came from the assembly telling the end bogeys to pull. On
     * 2026-11-03 the user reported that the X = 32 car must be a <b>power chassis</b> (动力底盘) and
     * is a carriage chassis instead; that is this test's subject. The siding car keeps the
     * carriage shell — the user asked for that one explicitly — and still drives, because
     * {@code drivesEndBogeys} wakes its end bogeys.
     */
    @Test
    void theSidingCarIsTheCarriageShellAndTheFreeCarIsThePowerChassis() {
        assertEquals(ChassisBlueprint.CARRIAGE, StationDepotAssemblyData.sidingBlueprint(),
                "the siding car keeps the carriage shell the user asked for");
        assertEquals(ChassisBlueprint.POWER, StationDepotAssemblyData.powerBlueprint(),
                "the free car at X=32 is the power chassis the requirement names");
        float carriageStress = ChassisBlueprint
                .bogeyTuning(ChassisBlueprint.CARRIAGE, 17, StationDepotAssemblyData.drivesEndBogeys())
                .orElseThrow().stress();
        assertTrue(carriageStress > 0.0F, "the siding car must still produce drive force");
        assertEquals(0.0F, ChassisBlueprint.bogeyTuning(ChassisBlueprint.CARRIAGE, 17).orElseThrow().stress(),
                0.0F, "a printed carriage stays dead-hauled");
        // The power chassis pulls from its own blueprint, so it needs no driveEnds override.
        assertEquals(ChassisBlueprint.bogeyTuning(ChassisBlueprint.POWER, 17).orElseThrow(),
                ChassisBlueprint.bogeyTuning(ChassisBlueprint.POWER, 17, StationDepotAssemblyData.drivesEndBogeys())
                        .orElseThrow(),
                "driving the end bogeys changes nothing on a power chassis");
    }

    @Test
    void thePowerSiteIsAtX32OnTheMainLine() {
        BlockPos power = StationDepotAssemblyData.powerCenter();
        assertEquals(32, power.getX());
        assertEquals(StationDepotSites.CHASSIS_Y, power.getY());
        assertEquals(0, power.getZ());
    }

    /**
     * 2026-11-02. Two depots in one world deleted each other's switch machine for ever: 1192
     * block changes spread over 39 seconds, then Create's {@code create:sync_rail_graph}
     * payload threw {@code ConcurrentModificationException} while the Netty thread encoded
     * it, and the player was dropped out of the world. Retirement is now scoped to the hall
     * one boundary owns.
     */
    @Test
    void aDepotOnlyRetiresItsOwnSwitchMachine() {
        assertTrue(StationDepotAssembly.isMachineOf(8192, StationDepotSwitch.machine(8192)));
        assertTrue(StationDepotAssembly.isMachineOf(2048, StationDepotSwitch.machine(2048)));
        assertFalse(StationDepotAssembly.isMachineOf(8192, StationDepotSwitch.machine(2048)),
                "the neighbouring depot's machine is that depot's business");
        assertFalse(StationDepotAssembly.isMachineOf(2048, StationDepotSwitch.machine(8192)));
    }

    @Test
    void theSpawnBoundaryCarriesNoDepotButTheNextSurfaceBoundaryDoes() {
        var lanes = (java.util.function.IntFunction<String>) region -> switch (region) {
            case -2, -1, 0, 1 -> "surface";
            case 2, 3 -> "underground";
            default -> "nether";
        };
        assertTrue(StationDepotSites.buildsDepot(2048, 2048, lanes));
        assertTrue(StationDepotSites.buildsDepot(-2048, 2048, lanes));
        assertEquals(false, StationDepotSites.buildsDepot(0, 2048, lanes), "X=0 keeps the starter depot");
        assertEquals(false, StationDepotSites.buildsDepot(4096, 2048, lanes), "surface/underground is walled");
    }

    @Test
    void junctionChunksLoadedCoversFullCurveAndSwitchSpanBeyondChassisSiteLoaded() {
        // When only ChassisSite.loaded chunks (center +-24, z = 7..13) are loaded,
        // the switch chunk at (B + 43) >> 4 = 130 and the negative siding at cz = -1 are NOT covered.
        StationDepotAssembly.ChunkReady chassisOnly = (cx, cz) ->
                cx >= ((B - 24) >> 4) && cx <= ((B + 24) >> 4) && cz == 0;
        assertFalse(StationDepotAssembly.junctionChunksLoaded(B, chassisOnly),
                "junction chunks extend into cx=130 and cz=-1, beyond ChassisSite.loaded");

        int minX = B + StationDepotCurves.CURVE_START_DX - 1;
        int maxX = B + StationDepotCurves.NEGATIVE_MERGE_DX + 2;
        int minZ = -StationDepotSites.SIDING_DZ - 1;
        int maxZ = StationDepotSites.SIDING_DZ + 1;
        StationDepotAssembly.ChunkReady allJunction = (cx, cz) ->
                cx >= (minX >> 4) && cx <= (maxX >> 4) && cz >= (minZ >> 4) && cz <= (maxZ >> 4);
        assertTrue(StationDepotAssembly.junctionChunksLoaded(B, allJunction),
                "all curve and switch chunks (including 1-block neighbor margin) loaded returns true");

        // Missing curve start chunk
        assertFalse(StationDepotAssembly.junctionChunksLoaded(B,
                (cx, cz) -> allJunction.isLoaded(cx, cz) && !(cx == ((B + StationDepotCurves.CURVE_START_DX) >> 4) && cz == 0)));
        // Missing negative siding chunk (cz = -1)
        assertFalse(StationDepotAssembly.junctionChunksLoaded(B,
                (cx, cz) -> allJunction.isLoaded(cx, cz) && cz != -1));
        // Missing east neighbor chunk of NEGATIVE_MERGE_DX
        assertFalse(StationDepotAssembly.junctionChunksLoaded(B,
                (cx, cz) -> allJunction.isLoaded(cx, cz) && !(cx == (maxX >> 4) && cz == 0)));
    }

    @Test
    void blockAndNeighborsLoadedChecksOneBlockBorderAcrossChunkBoundaries() {
        BlockPos chunkEdge = new BlockPos(16, 64, 0); // cx = 1, x - 1 = 15 (cx = 0)
        assertTrue(StationDepotAssembly.blockAndNeighborsLoaded(chunkEdge, (cx, cz) -> true));
        assertFalse(StationDepotAssembly.blockAndNeighborsLoaded(chunkEdge, (cx, cz) -> cx != 0),
                "missing left neighbor chunk cx=0 at chunk edge must return false");
        assertFalse(StationDepotAssembly.blockAndNeighborsLoaded(chunkEdge, (cx, cz) -> cx != 1),
                "missing block's own chunk cx=1 must return false");
    }
}