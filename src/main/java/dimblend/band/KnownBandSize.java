package dimblend.band;

import dimblend.worldgen.BandIndex;

/**
 * Last band size pushed to this process by {@link BandInfoSync} /
 * {@link dimblend.client.ClientBandProgress}. Zero means never synced.
 *
 * Shared so worldgen/runtime protection can align client columns to {@code x % bandSize}
 * without importing client-only HUD types. Falls back to
 * {@link BandIndex#DEFAULT_BAND_SIZE} when the packet has not arrived yet.
 */
public final class KnownBandSize {
    private static volatile int bandSize;

    private KnownBandSize() {
    }

    public static void apply(int bandSize) {
        KnownBandSize.bandSize = bandSize;
    }

    public static int raw() {
        return bandSize;
    }

    public static int orDefault() {
        return bandSize > 0 ? bandSize : BandIndex.DEFAULT_BAND_SIZE;
    }
}
