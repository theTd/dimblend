package dimblend.spyglass.server;

import javax.annotation.Nullable;

/**
 * Mutable, thread-confined failure counter for one scan.
 *
 * <p>Shared by the structure-start scan and the shape extraction so both read passes of one request
 * are reported as a single throttled warning. Failures skip only the failing chunk; nothing aborts a
 * whole scan. The categories deliberately match vanilla's own read-failure modes plus the read-only
 * identity check this mod adds.
 */
final class RegionReadErrors {
    private int regionFailures;
    private int chunkReadFailures;
    private int parseFailures;
    private int identityFailures;
    private int unsupportedDataVersionSkips;
    private Throwable firstFailure;

    void regionFailure(Throwable failure) {
        this.regionFailures++;
        capture(failure);
    }

    void chunkReadFailure(Throwable failure) {
        this.chunkReadFailures++;
        capture(failure);
    }

    void parseFailure(Throwable failure) {
        this.parseFailures++;
        capture(failure);
    }

    void identityFailure(int chunkX, int chunkZ) {
        this.identityFailures++;
        capture(new IllegalStateException(
                "chunk read from region data does not report coordinates " + chunkX + "," + chunkZ));
    }

    void unsupportedDataVersionSkip() {
        this.unsupportedDataVersionSkips++;
    }

    private void capture(Throwable failure) {
        if (this.firstFailure == null) {
            this.firstFailure = failure;
        }
    }

    RegionScanResult.Counts snapshot() {
        return new RegionScanResult.Counts(
                this.regionFailures,
                this.chunkReadFailures,
                this.parseFailures,
                this.identityFailures,
                this.unsupportedDataVersionSkips,
                this.firstFailure);
    }

    @Nullable
    Throwable firstFailure() {
        return this.firstFailure;
    }
}
