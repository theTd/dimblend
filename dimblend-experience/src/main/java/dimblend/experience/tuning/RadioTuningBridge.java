package dimblend.experience.tuning;

import dimblend.radio.api.RadioTuning;

/** Only loaded after the dimblend_radio presence check. */
final class RadioTuningBridge {
    static boolean isLoaded() { return RadioTuning.isLoaded(); }

    static double get(TuningOption option) {
        return option == TuningOption.RADIO_STATIC
                ? RadioTuning.receptionNoiseVolume()
                : RadioTuning.acousticIntensity();
    }

    /** Server thread: saves the world's radio config. */
    static void set(TuningOption option, double value) {
        if (option == TuningOption.RADIO_STATIC) {
            RadioTuning.setReceptionNoiseVolume(value);
        } else {
            RadioTuning.setAcousticIntensity(value);
        }
    }

    /** Remote client: memory only. */
    static void apply(TuningOption option, double value) {
        if (option == TuningOption.RADIO_STATIC) {
            RadioTuning.applyReceptionNoiseVolume(value);
        } else {
            RadioTuning.applyAcousticIntensity(value);
        }
    }

    private RadioTuningBridge() {
    }
}
