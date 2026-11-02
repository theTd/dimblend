package dimblend.spyglass.server;

import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureOutline;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses which predicted structures get a template preview in one snapshot: the nearest ones, so
 * the preview budget is spent where the player is looking and a large scan cannot turn into a large
 * amount of template work.
 */
final class PreviewBudget {
    /** M2 D11: at most eight previews per snapshot. */
    static final int MAX_PREVIEWS = 8;

    static List<StructureOutline> nearest(
            List<StructureOutline> outlines, double playerX, double playerZ, int max) {
        if (max <= 0) {
            return List.of();
        }
        List<StructureOutline> predicted = new ArrayList<>();
        for (StructureOutline outline : outlines) {
            if (outline.predicted()) {
                predicted.add(outline);
            }
        }
        predicted.sort(Comparator.comparingDouble(
                outline -> SpyglassDistance.horizontal(playerX, playerZ, outline)));
        return List.copyOf(predicted.subList(0, Math.min(max, predicted.size())));
    }

    private PreviewBudget() {
    }
}
