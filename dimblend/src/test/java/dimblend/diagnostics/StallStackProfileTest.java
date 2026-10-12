package dimblend.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StallStackProfileTest {
    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }

    /** Deepest frame first, like Thread#getStackTrace. */
    private static StackTraceElement[] spawnWaitingOnChunk() {
        return new StackTraceElement[] {
                frame("jdk.internal.misc.Unsafe", "park"),
                frame("net.minecraft.server.level.ServerChunkCache", "getChunk"),
                frame("net.minecraft.world.level.NaturalSpawner", "spawnCategoryForPosition"),
                frame("net.minecraft.server.level.ServerLevel", "tick"),
        };
    }

    private static StackTraceElement[] spawnChecking() {
        return new StackTraceElement[] {
                frame("net.minecraft.world.level.NaturalSpawner", "isValidSpawnPostitionForType"),
                frame("net.minecraft.world.level.NaturalSpawner", "spawnCategoryForPosition"),
                frame("net.minecraft.server.level.ServerLevel", "tick"),
        };
    }

    @Test
    void hotSpotIsTheDeepestFrameCommonToMostSamples() {
        StallStackProfile profile = new StallStackProfile();
        for (int i = 0; i < 8; i++) {
            profile.add(spawnWaitingOnChunk());
        }
        profile.add(spawnChecking());
        profile.add(spawnChecking());
        assertEquals(10, profile.samples());
        // park/getChunk are in 80 % of samples, so the deepest frame above 60 % is park.
        assertEquals("jdk.internal.misc.Unsafe.park", profile.hottestLeaf(0.6D));
        // Above 90 % only the shared spawn frame qualifies.
        assertEquals("net.minecraft.world.level.NaturalSpawner.spawnCategoryForPosition", profile.hottestLeaf(0.9D));
    }

    @Test
    void renderListsInclusiveSharesAndTheMostRepeatedStack() {
        StallStackProfile profile = new StallStackProfile();
        profile.add(spawnWaitingOnChunk());
        profile.add(spawnWaitingOnChunk());
        profile.add(spawnChecking());
        String text = profile.render("header", 10, 1);
        assertTrue(text.contains("3 stack samples"), text);
        assertTrue(text.contains("100.0%  net.minecraft.world.level.NaturalSpawner.spawnCategoryForPosition"), text);
        assertTrue(text.contains("-- 2 samples (66.7%)"), text);
        assertTrue(text.contains("at net.minecraft.server.level.ServerChunkCache.getChunk"), text);
    }
}
