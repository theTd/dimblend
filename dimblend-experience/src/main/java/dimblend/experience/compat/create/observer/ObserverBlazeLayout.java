package dimblend.experience.compat.create.observer;

/**
 * The observer blaze's vertical layout, in block units, kept free of client classes so the numbers can be pinned by
 * a plain unit test: the block draws a half-block cushion, the blaze stands on it, and the spyglass floats above the
 * blaze's head.
 *
 * <p>Every offset is measured from the block's own bottom face. The renderer applies {@link #BLAZE_RAISE} to the pose
 * stack before handing it to Create's {@code BlazeBurnerRenderer}, so the blaze, its hat, goggles, rods and flame quad
 * all move together; the flame particles and the spyglass are placed independently and must match.
 */
public final class ObserverBlazeLayout {
    /** Height of the cushion that fills the block's lower half. */
    public static final float CUSHION_HEIGHT = 0.5F;

    /**
     * Where Create's blaze models start, measured from the block's bottom face.
     *
     * <p>Every blaze partial model Create ships ({@code super}, {@code super_active}, {@code active}, {@code idle},
     * {@code inert}) has its lowest element at {@code y=6} of 16, so a bare burner's flames hang 6/16 above the block
     * floor -- the brazier underneath fills that space. This block draws only the cushion and keeps Create's blaze, so
     * that 6/16 is a gap with nothing in it: lifting by the cushion height alone left exactly that much air between
     * the flames and the cushion top, which reads as a floating blaze.
     */
    public static final float BLAZE_MODEL_BOTTOM = 6.0F / 16.0F;

    /** Where Create's blaze models top out, measured from the block's bottom face (14/16 for every lit variant). */
    public static final float BLAZE_MODEL_TOP = 14.0F / 16.0F;

    /**
     * Extra lift on top of the cushion alignment, in block units: a quarter block as of 2026-11-04.
     *
     * <p>History: on 2026-10-30 the assembly was raised 0.5 and the user reported in game that it
     * still floated; 2026-10-31 it went back onto the cushion, which is this constant being 0.
     * The 2026-11-03 requirement asked for a lift and the decision page answered "one more block
     * on top of the current position", so it became 1.0 and {@link #BLAZE_RAISE} 1.125 -- a full
     * block of daylight between the flames and the cushion, which is the very "floating" the user
     * had complained about a month earlier. 2026-11-04 walked it back down: 0.5 first, then this
     * quarter block, giving {@link #BLAZE_RAISE} = 0.375 and 4/16 of gap above the cushion top.
     */
    public static final float RAISE_ABOVE_CUSHION = 0.25F;

    /**
     * The blaze stands this far above the block floor.
     *
     * <p>{@code CUSHION_HEIGHT - BLAZE_MODEL_BOTTOM} is the alignment that puts the flames exactly
     * on the cushion top; {@link #RAISE_ABOVE_CUSHION} is added on top of it.
     */
    public static final float BLAZE_RAISE =
            CUSHION_HEIGHT - BLAZE_MODEL_BOTTOM + RAISE_ABOVE_CUSHION;

    /**
     * Half a block above the raised blaze's head.
     *
     * <p>Create's blaze model tops out at {@code 14/16}, so the head sits at {@code raise + 0.875}; the old
     * {@code raise + 1.5} was a further 1/8 above "half a block over the head" and is corrected here rather than
     * carried along with the lift change.
     */
    public static final float SPYGLASS_Y = BLAZE_RAISE + BLAZE_MODEL_TOP + 0.5F;

    /** Flame particles start just above the raised blaze's shoulders; Create's own puff was 0.95 above the block. */
    public static final float FLAME_BASE_Y = BLAZE_RAISE + 0.95F;

    /** Vertical spread of one flame puff. */
    public static final float FLAME_SPREAD_Y = 0.3F;

    private ObserverBlazeLayout() {
    }
}