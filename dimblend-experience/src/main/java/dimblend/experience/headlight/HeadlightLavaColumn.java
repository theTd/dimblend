package dimblend.experience.headlight;

/**
 * Per-cell decision of the lit-lava scan: is this lava cell a face the beams may light, and is it an ordinary
 * exposed top face or one cell of a vertical falling lava column?
 *
 * <p>The scan's original rule was "the same fluid above means the surface is higher up, skip", which is right for a
 * pool and a stair-stepped flow (each step has air above) and wrong for a falling column: lava that pours straight
 * down is a stack of {@code level=8} + {@code FALLING=true} blocks ({@code FlowingFluid.getFlowing(8, true)}, e.g.
 * {@code FlowingFluid.java:231-233}) in which <em>every</em> cell has the same fluid above, because
 * {@code LavaFluid.isSame} treats LAVA and FLOWING_LAVA as one fluid ({@code LavaFluid.java:149-152}). The whole
 * column was skipped, so a beam shining on a lavafall outlined nothing there (reported 2026-10-31).
 *
 * <p>The exception is one condition and no more: a cell whose own state is falling, with the same fluid above, is
 * itself reported, at its own {@code getOwnHeight()} ({@code FlowingFluid.java:468-471}). Every cell of the column
 * then contributes a face, and {@link LavaBoxOutline} merges the touching boxes into one vertical tube, so the
 * outline needs no change.
 *
 * <p>Nothing here touches a {@code Level}: the caller reads the block above, the fluid state and the exposed-top
 * rule, so the decision is a pure function the unit tests pin directly. A lava cell with no lava above keeps the
 * old rule exactly, and an own height below {@link #MIN_HEIGHT} is never a face in either case.
 *
 * <p><b>Why the solid-above test is not repeated for a column face.</b> A falling column cell always has lava
 * above it, so that cell can never cap a pool; lava's own shape is empty ({@code LiquidBlock.getShape} returns
 * {@code Shapes.empty()}), so it can never be {@code isSolidRender} either way. The caller therefore runs the
 * solid-above and fence-neighbourhood tests for {@link Decision#TOP_FACE} only, and a column face reaches the lit
 * test with the same cone/attenuation gate as any other face.
 *
 * <p><b>The block state behind {@code FALLING}.</b> {@code LiquidBlock} caches its block states as
 * {@code getFlowing(8, true)} for {@code LEVEL=8} ({@code LiquidBlock.java:70-77}), so the cell of a lavafall that
 * reads {@code level=8} is exactly a fluid state with {@code FALLING=true} and {@code getOwnHeight()} 8/9. A
 * flowing cell at any other level is {@code FALLING=false}; that is why the falling flag, not the level, is the
 * test.
 */
public final class HeadlightLavaColumn {
    /**
     * Below this the fluid state's own height is not a surface worth drawing: the scan used this bound before the
     * column rule existed and both face kinds keep it.
     */
    public static final float MIN_HEIGHT = 1.0E-3F;

    /** What a lava cell contributes to the highlight. */
    public enum Decision {
        /** No face: nothing above it can be drawn, or it is a buried cell of a non-falling body of lava. */
        SKIP,
        /** An exposed top face: the cell has no lava above it and passes the exposed-top rule. */
        TOP_FACE,
        /** One cell of a vertical falling column: the same fluid is above, but this cell's own state is falling. */
        COLUMN_FACE
    }

    /**
     * Whether this lava cell is a face the scan reports, and which kind.
     *
     * @param sameFluidAbove whether the cell above holds the same fluid (lava and flowing lava are one fluid,
     *                       {@code LavaFluid.isSame}): the original rule's skip condition
     * @param falling        whether this cell's own fluid state is {@code FALLING}: the column's exception
     * @param ownHeight      this cell's {@code FluidState.getOwnHeight()}, {@code amount / 9}
     */
    public static Decision decide(boolean sameFluidAbove, boolean falling, float ownHeight) {
        if (ownHeight < MIN_HEIGHT) {
            return Decision.SKIP;
        }
        if (sameFluidAbove) {
            return falling ? Decision.COLUMN_FACE : Decision.SKIP;
        }
        return Decision.TOP_FACE;
    }

    private HeadlightLavaColumn() {
    }
}