package dimblend.spyglass.client;

/**
 * Chooses between a predicted structure's template mesh and its wireframe fallback.
 *
 * <p>The wireframe is both the "no template" state and the placeholder while the preview mesh is
 * still being built, so a predicted structure is never invisible: it either has its mesh or a line
 * box. This is pure so the three cases can be tested without a renderer.
 */
final class PreviewSelection {
    enum Choice {
        MESH,
        WIREFRAME
    }

    static Choice forPredicted(boolean hasShape, boolean meshReady) {
        return hasShape && meshReady ? Choice.MESH : Choice.WIREFRAME;
    }

    private PreviewSelection() {
    }
}
