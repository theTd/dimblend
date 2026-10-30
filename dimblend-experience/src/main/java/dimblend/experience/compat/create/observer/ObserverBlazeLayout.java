package dimblend.experience.compat.create.observer;

/**
 * The observer blaze's vertical layout, in block units, kept free of client classes so the numbers can be pinned by
 * a plain unit test: the block draws a half-block cushion, the blaze stands on it, and the spyglass floats one block
 * above the blaze's head.
 *
 * <p>Every offset is measured from the block's own bottom face. The renderer applies {@link #BLAZE_RAISE} to the pose
 * stack before handing it to Create's {@code BlazeBurnerRenderer}, so the blaze, its hat, goggles, rods and flame quad
 * all move together; the flame particles and the spyglass are placed independently and must match.
 */
public final class ObserverBlazeLayout {
    /** Height of the cushion that fills the block's lower half; also the lift given to the blaze. */
    public static final float CUSHION_HEIGHT = 0.5F;
    /** The blaze stands on the cushion. */
    public static final float BLAZE_RAISE = CUSHION_HEIGHT;
    /** Half a block above the raised blaze's head (Create raises it by one block from the block's top face). */
    public static final float SPYGLASS_Y = BLAZE_RAISE + 1.5F;
    /** Flame particles start just above the raised blaze's shoulders; Create's own puff was 0.95 above the block. */
    public static final float FLAME_BASE_Y = BLAZE_RAISE + 0.95F;
    /** Vertical spread of one flame puff. */
    public static final float FLAME_SPREAD_Y = 0.3F;

    private ObserverBlazeLayout() {
    }
}