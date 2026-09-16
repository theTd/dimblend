package dimblend.time;

/**
 * Per-band time-lock target for the rotating dimension, straight from the
 * north-star spec (docs/generation-rules.md §3). Lane names come from
 * {@link dimblend.worldgen.BandLayout#laneName}.
 *
 * <p>Starlight keeps the 14000 lock for day-cycle reads; the starlight skybox
 * itself is restored in RotatingDimensionEffects. Voidscape uses the spec's
 * "时间锁定" fallback (the "恢复纬度效果" alternative is not implemented). End
 * keeps the 18000 lock for day-cycle reads; the End skybox itself is restored
 * in RotatingDimensionEffects.
 */
public record TimeLockTarget(Mode mode, long time) {
    public enum Mode {
        /** Normal flowing time (surface bands and unknown lanes). */
        NONE,
        /** Fixed time of day in a 24000 tick cycle. */
        FIXED,
        /** Twilight dusk jitter around TF's {@code fixed_time: 13000}. */
        TWILIGHT_JITTER
    }

    /**
     * Inclusive lower bound of the dusk wander. Twilight Forest 1.21.1 pins the
     * dimension at {@code fixed_time: 13000} (just after night start); vanilla
     * lightmap/sky daylight at 12600–12700 is still ~1.5× brighter than that
     * and reads as daytime on terrain. Keep a 100-tick window centered on 13000
     * so the old "twitch" remains without climbing back up the sunset curve.
     */
    public static final long TWILIGHT_MIN = 12950L;
    /** Exclusive upper bound; 12950–13050 inclusive. */
    public static final long TWILIGHT_MAX_EXCLUSIVE = 13051L;

    public static final TimeLockTarget NONE = new TimeLockTarget(Mode.NONE, 0L);

    private static final TimeLockTarget TWILIGHT = new TimeLockTarget(Mode.TWILIGHT_JITTER, 0L);

    public static TimeLockTarget fromLane(String laneName) {
        return switch (laneName) {
            case "underground", "nether", "end", "deeperdarker", "voidscape" -> fixed(18000L);
            case "starlight" -> fixed(14000L);
            case "aether" -> fixed(4000L);
            case "twilight" -> TWILIGHT;
            default -> NONE;
        };
    }

    private static TimeLockTarget fixed(long time) {
        return new TimeLockTarget(Mode.FIXED, time);
    }
}
