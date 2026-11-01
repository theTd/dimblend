package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * The depot carries no track switch at all.
     *
     * <p>A user reported on 2026-11-01 that a switch block stood in the middle of the
     * junction and stopped the car. Railways' andesite switch is a solid block: its
     * collision shape fills the entire 16x16 footprint at 0..5px, and it registers
     * without {@code noCollission()} (CRShapes:29, CRBlocks:179). Anything standing in the
     * rail's own column therefore blocks the train, and the depot's two merge columns sit
     * one block apart, so one switch could never have covered both forks anyway. The
     * decision was to remove it and leave the main line clear.
     */
    @Test
    void theDepotPlacesNoSwitchInTheRunningLine() {
        assertFalse(StationDepotAssemblyData.fitsSwitch(),
                "the depot must not place a switch over the rails");
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
     * The siding car must be the SAME car the spawn point hands out. A user hit this on
     * 2026-11-01: the spawn chassis drove as soon as a motor was fitted, the siding one
     * never moved. The cause was the blueprint, not the block: CARRIAGE tunes every bogey
     * to stress 0, and stress multiplies the drive force, so no motor could ever move it.
     */
    @Test
    void bothSitesUseThePowerBlueprintThatActuallyDrives() {
        assertEquals(ChassisBlueprint.POWER, StationDepotAssemblyData.sidingBlueprint(),
                "the siding car must be the drivable one");
        assertEquals(ChassisBlueprint.POWER, StationDepotAssemblyData.powerBlueprint(),
                "the spawn car must be the drivable one");
        assertTrue(ChassisBlueprint.bogeyTuning(ChassisBlueprint.POWER, 17).orElseThrow().stress() > 0,
                "at least one bogey of the shared blueprint must produce drive force");
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