package dimblend.weather;

/**
 * Per-band weather lock for the rotating dimension. Rain and thunder are
 * dimension-wide, so locked lanes do not stop the shared weather cycle —
 * they only force those columns (and the player standing in them) to read
 * as clear sky. Lane names come from {@link dimblend.worldgen.BandLayout#laneName}.
 *
 * <p>Server locked: underground / aether / deeperdarker / voidscape. Surface,
 * nether, end, twilight and starlight keep the dimension's vanilla weather
 * mechanics. Twilight is additionally client-clear so leaked overworld rain
 * does not tint the TF sky.
 */
public final class WeatherLockTarget {
    private WeatherLockTarget() {
    }

    /** True when this lane must be treated as clear sky. */
    public static boolean clearSky(String laneName) {
        return switch (laneName) {
            case "underground", "aether", "deeperdarker", "voidscape" -> true;
            default -> false;
        };
    }

    /**
     * Client visual mask. Includes {@link #clearSky} plus twilight: rain is
     * dimension-wide, so overworld precipitation would otherwise darken the
     * TF sky disc, fade the custom stars, and tint the mint horizon fog.
     * Server columns still follow {@link #clearSky} so twilight keeps vanilla
     * weather mechanics.
     */
    public static boolean clientClearSky(String laneName) {
        return clearSky(laneName) || "twilight".equals(laneName);
    }
}
