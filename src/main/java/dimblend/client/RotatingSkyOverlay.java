package dimblend.client;

/**
 * Which extra sky overlay the rotating dimension should use this frame.
 * Twilight (camera biome) wins over End (player lane); neither means
 * vanilla overworld sky.
 */
public enum RotatingSkyOverlay {
    NONE,
    TWILIGHT,
    END;

    public static RotatingSkyOverlay of(boolean twilight, boolean end) {
        if (twilight) {
            return TWILIGHT;
        }
        if (end) {
            return END;
        }
        return NONE;
    }
}
