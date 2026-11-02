package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PreviewBudgetTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final StructureKey KEY = new StructureKey(ID, 0, 0);

    @Test
    void takesTheNearestPredictedStructures() {
        StructureOutline near = at(200, true);
        StructureOutline far = at(800, true);

        assertEquals(List.of(near), PreviewBudget.nearest(List.of(far, near), 0, 0, 1));
    }

    @Test
    void ignoresSavedStructures() {
        StructureOutline saved = at(100, false);
        StructureOutline predicted = at(700, true);

        assertEquals(List.of(predicted), PreviewBudget.nearest(List.of(saved, predicted), 0, 0, 8));
    }

    @Test
    void fewerCandidatesThanTheBudgetAreAllReturned() {
        StructureOutline first = at(300, true);
        StructureOutline second = at(900, true);

        assertEquals(List.of(first, second), PreviewBudget.nearest(List.of(second, first), 0, 0, 8));
    }

    @Test
    void zeroBudgetIsEmpty() {
        assertTrue(PreviewBudget.nearest(List.of(at(300, true)), 0, 0, 0).isEmpty());
    }

    @Test
    void noPredictedStructuresIsEmpty() {
        assertTrue(PreviewBudget.nearest(List.of(at(300, false)), 0, 0, 8).isEmpty());
    }

    private static StructureOutline at(int x, boolean predicted) {
        OutlineBox box = new OutlineBox(x, 0, 0, x, 1, 0);
        return predicted
                ? StructureOutline.predicted(KEY, List.of(box))
                : StructureOutline.of(KEY, List.of(box));
    }
}
