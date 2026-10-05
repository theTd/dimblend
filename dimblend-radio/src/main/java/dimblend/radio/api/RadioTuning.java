package dimblend.radio.api;

import dimblend.radio.RadioLiveSettings;
import dimblend.radio.RadioServerConfig;

/**
 * Entry point for optional configuration panels in other mods. The setters run on the logical
 * server and save the world's config; the {@code apply} methods change memory only and are for
 * remote clients receiving the server's values.
 */
public final class RadioTuning {
    public static boolean isLoaded() {
        return RadioServerConfig.SPEC.isLoaded();
    }

    public static boolean receptionNoise() {
        return isLoaded() ? RadioServerConfig.RECEPTION_NOISE.get() : RadioLiveSettings.receptionNoise();
    }

    public static double acousticIntensity() {
        return isLoaded() ? RadioServerConfig.ACOUSTIC_INTENSITY.get() : RadioLiveSettings.acousticIntensity();
    }

    public static void setReceptionNoise(boolean enabled) {
        applyReceptionNoise(enabled);
        RadioServerConfig.SPEC.save();
    }

    public static void setAcousticIntensity(double value) {
        applyAcousticIntensity(value);
        RadioServerConfig.SPEC.save();
    }

    public static void applyReceptionNoise(boolean enabled) {
        RadioServerConfig.apply(enabled, acousticIntensity());
    }

    public static void applyAcousticIntensity(double value) {
        if (!validIntensity(value)) {
            throw new IllegalArgumentException("Acoustic intensity must be 0.00-2.00 in steps of 0.01");
        }
        RadioServerConfig.apply(receptionNoise(), value);
    }

    public static boolean validIntensity(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= RadioLiveSettings.MAX_ACOUSTIC_INTENSITY
                && Math.abs(value * 100.0D - Math.rint(value * 100.0D)) < 1.0E-7D;
    }

    private RadioTuning() {
    }
}
