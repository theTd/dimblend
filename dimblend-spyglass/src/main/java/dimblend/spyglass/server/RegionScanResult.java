package dimblend.spyglass.server;

import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Result of one read-only region scan: the outlines plus per-category failure counts. Failures skip
 * only the failing chunk; the counts are reported as one throttled warning per dimension.
 *
 * <p>{@code absentCandidates} holds the candidates whose chunk had <b>no saved start for that
 * structure</b>. Two situations look the same on disk and both are legal world generation: the chunk
 * has not been written yet (never visited, or visited but not saved), or it was saved without a start
 * for that structure. Neither is an error, so they are not counted as failures; they are returned so
 * the caller can decide whether to replay generation for them ({@code StructurePredictor}).
 */
record RegionScanResult(
        List<StructureOutline> outlines,
        List<StructureCandidate> absentCandidates,
        Counts errors) {
    RegionScanResult {
        outlines = List.copyOf(outlines);
        absentCandidates = List.copyOf(absentCandidates);
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
