package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LabelPlacementTest {
    private static final int WIDTH = 60;
    private static final int HEIGHT = 21;

    @Test
    void placesLabelsThatDoNotOverlap() {
        List<LabelPlacement.Candidate<String>> placed = LabelPlacement.place(List.of(
                label("far", 900.0, 100.0F, 100.0F),
                label("near", 300.0, 300.0F, 100.0F)));

        assertEquals(List.of("near", "far"), names(placed));
    }

    @Test
    void theNearerStructureWinsAnOverlapWhateverTheInputOrder() {
        LabelPlacement.Candidate<String> far = label("far", 900.0, 100.0F, 100.0F);
        LabelPlacement.Candidate<String> near = label("near", 300.0, 120.0F, 105.0F);

        assertEquals(List.of("near"), names(LabelPlacement.place(List.of(far, near))));
        assertEquals(List.of("near"), names(LabelPlacement.place(List.of(near, far))));
    }

    @Test
    void keepsAtLeastTheGapBetweenLabels() {
        // Side by side: right edge of the first at 130, so the second may start at 130 + GAP.
        float exactGap = 100.0F + WIDTH + LabelPlacement.GAP;
        assertEquals(2, LabelPlacement.place(List.of(
                label("a", 300.0, 100.0F, 100.0F),
                label("b", 400.0, exactGap, 100.0F))).size());
        assertEquals(1, LabelPlacement.place(List.of(
                label("a", 300.0, 100.0F, 100.0F),
                label("b", 400.0, exactGap - 0.5F, 100.0F))).size());

        float verticalGap = 100.0F + HEIGHT + LabelPlacement.GAP;
        assertEquals(2, LabelPlacement.place(List.of(
                label("a", 300.0, 100.0F, 100.0F),
                label("b", 400.0, 100.0F, verticalGap))).size());
    }

    @Test
    void aSkippedLabelDoesNotBlockLaterOnes() {
        // b overlaps both a and c, a and c are apart: a (nearest) wins, b is dropped, c still fits.
        List<LabelPlacement.Candidate<String>> placed = LabelPlacement.place(List.of(
                label("c", 700.0, 220.0F, 100.0F),
                label("b", 500.0, 160.0F, 100.0F),
                label("a", 300.0, 100.0F, 100.0F)));

        assertEquals(List.of("a", "c"), names(placed));
    }

    @Test
    void equalDistancesKeepTheInputOrder() {
        List<LabelPlacement.Candidate<String>> placed = LabelPlacement.place(List.of(
                label("first", 400.0, 100.0F, 100.0F),
                label("second", 400.0, 110.0F, 100.0F)));

        assertEquals(List.of("first"), names(placed));
    }

    @Test
    void sitsAboveItsAnchorHorizontallyCentered() {
        LabelPlacement.Candidate<String> candidate = label("a", 300.0, 100.0F, 50.0F);

        assertEquals(100.0F - WIDTH / 2.0F, candidate.left());
        assertEquals(50.0F - HEIGHT, candidate.top());
    }

    private static LabelPlacement.Candidate<String> label(String name, double distance, float x, float y) {
        return LabelPlacement.Candidate.above(name, distance, x, y, WIDTH, HEIGHT);
    }

    private static List<String> names(List<LabelPlacement.Candidate<String>> placed) {
        return placed.stream().map(LabelPlacement.Candidate::label).toList();
    }
}
