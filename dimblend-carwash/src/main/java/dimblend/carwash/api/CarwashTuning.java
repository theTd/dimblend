package dimblend.carwash.api;

import dimblend.carwash.Config;

/** Server-thread entry point for optional configuration panels in other mods. */
public final class CarwashTuning {
    public static boolean isLoaded() {
        return Config.SPEC.isLoaded();
    }

    public static double soilingProbabilityMultiplier() {
        return isLoaded() ? Config.SOILING_PROBABILITY_MULTIPLIER.get() : 0.5D;
    }

    public static void setSoilingProbabilityMultiplier(double value) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D || (value > 0.0D && value < 0.01D)
                || Math.abs(value * 100.0D - Math.rint(value * 100.0D)) > 1.0E-7D) {
            throw new IllegalArgumentException("Multiplier must be 0.00-1.00 in steps of 0.01");
        }
        Config.SOILING_PROBABILITY_MULTIPLIER.set(value);
        Config.SPEC.save();
    }

    private CarwashTuning() {
    }
}
