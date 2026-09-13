package dimblend.client;

/**
 * Client-side holder for the rotating dimension's band size pushed by
 * {@link dimblend.band.BandInfoSync}. Zero means unknown: the band progress
 * HUD hides until a positive size arrives.
 */
public final class ClientBandProgress {
    private static volatile int bandSize;

    private ClientBandProgress() {
    }

    public static void apply(int bandSize) {
        ClientBandProgress.bandSize = bandSize;
    }

    public static int bandSize() {
        return bandSize;
    }
}
