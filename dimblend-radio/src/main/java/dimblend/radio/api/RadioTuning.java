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

    public static double receptionNoiseVolume() {
        return isLoaded() ? RadioServerConfig.RECEPTION_NOISE.get() : RadioLiveSettings.receptionNoiseVolume();
    }

    public static double acousticIntensity() {
        return isLoaded() ? RadioServerConfig.ACOUSTIC_INTENSITY.get() : RadioLiveSettings.acousticIntensity();
    }

    public static void setReceptionNoiseVolume(double value) {
        applyReceptionNoiseVolume(value);
        RadioServerConfig.SPEC.save();
    }

    public static void setAcousticIntensity(double value) {
        applyAcousticIntensity(value);
        RadioServerConfig.SPEC.save();
    }

    public static void applyReceptionNoiseVolume(double value) {
        if (!validNoiseVolume(value)) {
            throw new IllegalArgumentException("Reception noise volume must be 0.00-1.00 in steps of 0.01");
        }
        RadioServerConfig.apply(value, acousticIntensity());
    }

    public static void applyAcousticIntensity(double value) {
        if (!validIntensity(value)) {
            throw new IllegalArgumentException("Acoustic intensity must be 0.00-2.00 in steps of 0.01");
        }
        RadioServerConfig.apply(receptionNoiseVolume(), value);
    }

    public static boolean validIntensity(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= RadioLiveSettings.MAX_ACOUSTIC_INTENSITY
                && Math.abs(value * 100.0D - Math.rint(value * 100.0D)) < 1.0E-7D;
    }

    public static boolean validNoiseVolume(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= RadioLiveSettings.MAX_RECEPTION_NOISE_VOLUME
                && Math.abs(value * 100.0D - Math.rint(value * 100.0D)) < 1.0E-7D;
    }

    private RadioTuning() {
    }
}
