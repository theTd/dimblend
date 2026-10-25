package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The server's fallback reveal distance must be the same fog start the client would compute for the
 * effective view distance ({@code FogRenderer.java:267-275}, {@code ChunkMap.java:807-809}).
 */
class SpyglassRevealDefaultsTest {
    private static final double DELTA = 1.0E-4;

    @Test
    void matchesTheNonFoggyFogStartForThePlayersEffectiveViewDistance() {
        // 12 chunks = 192 blocks; span = clamp(19.2, 4, 64) = 19.2.
        assertEquals(172.8, SpyglassRevealDefaults.defaultRevealBlocks(12, 12), DELTA);
        // 32 chunks = 512 blocks are clamped by the server's view distance of 10 chunks.
        assertEquals(144.0, SpyglassRevealDefaults.defaultRevealBlocks(32, 10), DELTA);
        // 64 chunks = 1024 blocks; the span is capped at 64 blocks.
        assertEquals(960.0, SpyglassRevealDefaults.defaultRevealBlocks(64, 64), DELTA);
    }

    @Test
    void clampsTheViewDistanceLikeTheChunkMap() {
        // Requested below the vanilla minimum of 2 chunks.
        assertEquals(28.0, SpyglassRevealDefaults.defaultRevealBlocks(1, 12), DELTA);
        // A server view distance below the minimum cannot push the result under 2 chunks.
        assertEquals(28.0, SpyglassRevealDefaults.defaultRevealBlocks(12, 1), DELTA);
        // The minimum fog span keeps very small render distances sensible.
        assertEquals(28.0, SpyglassRevealDefaults.defaultRevealBlocks(2, 12), DELTA);
    }
}
