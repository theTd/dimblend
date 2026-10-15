package dimblend.radio;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * World-wide radio settings. A SERVER config: NeoForge sends it to clients on login, so every
 * listener hears the same reception rules; live panel edits reach clients through
 * {@link dimblend.radio.api.RadioTuning}. The audio threads read {@link RadioLiveSettings}.
 */
public final class RadioServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue RECEPTION_NOISE = BUILDER
            .comment("Radio reception noise volume: 0 plays every radio clean, 0.5 the former always-on"
                    + " level, up to 2. Overworld and underground play clean; the Nether and modded areas add"
                    + " faint static; the End lets faint enderman and shulker voices through now and then;"
                    + " Voidscape plays static only. A stored boolean from older versions resets to the default.")
            .defineInRange("receptionNoise", (double) RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    0.0D, (double) RadioLiveSettings.MAX_RECEPTION_NOISE_VOLUME);

    public static final ModConfigSpec.DoubleValue END_VOICE_FREQUENCY = BUILDER
            .comment("End voice frequency multiplier: 1 is the original cadence (first voice after 10-30 seconds,"
                    + " then every 25-70 seconds), default 2 doubles it. Range 0.0-4.0, step 0.1;"
                    + " 0 disables new voices. Enderman and shulker clips are selected with weights 7 and 1.")
            .defineInRange("endVoiceFrequency", (double) RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY,
                    0.0D, (double) RadioLiveSettings.MAX_END_VOICE_FREQUENCY);

    public static final ModConfigSpec.DoubleValue ACOUSTIC_INTENSITY = BUILDER
            .comment("Acoustic simulation intensity: scales the simulated echo (reflections and reverb); default 1.0."
                    + " Range 0.00-2.00, step 0.01. 0 leaves only the direct sound, which keeps its occlusion and"
                    + " 3D direction.")
            .defineInRange("acousticIntensity", (double) RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    0.0D, (double) RadioLiveSettings.MAX_ACOUSTIC_INTENSITY);

    public static final ModConfigSpec.DoubleValue END_VOICE_VOLUME = BUILDER
            .comment("End voice volume: scales the enderman and shulker clips heard in the End; default 0.75,"
                    + " 1.5 times the former hard-coded gain. Range 0.00-2.00, step 0.01. 0.5 restores the"
                    + " former loudness; 0 schedules no voices (the music is not ducked either).")
            .defineInRange("endVoiceVolume", (double) RadioLiveSettings.DEFAULT_END_VOICE_VOLUME,
                    0.0D, (double) RadioLiveSettings.MAX_END_VOICE_VOLUME);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Copies the loaded values into {@link RadioLiveSettings}, or its defaults while unloaded. */
    public static void refresh() {
        if (SPEC.isLoaded()) {
            RadioLiveSettings.set(RECEPTION_NOISE.get(), ACOUSTIC_INTENSITY.get(), END_VOICE_FREQUENCY.get(),
                    END_VOICE_VOLUME.get());
        } else {
            RadioLiveSettings.reset();
        }
    }

    /**
     * Changes the values in memory (the loaded config when there is one) and the audio threads'
     * copies; saving is the caller's choice.
     */
    public static void apply(double receptionNoiseVolume, double acousticIntensity) {
        apply(receptionNoiseVolume, acousticIntensity, RadioLiveSettings.endVoiceFrequency());
    }

    public static void apply(double receptionNoiseVolume, double acousticIntensity, double voiceFrequency) {
        apply(receptionNoiseVolume, acousticIntensity, voiceFrequency, RadioLiveSettings.endVoiceVolume());
    }

    public static void apply(double receptionNoiseVolume, double acousticIntensity, double voiceFrequency,
            double voiceVolume) {
        if (SPEC.isLoaded()) {
            RECEPTION_NOISE.set(receptionNoiseVolume);
            ACOUSTIC_INTENSITY.set(acousticIntensity);
            END_VOICE_FREQUENCY.set(voiceFrequency);
            END_VOICE_VOLUME.set(voiceVolume);
        }
        RadioLiveSettings.set(receptionNoiseVolume, acousticIntensity, voiceFrequency, voiceVolume);
    }

    static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) refresh();
    }

    static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) refresh();
    }

    static void onUnloading(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) RadioLiveSettings.reset();
    }

    private RadioServerConfig() {
    }
}
