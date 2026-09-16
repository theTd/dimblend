package dimblend.client;

/**
 * Which extra sky overlay the rotating dimension should use this frame.
 * Twilight (camera biome) wins over Starlight (camera biome) wins over End
 * (player lane); none means vanilla overworld sky.
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
}
