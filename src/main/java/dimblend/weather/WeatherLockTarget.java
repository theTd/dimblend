package dimblend.weather;

/**
 * Per-band weather lock for the rotating dimension. Rain and thunder are
 * dimension-wide, so locked lanes do not stop the shared weather cycle —
 * they only force those columns (and the player standing in them) to read
 * as clear sky. Lane names come from {@link dimblend.worldgen.BandLayout#laneName}.
 *
 * <p>Locked: underground / aether / deeperdarker / voidscape. Surface, nether,
 * end, twilight and starlight keep the dimension's vanilla weather.
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
}
