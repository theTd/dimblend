package dimblend.experience.compat.create;

/**
 * Accessor bridge for {@code SlidingDoorBlockEntity}'s package-private animation chaser.
 * Kept outside {@code dimblend.experience.mixin.**} per Mixin package classloading rules.
 */
public interface SlidingDoorAnimationAccess {
    float dimblend$animationValue(float partialTicks);

    void dimblend$setAnimationValue(float value);
}
