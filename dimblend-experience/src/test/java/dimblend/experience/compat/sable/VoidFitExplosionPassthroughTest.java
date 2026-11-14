package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class VoidFitExplosionPassthroughTest {

    private static ResourceKey<Level> testDim(String name) {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("dimblend_test", name));
    }

    @Test
    void matchesTargetAndTrailingMaterializedCellsOnly() {
        assertFalse(VoidFitExplosionPassthrough.isTrackedFittedCell(null, BlockPos.asLong(0, 64, 0)));

        VoidFitTracker tracker = VoidFitTracker.of(testDim("explosion_passthrough"));
        UUID car = UUID.randomUUID();
        long targetOnly = BlockPos.asLong(12, 66, -3);
        long trailingWake = BlockPos.asLong(-20, 66, -3);
        long unrelatedWorld = BlockPos.asLong(13, 66, -3);
        long plotCoord = BlockPos.asLong(20_480_012, 66, -3);

        try {
            tracker.vehicles().put(car, new VoidFitTracker.VehicleFit());

            LongOpenHashSet initial = new LongOpenHashSet();
            initial.add(trailingWake);
            tracker.setTargetCells(car, initial);
            tracker.addMaterialized(car, trailingWake);

            LongOpenHashSet moved = new LongOpenHashSet();
            moved.add(targetOnly);
            tracker.setTargetCells(car, moved);

            assertTrue(VoidFitExplosionPassthrough.isTrackedFittedCell(tracker, targetOnly),
                    "active target cell must be treated as fitted void for explosion rays");
            assertTrue(VoidFitExplosionPassthrough.isTrackedFittedCell(tracker, trailingWake),
                    "unremoved trailing materialized cell must also be treated as fitted void");
            assertFalse(VoidFitExplosionPassthrough.isTrackedFittedCell(tracker, unrelatedWorld),
                    "untracked world coordinate must not be masked");
            assertFalse(VoidFitExplosionPassthrough.isTrackedFittedCell(tracker, plotCoord),
                    "SubLevel plot coordinate must never be masked");

            tracker.removeMaterialized(car, trailingWake);
            assertFalse(VoidFitExplosionPassthrough.isTrackedFittedCell(tracker, trailingWake),
                    "cleaned wake cell must no longer match");
        } finally {
            tracker.removeVehicle(car);
        }
    }
}
