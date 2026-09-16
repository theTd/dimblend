package dimblend.client;

import dimblend.weather.WeatherLockTarget;

/**
 * Client-side holder for the clear-sky weather lock. Derived from the band
 * lane already pushed by {@link dimblend.band.BandLaneSync} — rain is
 * dimension-wide, so the client only needs to know which lane the player
 * stands in, the same per-player-band mask as {@link ClientTimeLock}.
 * Twilight is included here (not on the server) so overworld rain cannot
 * darken the TF sky disc / stars.
 */
public final class ClientWeatherLock {
    private ClientWeatherLock() {
    }

    public static boolean active() {
        return WeatherLockTarget.clientClearSky(ClientBandLane.lane());
    }
}
