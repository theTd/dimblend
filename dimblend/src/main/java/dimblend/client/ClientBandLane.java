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
     * (end / deeperdarker). Underground and nether use {@link #netherSky()}
     * instead; Voidscape uses Voidscape's own portal-shader sky. Same
     * partition-wall signal as {@link #end()}; not a camera-biome lookup.
     */
    public static boolean endSky() {
        return switch (lane) {
            case "end", "deeperdarker" -> true;
            default -> false;
        };
    }

    /**
     * True while the player stands in a lane that uses vanilla's Nether
     * effects (underground / nether): {@code SkyType.NONE}, thick fog,
     * constant ambient light. Same partition-wall signal as {@link #endSky()}.
     */
    public static boolean netherSky() {
        return switch (lane) {
            case "underground", "nether" -> true;
            default -> false;
        };
    }
}
