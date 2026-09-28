package dimblend.radio.client;

import dimblend.radio.RadioSignals;

/** The same signal and strictly-positive audible-gain rules for every radio candidate. */
public final class RadioAudibilityRules {
    public static boolean validSignal(int station, int side) {
        return station >= RadioSignals.STATION_MIN && station <= RadioSignals.STATION_MAX
                && side > 0 && side <= 15;
    }

    public static boolean shouldSuppress(int station, int side, double playingGain,
            double distance, double range) {
        return validSignal(station, side) && Double.isFinite(playingGain) && playingGain > 0
                && distance >= 0 && distance < range;
    }

    private RadioAudibilityRules() {
    }
}
