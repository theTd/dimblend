package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class StructureNoiseSetupTest {
    @Test
    void scopeIsActiveOnlyDuringStructurePlacement() {
        assertFalse(StructureNoiseSetup.active());
        StructureNoiseSetup.run(() -> {
            assertTrue(StructureNoiseSetup.active());
        });
        assertFalse(StructureNoiseSetup.active());
    }

    @Test
    void nestedScopeSurvivesInnerFailureAndRestoresOriginalCacheAfterOuterFailure() {
        assertFalse(StructureNoiseSetup.active());
        assertThrows(IllegalStateException.class, () -> StructureNoiseSetup.run(() -> {
            assertThrows(IllegalArgumentException.class,
                    () -> StructureNoiseSetup.run(() -> { throw new IllegalArgumentException(); }));
            assertTrue(StructureNoiseSetup.active());
            throw new IllegalStateException();
        }));
        assertFalse(StructureNoiseSetup.active());
    }

    @Test
    void scopeDoesNotChangeOtherWorkersCaches() {
        StructureNoiseSetup.run(() -> {
            assertTrue(StructureNoiseSetup.active());
            assertFalse(CompletableFuture.supplyAsync(StructureNoiseSetup::active).join());
        });
        assertFalse(StructureNoiseSetup.active());
    }

    @Test
    void mappingScopeRestoresAfterNestedFailureAndDoesNotReachOtherWorkers() {
        assertFalse(StructureNoiseSetup.mappingActive(null));
        StructureNoiseSetup.run(() -> StructureNoiseSetup.mapNodes(null, () -> {
            assertTrue(StructureNoiseSetup.mappingActive(null));
            assertFalse(CompletableFuture.supplyAsync(() -> StructureNoiseSetup.mappingActive(null)).join());
            assertThrows(IllegalStateException.class, () -> StructureNoiseSetup.mapNodes(null, () -> {
                throw new IllegalStateException();
            }));
            assertTrue(StructureNoiseSetup.mappingActive(null));
            return null;
        }));
        assertFalse(StructureNoiseSetup.mappingActive(null));
        assertThrows(IllegalStateException.class, () -> StructureNoiseSetup.run(
                () -> StructureNoiseSetup.mapNodes(null, () -> { throw new IllegalStateException(); })));
        assertFalse(StructureNoiseSetup.mappingActive(null));
    }

}
