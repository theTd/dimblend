package dimblend.experience.compat.simurail;

import java.util.Locale;
import java.util.OptionalDouble;

/** Along-track metres per second divided by the input-rpm reference speed. */
public final class TractionEfficiency {
    public static OptionalDouble percentage(double alongTrackMetresPerSecond, double inputRpm) {
        if (!Double.isFinite(alongTrackMetresPerSecond) || !Double.isFinite(inputRpm)
                || Math.abs(inputRpm) <= 4.0) {
            return OptionalDouble.empty();
        }
        double percent = Math.abs(alongTrackMetresPerSecond) / (Math.abs(inputRpm) / 4.0 - 1.0) * 100.0;
        return Double.isFinite(percent) ? OptionalDouble.of(percent) : OptionalDouble.empty();
    }

    public static String format(double alongTrackMetresPerSecond, double inputRpm) {
        OptionalDouble percent = percentage(alongTrackMetresPerSecond, inputRpm);
        return percent.isPresent() ? String.format(Locale.ROOT, "%.2f%%", percent.getAsDouble()) : "--";
    }

    private TractionEfficiency() { }
}
