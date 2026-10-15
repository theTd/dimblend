package dimblend.experience.tuning;

import dimblend.radio.api.RadioTuning;

/** Only loaded after the dimblend_radio presence check. */
final class RadioTuningBridge {
    static boolean isLoaded() { return RadioTuning.isLoaded(); }

    static double get(TuningOption option) {
        return switch (option) {
            case RADIO_STATIC -> RadioTuning.receptionNoiseVolume();
            case RADIO_END_VOICE_FREQUENCY -> RadioTuning.endVoiceFrequency();
            case RADIO_END_VOICE_VOLUME -> RadioTuning.endVoiceVolume();
            case RADIO_ACOUSTIC_INTENSITY -> RadioTuning.acousticIntensity();
            default -> throw new IllegalArgumentException("Not a radio tuning option: " + option);
        };
    }

    /** Server thread: saves the world's radio config. */
    static void set(TuningOption option, double value) {
        switch (option) {
            case RADIO_STATIC -> RadioTuning.setReceptionNoiseVolume(value);
            case RADIO_END_VOICE_FREQUENCY -> RadioTuning.setEndVoiceFrequency(value);
            case RADIO_END_VOICE_VOLUME -> RadioTuning.setEndVoiceVolume(value);
            case RADIO_ACOUSTIC_INTENSITY -> RadioTuning.setAcousticIntensity(value);
            default -> throw new IllegalArgumentException("Not a radio tuning option: " + option);
        }
    }

    /** Remote client: memory only. */
    static void apply(TuningOption option, double value) {
        switch (option) {
            case RADIO_STATIC -> RadioTuning.applyReceptionNoiseVolume(value);
            case RADIO_END_VOICE_FREQUENCY -> RadioTuning.applyEndVoiceFrequency(value);
            case RADIO_END_VOICE_VOLUME -> RadioTuning.applyEndVoiceVolume(value);
            case RADIO_ACOUSTIC_INTENSITY -> RadioTuning.applyAcousticIntensity(value);
            default -> throw new IllegalArgumentException("Not a radio tuning option: " + option);
        }
    }

    private RadioTuningBridge() {
    }
}
