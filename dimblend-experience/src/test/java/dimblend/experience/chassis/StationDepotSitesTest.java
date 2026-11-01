package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(ChassisBlueprint.SAFE_Z + 1, Math.abs(machine.getZ()),
                "and no further out than the first free column, so it still reads as trackside");
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
     * Both cars the world hands out must be the same car, and that car must drive itself.
     * A user hit the reverse on 2026-11-01: the siding car never moved, because CARRIAGE
     * tunes every bogey to stress 0 and stress multiplies the drive force. On 2026-11-02 the
     * user asked for the carriage shell back at both sites, so the shell is CARRIAGE and the
     * drive comes from the assembly telling the bogeys to pull — not from the blueprint.
     */
    @Test
    void bothSitesUseTheCarriageShellWithDrivenEndBogeys() {
        assertEquals(ChassisBlueprint.CARRIAGE, StationDepotAssemblyData.sidingBlueprint(),
                "the siding car is the carriage shell");
        assertEquals(ChassisBlueprint.CARRIAGE, StationDepotAssemblyData.powerBlueprint(),
                "the spawn car is the same shell");
        float stress = ChassisBlueprint
                .bogeyTuning(ChassisBlueprint.CARRIAGE, 17, StationDepotAssemblyData.drivesEndBogeys())
                .orElseThrow().stress();
        assertTrue(stress > 0.0F, "the car this world builds must produce drive force");
        assertEquals(0.0F, ChassisBlueprint.bogeyTuning(ChassisBlueprint.CARRIAGE, 17).orElseThrow().stress(),
                0.0F, "a printed carriage stays dead-hauled");
    }

    @Test
    void thePowerSiteIsAtX32OnTheMainLine() {
        BlockPos power = StationDepotAssemblyData.powerCenter();
        assertEquals(32, power.getX());
        assertEquals(StationDepotSites.CHASSIS_Y, power.getY());
        assertEquals(0, power.getZ());
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
}