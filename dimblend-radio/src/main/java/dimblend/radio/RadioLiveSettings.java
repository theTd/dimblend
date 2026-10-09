package dimblend.radio;

/**
 * The radio settings as the audio threads read them: plain volatile copies of
 * {@link RadioServerConfig}, refreshed when that config loads, reloads, unloads or is edited.
 * Defaults apply while no server config is present (title screen, a server without the mod).
 */
public final class RadioLiveSettings {
    /** The former always-on noise level, now the volume slider's middle. */
    public static final float DEFAULT_RECEPTION_NOISE_VOLUME = 0.5f;
    public static final float MAX_RECEPTION_NOISE_VOLUME = 1.0f;
    public static final float DEFAULT_ACOUSTIC_INTENSITY = 1.0f;
    public static final float MAX_ACOUSTIC_INTENSITY = 2.0f;

    private static volatile float receptionNoiseVolume = DEFAULT_RECEPTION_NOISE_VOLUME;
    private static volatile float acousticIntensity = DEFAULT_ACOUSTIC_INTENSITY;

    /** The reception noise's volume: 0 plays every radio clean, up to {@link #MAX_RECEPTION_NOISE_VOLUME}. */
    public static float receptionNoiseVolume() {
        return receptionNoiseVolume;
    }

    /** The static, crackle and voice scale: 1 at the former always-on volume (0.5), up to 2. */
    public static float receptionNoiseScale() {
        return receptionNoiseVolume * 2.0f;
    }

    /** The simulated echo's scale: 0 dry, 1 as simulated, up to {@link #MAX_ACOUSTIC_INTENSITY}. */
    public static float acousticIntensity() {
        return acousticIntensity;
    }

    static void set(double volume, double intensity) {
        receptionNoiseVolume = (float) Math.max(0.0, Math.min(MAX_RECEPTION_NOISE_VOLUME, volume));
        acousticIntensity = (float) Math.max(0.0, Math.min(MAX_ACOUSTIC_INTENSITY, intensity));
    }

    static void reset() {
        set(DEFAULT_RECEPTION_NOISE_VOLUME, DEFAULT_ACOUSTIC_INTENSITY);
    }

    private RadioLiveSettings() {
    }
}
