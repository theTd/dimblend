package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PreviewSelectionTest {
    @Test
    void aShapeWithAReadyMeshDrawsTheMesh() {
        assertEquals(
                PreviewSelection.Choice.MESH,
                PreviewSelection.forPredicted(true, true));
    }

    @Test
    void aShapeStillBuildingKeepsTheWireframe() {
        assertEquals(
                PreviewSelection.Choice.WIREFRAME,
                PreviewSelection.forPredicted(true, false));
    }

    @Test
    void noShapeKeepsTheWireframe() {
        assertEquals(
                PreviewSelection.Choice.WIREFRAME,
                PreviewSelection.forPredicted(false, false));
    }

    @Test
    void aReadyMeshWithoutAShapeIsStillAWireframe() {
        assertEquals(
                PreviewSelection.Choice.WIREFRAME,
                PreviewSelection.forPredicted(false, true));
    }
}
