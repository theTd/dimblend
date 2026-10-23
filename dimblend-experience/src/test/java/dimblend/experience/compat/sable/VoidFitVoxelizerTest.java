package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VoidFitVoxelizerTest {

    @Test
    void positiveZAboveBuildHeightIsNotClampedByWorldY() {
        LongOpenHashSet out = new LongOpenHashSet();
        VoidFitVoxelizer.addCoveredCells(out, 8.5, -59.5, 9_770_449.5,
                0.5, 0.5, 0.5, -64, 319);

        assertEquals(8, out.size());
        assertTrue(out.contains(BlockPos.asLong(8, -60, 9_770_449)));
        assertTrue(out.contains(BlockPos.asLong(9, -59, 9_770_450)));
    }

    @Test
    void worldYStillClampsVerticalCoverage() {
        LongOpenHashSet out = new LongOpenHashSet();
        VoidFitVoxelizer.addCoveredCells(out, 0.0, 319.5, 0.0,
                0.0, 2.0, 0.0, -64, 319);

        assertEquals(3, out.size());
        assertTrue(out.contains(BlockPos.asLong(0, 317, 0)));
        assertTrue(out.contains(BlockPos.asLong(0, 319, 0)));
    }
}
