package dimblend.radio;

/**
 * The radio settings as the audio threads read them: plain volatile copies of
 * {@link RadioServerConfig}, refreshed when that config loads, reloads, unloads or is edited.
 * Defaults apply while no server config is present (title screen, a server without the mod).
 */
public final class RadioLiveSettings {
    /** The former always-on noise level, now the volume slider's middle. */
    public static final float DEFAULT_RECEPTION_NOISE_VOLUME = 0.5f;
    public static final float MAX_RECEPTION_NOISE_VOLUME = 2.0f;
    public static final float DEFAULT_ACOUSTIC_INTENSITY = 1.0f;
    public static final float MAX_ACOUSTIC_INTENSITY = 2.0f;
    /** Twice the original End voice cadence; 1 restores the original 25-70 second spacing. */
    public static final float DEFAULT_END_VOICE_FREQUENCY = 2.0f;
    public static final float MAX_END_VOICE_FREQUENCY = 4.0f;
    /**
     * End voice volume: 1.5 times the former hard-coded gain (0.5), so 0.5 restores the old
     * loudness and 0 schedules no voices.
     */
    public static final float DEFAULT_END_VOICE_VOLUME = 0.75f;
    /** Same ceiling as the noise volume. */
    public static final float MAX_END_VOICE_VOLUME = 2.0f;

    private static volatile float receptionNoiseVolume = DEFAULT_RECEPTION_NOISE_VOLUME;
    private static volatile float acousticIntensity = DEFAULT_ACOUSTIC_INTENSITY;
    private static volatile float endVoiceFrequency = DEFAULT_END_VOICE_FREQUENCY;
    private static volatile float endVoiceVolume = DEFAULT_END_VOICE_VOLUME;

    /** The reception noise's volume: 0 plays every radio clean, up to {@link #MAX_RECEPTION_NOISE_VOLUME}. */
    public static float receptionNoiseVolume() {
        return receptionNoiseVolume;
    }

    /** The static, crackle and voice scale: 1 at the former always-on volume (0.5), up to 4. */
    public static float receptionNoiseScale() {
        return receptionNoiseVolume * 2.0f;
    }

    /** The simulated echo's scale: 0 dry, 1 as simulated, up to {@link #MAX_ACOUSTIC_INTENSITY}. */
    public static float acousticIntensity() {
        return acousticIntensity;
    }

    /** End voice countdown speed: 0 disables new voices, 2 is twice the original cadence. */
    public static float endVoiceFrequency() {
        return endVoiceFrequency;
    }

    /** End voice gain: 0.5 is the former hard-coded loudness, 0 schedules no voices. */
    public static float endVoiceVolume() {
        return endVoiceVolume;
    }

    static void set(double volume, double intensity, double voiceFrequency, double voiceVolume) {
        receptionNoiseVolume = (float) Math.max(0.0, Math.min(MAX_RECEPTION_NOISE_VOLUME, volume));
        acousticIntensity = (float) Math.max(0.0, Math.min(MAX_ACOUSTIC_INTENSITY, intensity));
        endVoiceFrequency = (float) Math.max(0.0, Math.min(MAX_END_VOICE_FREQUENCY, voiceFrequency));
        endVoiceVolume = (float) Math.max(0.0, Math.min(MAX_END_VOICE_VOLUME, voiceVolume));
    }

    static void reset() {
        set(DEFAULT_RECEPTION_NOISE_VOLUME, DEFAULT_ACOUSTIC_INTENSITY, DEFAULT_END_VOICE_FREQUENCY,
                DEFAULT_END_VOICE_VOLUME);
    }

    private RadioLiveSettings() {
    }
}
