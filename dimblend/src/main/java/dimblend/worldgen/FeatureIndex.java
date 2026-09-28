package dimblend.worldgen;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * {@code FeatureSorter} stores one instance per equal {@code PlacedFeature}, but
 * {@code ChunkGenerator.applyBiomeDecoration} looks that instance up by identity.
 * A biome in the 3×3 that holds an equal copy (inline feature, biome-modifier
 * copy) gets {@code -1} and {@code features.get(-1)} aborts the chunk.
 */
public final class FeatureIndex {
    private FeatureIndex() {
    }

    /**
     * @return index into {@code features}, or {@code -1} when the feature is not in this step
     */
    public static <T> int resolve(List<T> features, ToIntFunction<T> identity, T feature) {
        int index = identity.applyAsInt(feature);
        if (index >= 0 && index < features.size()) {
            return index;
        }
        for (int i = 0; i < features.size(); i++) {
            if (feature.equals(features.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
