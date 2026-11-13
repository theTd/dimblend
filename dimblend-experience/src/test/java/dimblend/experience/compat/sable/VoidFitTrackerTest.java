package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class VoidFitTrackerTest {

    private static ResourceKey<Level> testDim(String name) {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("dimblend_test", name));
    }

    private static long cell(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    @Test
    void unremovedWakeCellsStayInMaterializedChunkIndexWithoutPollutingTarget() {
        VoidFitTracker tracker = VoidFitTracker.of(testDim("wake_isolation"));
        UUID vehicle = UUID.randomUUID();
        long wakeCell = cell(4, 70, 4);     // chunk (0, 0)
        long freshCell = cell(164, 70, 4);  // chunk (10, 0)
        try {
            VoidFitTracker.VehicleFit fit = new VoidFitTracker.VehicleFit();
            tracker.vehicles().put(vehicle, fit);

            LongOpenHashSet initial = new LongOpenHashSet();
            initial.add(wakeCell);
            tracker.setTargetCells(vehicle, initial);
            tracker.addMaterialized(vehicle, wakeCell);

            // Train moves far ahead; wakeCell has not yet been removed due to budget cap
            LongOpenHashSet moved = new LongOpenHashSet();
            moved.add(freshCell);
            tracker.setTargetCells(vehicle, moved);

            assertFalse(fit.targetCells.contains(wakeCell),
                    "wake cell must not remain in targetCells after vehicle moves away");
            assertNull(tracker.targetCellsOfChunk(new ChunkPos(0, 0)),
                    "old chunk must have zero target cells");

            LongSet wakeInOldChunk = tracker.materializedCellsOfChunk(new ChunkPos(0, 0));
            assertNotNull(wakeInOldChunk,
                    "unremoved wake cell must remain indexed in materializedCellsOfChunk so chunk save still strips it");
            assertTrue(wakeInOldChunk.contains(wakeCell));

            // Even after targetCells is cleared completely (gone vehicle draining removals), hasAny() stays true
            tracker.setTargetCells(vehicle, new LongOpenHashSet());
            assertTrue(VoidFitTracker.hasAny(),
                    "hasAny() must remain true while unremoved materialized wake cells exist");

            tracker.removeMaterialized(vehicle, wakeCell);
            assertNull(tracker.materializedCellsOfChunk(new ChunkPos(0, 0)),
                    "chunk index must be cleaned once the wake cell is removed");
        } finally {
            tracker.removeVehicle(vehicle);
        }
    }

    @Test
    void sharedTargetAndMaterializedCellsRespectMultiVehicleOwnership() {
        VoidFitTracker tracker = VoidFitTracker.of(testDim("shared_ownership"));
        UUID carA = UUID.randomUUID();
        UUID carB = UUID.randomUUID();
        long shared = cell(20, 65, 20); // chunk (1, 1)
        try {
            tracker.vehicles().put(carA, new VoidFitTracker.VehicleFit());
            tracker.vehicles().put(carB, new VoidFitTracker.VehicleFit());

            LongOpenHashSet targetBoth = new LongOpenHashSet();
            targetBoth.add(shared);
            tracker.setTargetCells(carA, targetBoth);
            tracker.setTargetCells(carB, targetBoth);
            tracker.addMaterialized(carA, shared);
            tracker.addMaterialized(carB, shared);

            // carA moves away and drops shared from both target and materialized
            tracker.setTargetCells(carA, new LongOpenHashSet());
            tracker.removeMaterialized(carA, shared);

            LongSet remainingTarget = tracker.targetCellsOfChunk(new ChunkPos(1, 1));
            LongSet remainingMaterialized = tracker.materializedCellsOfChunk(new ChunkPos(1, 1));
            assertNotNull(remainingTarget);
            assertTrue(remainingTarget.contains(shared), "carB still targets shared cell");
            assertNotNull(remainingMaterialized);
            assertTrue(remainingMaterialized.contains(shared), "carB still owns materialized shared cell");

            // Now carB also drops shared
            tracker.setTargetCells(carB, new LongOpenHashSet());
            tracker.removeMaterialized(carB, shared);
            assertNull(tracker.targetCellsOfChunk(new ChunkPos(1, 1)));
            assertNull(tracker.materializedCellsOfChunk(new ChunkPos(1, 1)));
        } finally {
            tracker.removeVehicle(carA);
            tracker.removeVehicle(carB);
        }
    }

    @Test
    void removeVehicleDirectlyCleansMaterializedChunkIndex() {
        VoidFitTracker tracker = VoidFitTracker.of(testDim("direct_remove"));
        UUID id = UUID.randomUUID();
        long c = cell(-8, 64, -8); // chunk (-1, -1)
        tracker.vehicles().put(id, new VoidFitTracker.VehicleFit());
        LongOpenHashSet t = new LongOpenHashSet();
        t.add(c);
        tracker.setTargetCells(id, t);
        tracker.addMaterialized(id, c);
        assertNotNull(tracker.materializedCellsOfChunk(new ChunkPos(-1, -1)));

        tracker.removeVehicle(id);
        assertEquals(0, tracker.vehicles().size());
        assertNull(tracker.targetCellsOfChunk(new ChunkPos(-1, -1)));
        assertNull(tracker.materializedCellsOfChunk(new ChunkPos(-1, -1)));
    }
}
