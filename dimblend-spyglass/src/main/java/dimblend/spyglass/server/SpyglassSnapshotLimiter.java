package dimblend.spyglass.server;

import dimblend.spyglass.net.SpyglassSnapshotPayload;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureOutline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Fits the scanned outlines into the snapshot limits.
 *
 * <ul>
 *   <li>Distance is filtered against the padded snapshot ring, not the exact display ring: the
 *       client re-filters against its live position and live reveal distance every frame, and a
 *       snapshot is reused while the player stays within the movement threshold and the reveal
 *       distance stays within its own threshold ({@link SpyglassRefreshPolicy}). Both the outer center
 *       distance and the inner farthest-corner distance are padded.</li>
 *   <li>A structure with more than {@link SpyglassSnapshotPayload#MAX_PIECES_PER_STRUCTURE} pieces
 *       keeps its largest-volume pieces instead of being dropped. The exact union bounds stay with
 *       the outline, so center and distance do not drift.</li>
 *   <li>When the total piece cap is reached, the remaining (farther) structures are dropped.</li>
 * </ul>
 *
 * <p>Each selected entry keeps the <b>full</b> piece list next to the transmitted (possibly
 * truncated) outline: shape extraction has to look at every piece the saved start contains.
 */
final class SpyglassSnapshotLimiter {
    private SpyglassSnapshotLimiter() {
    }

    static LimitResult limit(
            double playerX, double playerZ, double revealBlocks, List<StructureOutline> outlines) {
        List<MeasuredOutline> inRange = new ArrayList<>();
        for (StructureOutline outline : outlines) {
            double centerDistance = SpyglassDistance.horizontal(playerX, playerZ, outline);
            if (!SpyglassDistance.inSnapshotRange(
                    centerDistance,
                    SpyglassDistance.farthestHorizontal(playerX, playerZ, outline),
                    revealBlocks,
                    SpyglassRefreshPolicy.SNAPSHOT_PAD_BLOCKS)) {
                continue;
            }
            inRange.add(new MeasuredOutline(outline, centerDistance));
        }
        inRange.sort(Comparator.comparingDouble(MeasuredOutline::distance));

        List<LimitedStructure> selected = new ArrayList<>(Math.min(
                inRange.size(), SpyglassSnapshotPayload.MAX_STRUCTURES));
        int totalPieces = 0;
        int truncatedStructures = 0;
        int omittedStructures = 0;
        // Once a structure no longer fits the total budget, it and everything farther is dropped:
        // skipping it and continuing would keep a smaller, farther structure in place of a nearer one.
        boolean budgetExhausted = false;
        for (MeasuredOutline measured : inRange) {
            if (budgetExhausted || selected.size() >= SpyglassSnapshotPayload.MAX_STRUCTURES) {
                omittedStructures++;
                continue;
            }
            StructureOutline outline = measured.outline();
            StructureOutline transmitted = outline;
            if (outline.pieces().size() > SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE) {
                transmitted = outline.withPieces(
                        largestPieces(outline.pieces(), SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE));
                truncatedStructures++;
            }
            int pieceCount = transmitted.pieces().size();
            if (pieceCount > SpyglassSnapshotPayload.MAX_TOTAL_PIECES - totalPieces) {
                budgetExhausted = true;
                omittedStructures++;
                continue;
            }
            selected.add(new LimitedStructure(transmitted, outline.pieces()));
            totalPieces += pieceCount;
        }
        return new LimitResult(
                List.copyOf(selected), totalPieces, truncatedStructures, omittedStructures);
    }

    /**
     * Keeps the {@code limit} pieces with the largest volume, preserving the original piece order in
     * the result so equal inputs produce equal payloads.
     */
    static List<OutlineBox> largestPieces(List<OutlineBox> pieces, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Non-positive piece limit: " + limit);
        }
        if (pieces.size() <= limit) {
            return pieces;
        }
        Integer[] byVolume = new Integer[pieces.size()];
        for (int i = 0; i < byVolume.length; i++) {
            byVolume[i] = i;
        }
        // Stable sort: ties keep the saved order.
        Arrays.sort(byVolume, Comparator.comparingDouble(
                (Integer index) -> pieces.get(index).volume()).reversed());
        boolean[] keep = new boolean[pieces.size()];
        for (int i = 0; i < limit; i++) {
            keep[byVolume[i]] = true;
        }
        List<OutlineBox> kept = new ArrayList<>(limit);
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                kept.add(pieces.get(i));
            }
        }
        return List.copyOf(kept);
    }

    private record MeasuredOutline(StructureOutline outline, double distance) {
    }

    /** One selected structure: the transmitted outline plus every saved piece for shape extraction. */
    record LimitedStructure(StructureOutline outline, List<OutlineBox> allPieces) {
        LimitedStructure {
            if (allPieces.isEmpty()) {
                throw new IllegalArgumentException("A structure needs at least one piece");
            }
            allPieces = List.copyOf(allPieces);
        }
    }

    record LimitResult(
            List<LimitedStructure> selected,
            int totalPieces,
            int truncatedStructures,
            int omittedStructures) {
        /** The outlines as sent in the snapshot, nearest first. */
        List<StructureOutline> structures() {
            return this.selected.stream().map(LimitedStructure::outline).toList();
        }
    }
}
