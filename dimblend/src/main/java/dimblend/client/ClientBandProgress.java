package dimblend.client;

import dimblend.band.KnownBandSize;

/**
 * Client-side holder for the rotating dimension's band size pushed by
 * {@link dimblend.band.BandInfoSync}. Zero means unknown: the band progress
 * HUD hides until a positive size arrives. Writes through
 * {@link KnownBandSize} so common runtime (wall protection) can read the same
 * value without importing this client class.
 */
public final class ClientBandProgress {
    private ClientBandProgress() {
    }

    public static void apply(int bandSize) {
        KnownBandSize.apply(bandSize);
    }

    public static int bandSize() {
        return KnownBandSize.raw();
    }
}
