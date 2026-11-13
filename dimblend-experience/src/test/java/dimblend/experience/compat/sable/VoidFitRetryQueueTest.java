package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class VoidFitRetryQueueTest {

    private static ResourceKey<Level> testDim(String name) {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("dimblend_test", name));
    }

    @Test
    void budgetRetryDoesNotForceWorldVerifyUntilCountdownExpires() {
        VoidFitTracker tracker = VoidFitTracker.of(testDim("retry_verify"));
        UUID id = UUID.randomUUID();
        try {
            VoidFitTracker.VehicleFit fit = new VoidFitTracker.VehicleFit();
            fit.verifyCountdown = 3;
            tracker.vehicles().put(id, fit);
            LongOpenHashSet target = new LongOpenHashSet();
            target.add(BlockPos.asLong(0, 64, 0));
            tracker.setTargetCells(id, target);

            Set<UUID> retry = new LinkedHashSet<>(List.of(id));

            List<VoidFitRetryQueue.RetryCandidate> scan1 = VoidFitRetryQueue.pollCandidates(tracker, retry, 2);
            assertEquals(1, scan1.size());
            assertFalse(scan1.get(0).verifyDue(), "countdown 3 -> 2 must NOT trigger world verify");
            assertEquals(2, fit.verifyCountdown);
            VoidFitRetryQueue.finishCandidate(tracker, retry, id, false, 20);

            List<VoidFitRetryQueue.RetryCandidate> scan2 = VoidFitRetryQueue.pollCandidates(tracker, retry, 2);
            assertFalse(scan2.get(0).verifyDue(), "countdown 2 -> 1 must NOT trigger world verify");
            assertEquals(1, fit.verifyCountdown);
            VoidFitRetryQueue.finishCandidate(tracker, retry, id, false, 20);

            List<VoidFitRetryQueue.RetryCandidate> scan3 = VoidFitRetryQueue.pollCandidates(tracker, retry, 2);
            assertTrue(scan3.get(0).verifyDue(), "countdown 1 -> 0 MUST trigger world verify");
            assertEquals(0, fit.verifyCountdown);
            VoidFitRetryQueue.finishCandidate(tracker, retry, id, true, 20);
            assertTrue(retry.isEmpty(), "completed non-empty target must leave retry queue");
        } finally {
            tracker.removeVehicle(id);
        }
    }

    @Test
    void incompleteRetriesRotateFifoSoTailCarriagesNeverStarve() {
        VoidFitTracker tracker = VoidFitTracker.of(testDim("retry_fifo"));
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();
        UUID c4 = UUID.randomUUID();
        try {
            for (UUID id : List.of(c1, c2, c3, c4)) {
                VoidFitTracker.VehicleFit fit = new VoidFitTracker.VehicleFit();
                fit.verifyCountdown = 20;
                tracker.vehicles().put(id, fit);
            }
            Set<UUID> retry = new LinkedHashSet<>(List.of(c1, c2, c3, c4));

            List<VoidFitRetryQueue.RetryCandidate> scan1 = VoidFitRetryQueue.pollCandidates(tracker, retry, 2);
            assertEquals(List.of(c1, c2), scan1.stream().map(VoidFitRetryQueue.RetryCandidate::id).toList());
            // Both c1 and c2 still incomplete: must go to the tail behind c3, c4
            VoidFitRetryQueue.finishCandidate(tracker, retry, c1, false, 20);
            VoidFitRetryQueue.finishCandidate(tracker, retry, c2, false, 20);

            List<VoidFitRetryQueue.RetryCandidate> scan2 = VoidFitRetryQueue.pollCandidates(tracker, retry, 2);
            assertEquals(List.of(c3, c4), scan2.stream().map(VoidFitRetryQueue.RetryCandidate::id).toList(),
                    "second scan must serve c3 and c4 instead of starving them behind incomplete c1 and c2");
        } finally {
            for (UUID id : List.of(c1, c2, c3, c4)) {
                tracker.removeVehicle(id);
            }
        }
    }
}
