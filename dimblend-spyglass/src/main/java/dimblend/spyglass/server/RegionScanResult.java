package dimblend.spyglass.server;

import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Result of one read-only region scan: the outlines plus per-category failure counts. Failures skip
 * only the failing chunk; the counts are reported as one throttled warning per dimension.
 */
record RegionScanResult(List<StructureOutline> outlines, Counts errors) {
    RegionScanResult {
        outlines = List.copyOf(outlines);
        if (errors == null) {
            throw new IllegalArgumentException("errors");
        }
    }

    /**
     * @param unsupportedDataVersionSkips chunks skipped because their DataVersion is not the running
     *                                    game's world data version (only current-format chunks are
     *                                    read; nothing is datafixed)
     */
    record Counts(
            int regionFailures,
            int chunkReadFailures,
            int parseFailures,
            int identityFailures,
            int unsupportedDataVersionSkips,
            @Nullable Throwable firstFailure) {
        boolean hasAny() {
            return total() > 0;
        }

        int total() {
            return this.regionFailures
                    + this.chunkReadFailures
                    + this.parseFailures
                    + this.identityFailures
                    + this.unsupportedDataVersionSkips;
        }
    }
}
