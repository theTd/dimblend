package dimblend.client;

/**
 * Client-side holder for the band lane pushed by {@link dimblend.band.BandLaneSync}.
 * Callers must still gate on the current level being the rotating dimension —
 * the holder is not cleared on logout, so a stale value can outlive the level
 * it describes (same trade-off as {@link ClientBandProgress}).
 */
public final class ClientBandLane {
    private static volatile String lane = "unknown";

    private ClientBandLane() {
    }

    public static void apply(String lane) {
        ClientBandLane.lane = lane;
    }

    public static String lane() {
        return lane;
    }

    /** True while the player stands in an underground slice band. */
    public static boolean underground() {
        return "underground".equals(lane);
    }

    /** True while the player stands in an End-delegate band. */
    public static boolean end() {
        return "end".equals(lane);
    }

    /** True while the player stands in a Voidscape-delegate band. */
    public static boolean voidscape() {
        return "voidscape".equals(lane);
    }

    /**
     * True while the player stands in a lane that uses vanilla's End skybox
     * (end / underground / nether / deeperdarker). Voidscape uses Voidscape's
     * own portal-shader sky instead. Same partition-wall signal as {@link #end()};
     * not a camera-biome lookup.
     */
    public static boolean endSky() {
        return switch (lane) {
            case "underground", "nether", "end", "deeperdarker" -> true;
            default -> false;
        };
    }
}
