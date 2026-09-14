package dimblend.worldgen;

/**
 * Duck interface applied to {@link net.minecraft.world.level.levelgen.WorldGenerationContext}
 * by mixin. Lives outside {@code dimblend.mixin} so Mixin package isolation can load it.
 */
public interface WorldGenerationContextExtension {
    int dimblend$absoluteOffset();

    void dimblend$setAbsoluteOffset(int offset);
}
