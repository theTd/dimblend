package dimblend.client;

import dimblend.weather.WeatherLockTarget;

/**
 * Client-side holder for the clear-sky weather lock. Derived from the band
 * lane already pushed by {@link dimblend.band.BandLaneSync} — rain is
 * dimension-wide, so the client only needs to know which lane the player
 * stands in, the same per-player-band mask as {@link ClientTimeLock}.
 */
public final class ClientWeatherLock {
    private ClientWeatherLock() {
    }

    public static boolean active() {
        return WeatherLockTarget.clearSky(ClientBandLane.lane());
    }
}
