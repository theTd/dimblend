package dimblend.radio;

/**
 * The radio settings as the audio threads read them: plain volatile copies of
 * {@link RadioServerConfig}, refreshed when that config loads, reloads, unloads or is edited.
 * Defaults apply while no server config is present (title screen, a server without the mod).
 */
public final class RadioLiveSettings {
    public static final boolean DEFAULT_RECEPTION_NOISE = true;
    public static final float DEFAULT_ACOUSTIC_INTENSITY = 1.0f;
    public static final float MAX_ACOUSTIC_INTENSITY = 2.0f;

    private static volatile boolean receptionNoise = DEFAULT_RECEPTION_NOISE;
    private static volatile float acousticIntensity = DEFAULT_ACOUSTIC_INTENSITY;

    /** Whether radios pick up place-dependent static, crackle and voices. */
    public static boolean receptionNoise() {
        return receptionNoise;
    }

    /** The simulated echo's scale: 0 dry, 1 as simulated, up to {@link #MAX_ACOUSTIC_INTENSITY}. */
    public static float acousticIntensity() {
        return acousticIntensity;
    }

    static void set(boolean noise, double intensity) {
        receptionNoise = noise;
        acousticIntensity = (float) Math.max(0.0, Math.min(MAX_ACOUSTIC_INTENSITY, intensity));
    }

    static void reset() {
        set(DEFAULT_RECEPTION_NOISE, DEFAULT_ACOUSTIC_INTENSITY);
    }

    private RadioLiveSettings() {
    }
}
