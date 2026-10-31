package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.StationDepotSites;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The depot switch and the two chassis sites have to agree with worldgen to the block. */
class StationDepotSitesTest {
    private static final int B = 2048;

    @Test
    void theSwitchStandsOneBlockAboveTheJunctionOnTheMainLine() {
        BlockPos pos = StationDepotSwitch.at(B);
        assertEquals(B + dimblend.api.StationDepotCurves.POSITIVE_MERGE_DX, pos.getX(),
                "the switch stands where the positive siding's curve lands");
        assertEquals(StationDepotSites.controlY(), pos.getY(), "one block above the rail");
        assertEquals(StationDepotSites.POWER_CHASSIS_Z, pos.getZ(), "on the main line");
        assertEquals(pos.below().getX(), StationDepotSites.junctionX(B));
    }

    @Test
    void theSwitchTargetIsARelativeOffsetToTheRailBelowIt() {
        // TrackTargetingBehaviour.getGlobalPosition() adds TargetTrack to the switch
        // position, so an absolute position here would double the world coordinate.
        BlockPos pos = StationDepotSwitch.at(B);
        BlockPos relative = pos.below().subtract(pos);
        assertEquals(new BlockPos(0, -1, 0), relative);
        assertNotEquals(pos.below(), relative, "must never be the absolute rail position");
    }

    @Test
    void theCarriageSiteIsThePositiveSidingAndTheNegativeOneIsEmpty() {
        BlockPos carriage = StationDepotAssemblyData.carriageCenter(B);
        assertEquals(B, carriage.getX(), "centred on the boundary column");
        assertEquals(StationDepotSites.CHASSIS_Y, carriage.getY(), "one block above the rail");
        assertEquals(StationDepotSites.SIDING_DZ, carriage.getZ(), "positive Z side");
        assertTrue(carriage.getZ() > 0);
        assertTrue(StationDepotSites.SIDING_DZ > 0);
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