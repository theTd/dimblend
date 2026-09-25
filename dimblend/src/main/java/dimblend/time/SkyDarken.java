package dimblend.time;

/**
 * Vanilla {@code Level.updateSkyBrightness} formula, evaluated at an arbitrary
 * day time. Kept free of Minecraft types so the midnight/noon table can be
 * unit-tested without the client/server classpath.
 *
 * <p>Must stay in lockstep with {@code DimensionType.timeOfDay} (no fixed_time)
 * and {@code Level.updateSkyBrightness}.
 */
public final class SkyDarken {
    private SkyDarken() {
    }

    public static int of(long dayTime, float rainLevel, float thunderLevel) {
        double rain = 1.0 - (double) (rainLevel * 5.0F) / 16.0;
        double thunder = 1.0 - (double) (thunderLevel * 5.0F) / 16.0;
        double sun = 0.5 + 2.0 * clamp(Math.cos(timeOfDay(dayTime) * (Math.PI * 2)), -0.25, 0.25);
        return (int) ((1.0 - sun * rain * thunder) * 11.0);
    }

    static float timeOfDay(long dayTime) {
        double wrapped = frac((double) dayTime / 24000.0 - 0.25);
        double curve = 0.5 - Math.cos(wrapped * Math.PI) / 2.0;
        return (float) (wrapped * 2.0 + curve) / 3.0F;
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
