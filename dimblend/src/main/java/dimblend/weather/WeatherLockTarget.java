package dimblend.weather;

/**
 * Per-band weather lock for the rotating dimension. Rain and thunder are
 * dimension-wide (one shared weather state for the whole dimension), so the
 * lock does not stop the shared weather cycle — it only forces a band's
 * columns (and the player standing in them) to read as clear sky.
 *
 * <p>Every band except surface is locked clear. The surface band is the only
 * one that keeps the dimension's shared rain and thunder. Lane names come from
 * {@link dimblend.worldgen.BandLayout#laneName}; unknown and mod-delegate lanes
 * are not surface either, so they are locked as well.
 *
 * <p>Both halves use this same predicate: the server column reads through
 * {@link ServerBandWeather}, the client visual mask through
 * {@link dimblend.client.ClientWeatherLock}.
 */
public final class WeatherLockTarget {
    private WeatherLockTarget() {
    }

    /** True when this lane must be treated as clear sky; only surface keeps weather. */
    public static boolean clearSky(String laneName) {
        return !"surface".equals(laneName);
    }
}
