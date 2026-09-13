package dimblend.time;

/**
 * Per-band time-lock target for the rotating dimension, straight from the
 * north-star spec (docs/generation-rules.md §3). Lane names come from
 * {@link dimblend.worldgen.BandLayout#laneName}.
 *
 * <p>End / starlight / voidscape use the spec's "时间锁定" fallback
 * (the "恢复纬度效果" alternative is not implemented).
 */
public record TimeLockTarget(Mode mode, long time) {
    public enum Mode {
        /** Normal flowing time (surface bands and unknown lanes). */
        NONE,
        /** Fixed time of day in a 24000 tick cycle. */
        FIXED,
        /** Twilight dusk jitter: a fresh random tick in the twilight range per client tick. */
        TWILIGHT_JITTER
    }

    public static final long TWILIGHT_MIN = 12600L;
    /** Exclusive upper bound; the spec's 12600–12700 jitter interval. */
    public static final long TWILIGHT_MAX_EXCLUSIVE = 12701L;

    public static final TimeLockTarget NONE = new TimeLockTarget(Mode.NONE, 0L);

    private static final TimeLockTarget TWILIGHT = new TimeLockTarget(Mode.TWILIGHT_JITTER, 0L);

    public static TimeLockTarget fromLane(String laneName) {
        return switch (laneName) {
            case "underground" -> fixed(22000L);
            case "nether", "end", "deeperdarker", "voidscape" -> fixed(18000L);
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
