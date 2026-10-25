package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.StructureKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ShapeBuildTrackerTest {
    private static final StructureKey A = key("a");
    private static final StructureKey B = key("b");
    private static final StructureKey C = key("c");

    @Test
    void boundsTheNumberOfBuildsInFlight() {
        ShapeBuildTracker tracker = new ShapeBuildTracker(2);
        tracker.started(A, 1);
        tracker.started(B, 1);

        assertFalse(tracker.hasCapacity());
        assertFalse(tracker.canStart(C, 1, 0));

        tracker.finished(A);
        assertTrue(tracker.canStart(C, 1, 0));
        assertEquals(1, tracker.inFlightCount());
    }

    @Test
    void neverBuildsTheSameStructureTwiceAtOnce() {
        ShapeBuildTracker tracker = new ShapeBuildTracker(2);
        tracker.started(A, 1);

        assertFalse(tracker.canStart(A, 1, 0));
        assertFalse(tracker.canStart(A, 2, 0), "a newer revision waits for the running build");
        assertThrows(IllegalStateException.class, () -> tracker.started(A, 2));
    }

    @Test
    void aPermanentRejectionBlocksOnlyThatRevision() {
        ShapeBuildTracker tracker = new ShapeBuildTracker(2);
        tracker.reject(A, 3, ShapeBuildTracker.NEVER);

        assertFalse(tracker.canStart(A, 3, 1_000_000));
        assertTrue(tracker.canStart(A, 4, 0), "a newer revision is a different shape");
        assertTrue(tracker.canStart(B, 3, 0));
    }

    @Test
    void aTimedRejectionExpires() {
        ShapeBuildTracker tracker = new ShapeBuildTracker(2);
        tracker.reject(A, 1, 600);

        assertFalse(tracker.canStart(A, 1, 599));
        assertTrue(tracker.canStart(A, 1, 600));
    }

    @Test
    void clearForgetsBuildsAndRejections() {
        ShapeBuildTracker tracker = new ShapeBuildTracker(1);
        tracker.started(A, 1);
        tracker.reject(B, 1, ShapeBuildTracker.NEVER);

        tracker.clear();

        assertEquals(0, tracker.inFlightCount());
        assertTrue(tracker.canStart(A, 1, 0));
        assertTrue(tracker.canStart(B, 1, 0));
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new ShapeBuildTracker(0));
    }

    private static StructureKey key(String path) {
        return new StructureKey(ResourceLocation.fromNamespaceAndPath("test", path), 0, 0);
    }
}
