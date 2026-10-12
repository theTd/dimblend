package dimblend.experience.client;

import dev.ryanhcode.sable.SableClientConfig;

/** Sable's client config (snapshot interpolation delay). Only loaded after the sable presence check. */
final class SableClientTuningBridge {
    static boolean isLoaded() {
        return SableClientConfig.SPEC.isLoaded();
    }

    static double interpolationDelay() {
        return SableClientConfig.INTERPOLATION_DELAY.getAsDouble();
    }

    /** Sable reads the value every client tick, so the change applies immediately. */
    static void setInterpolationDelay(double ticks) {
        SableClientConfig.INTERPOLATION_DELAY.set(ticks);
        SableClientConfig.SPEC.save();
    }

    private SableClientTuningBridge() {
    }
}
