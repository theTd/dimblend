package dimblend.spyglass.server;

import net.minecraft.util.Mth;

/**
 * The server's fallback reveal distance when the client has not sent one yet.
 *
 * <p>Mirrors what vanilla's client would compute for the same view distance: the effective render
 * distance in blocks is {@code clamp(requestedViewDistance, 2, serverViewDistance) * 16}, exactly like
 * {@code ChunkMap.getPlayerViewDistance} ({@code ChunkMap.java:807-809}), and the non-foggy terrain fog
 * start is {@code R - clamp(R / 10, 4, 64)} ({@code FogRenderer.java:267-275}). The client normally
 * sends its own value within a tick or two; this fallback only keeps the very first snapshot sane.
 *
 * <p>Pure math, unit tested.
 */
final class SpyglassRevealDefaults {
    /** Vanilla's lower view-distance clamp ({@code ChunkMap.java:808}). */
    static final int MIN_VIEW_DISTANCE_CHUNKS = 2;
    static final int BLOCKS_PER_CHUNK = 16;
    /** Terrain fog span terms ({@code FogRenderer.java:267-268}). */
    static final float FOG_SPAN_DIVISOR = 10.0F;
    static final float MIN_FOG_SPAN_BLOCKS = 4.0F;
    static final float MAX_FOG_SPAN_BLOCKS = 64.0F;

    private SpyglassRevealDefaults() {
    }

    /**
     * @param requestedViewDistanceChunks the player's requested view distance in chunks
     * @param serverViewDistanceChunks    the server's view distance in chunks
     * @return vanilla's non-foggy terrain fog start for that view distance, in blocks
     */
    static double defaultRevealBlocks(int requestedViewDistanceChunks, int serverViewDistanceChunks) {
        int maximum = Math.max(MIN_VIEW_DISTANCE_CHUNKS, serverViewDistanceChunks);
        int viewDistance = Mth.clamp(requestedViewDistanceChunks, MIN_VIEW_DISTANCE_CHUNKS, maximum);
        float renderDistanceBlocks = viewDistance * (float) BLOCKS_PER_CHUNK;
        float fogSpan = Mth.clamp(
                renderDistanceBlocks / FOG_SPAN_DIVISOR, MIN_FOG_SPAN_BLOCKS, MAX_FOG_SPAN_BLOCKS);
        return renderDistanceBlocks - fogSpan;
    }
}
