package dimblend.client;

/**
 * Which extra sky overlay the rotating dimension should use this frame.
 * Twilight (camera noise biome) wins over Starlight (camera noise biome) wins over End
 * skybox (player lane: end / underground / nether / deeperdarker / voidscape);
 * none means vanilla overworld sky.
 */
public enum RotatingSkyOverlay {
    NONE,
    TWILIGHT,
    STARLIGHT,
    END;

    public static RotatingSkyOverlay of(boolean twilight, boolean starlight, boolean end) {
        if (twilight) {
            return TWILIGHT;
        }
        if (starlight) {
            return STARLIGHT;
        }
        if (end) {
            return END;
        }
        return NONE;
    }

    /**
     * Unloaded client quarts fall back to plains, which would otherwise flip the
     * sky off for a few frames while neighbor chunks stream in. Keep the last
     * loaded twilight/starlight sample until the camera quart is actually
     * present. End sky is keyed off the lane packet, not a biome sample, so the
     * live {@code sampled} value always wins when it is {@link #END} or when the
     * previous overlay was {@link #END} (do not freeze End sky after leaving).
     */
    public static RotatingSkyOverlay holdIfUnloaded(
            boolean chunkLoaded, RotatingSkyOverlay sampled, RotatingSkyOverlay previous) {
        if (chunkLoaded || sampled == END || previous == END) {
            return sampled;
        }
        return previous;
    }
}
