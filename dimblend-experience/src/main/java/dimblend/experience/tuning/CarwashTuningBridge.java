package dimblend.experience.tuning;

import dimblend.carwash.api.CarwashTuning;

/** Only loaded after the dimblend_carwash presence check. */
final class CarwashTuningBridge {
    static boolean isLoaded() { return CarwashTuning.isLoaded(); }
    static double get() { return CarwashTuning.soilingProbabilityMultiplier(); }
    static void set(double value) { CarwashTuning.setSoilingProbabilityMultiplier(value); }

    private CarwashTuningBridge() {
    }
}
