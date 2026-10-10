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
                    + " faint static; the End lets faint enderman voices through now and then; Voidscape plays"
                    + " static only. A stored boolean from older versions resets to the default.")
            .defineInRange("receptionNoise", (double) RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    0.0D, (double) RadioLiveSettings.MAX_RECEPTION_NOISE_VOLUME);

    public static final ModConfigSpec.DoubleValue ACOUSTIC_INTENSITY = BUILDER
            .comment("Acoustic simulation intensity: scales the simulated echo (reflections and reverb); default 1.0."
                    + " Range 0.00-2.00, step 0.01. 0 leaves only the direct sound, which keeps its occlusion and"
                    + " 3D direction.")
            .defineInRange("acousticIntensity", (double) RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    0.0D, (double) RadioLiveSettings.MAX_ACOUSTIC_INTENSITY);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Copies the loaded values into {@link RadioLiveSettings}, or its defaults while unloaded. */
    public static void refresh() {
        if (SPEC.isLoaded()) {
            RadioLiveSettings.set(RECEPTION_NOISE.get(), ACOUSTIC_INTENSITY.get());
        } else {
            RadioLiveSettings.reset();
        }
    }

    /**
     * Changes the values in memory (the loaded config when there is one) and the audio threads'
     * copies; saving is the caller's choice.
     */
    public static void apply(double receptionNoiseVolume, double acousticIntensity) {
        if (SPEC.isLoaded()) {
            RECEPTION_NOISE.set(receptionNoiseVolume);
            ACOUSTIC_INTENSITY.set(acousticIntensity);
        }
        RadioLiveSettings.set(receptionNoiseVolume, acousticIntensity);
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
