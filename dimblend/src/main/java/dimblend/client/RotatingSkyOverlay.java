package dimblend.client;

/**
 * Which extra sky overlay the rotating dimension should use this frame.
 * Twilight (camera noise biome) wins over Starlight (camera noise biome) wins over
 * Voidscape's portal-shader sky (player lane) wins over End skybox (player lane:
 * end / deeperdarker) wins over Nether effects (player lane: underground / nether);
 * none means vanilla overworld sky.
 */
public enum RotatingSkyOverlay {
    NONE,
    TWILIGHT,
    STARLIGHT,
    VOIDSCAPE,
    END,
    NETHER;

    public static RotatingSkyOverlay of(
            boolean twilight, boolean starlight, boolean voidscape, boolean end, boolean nether) {
        if (twilight) {
            return TWILIGHT;
        }
        if (starlight) {
            return STARLIGHT;
        }
        if (voidscape) {
            return VOIDSCAPE;
        }
        if (end) {
            return END;
        }
        if (nether) {
            return NETHER;
        }
        return NONE;
    }

    /**
     * Unloaded client quarts fall back to plains, which would otherwise flip the
     * sky off for a few frames while neighbor chunks stream in. Keep the last
     * loaded twilight/starlight sample until the camera quart is actually
     * present. Lane-keyed skies (Voidscape / End / Nether) always take the live
     * {@code sampled} value so they do not freeze after leaving the lane.
     */
    public static RotatingSkyOverlay holdIfUnloaded(
            boolean chunkLoaded, RotatingSkyOverlay sampled, RotatingSkyOverlay previous) {
        if (chunkLoaded || sampled.laneKeyed() || previous.laneKeyed()) {
            return sampled;
        }
        return previous;
    }

    boolean laneKeyed() {
        return this == END || this == VOIDSCAPE || this == NETHER;
    }
}
