package dimblend.worldgen;

import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Class-name based tree/fungus detector for corridor clearance, cached per class.
 * Walks the superclass chain so subclasses whose own name lacks a keyword
 * (e.g. FallenLogFeature extends TreeFeature) are still detected. Keywords cover
 * the vertical plant features present in this modpack: trees, huge fungi and
 * mushrooms, chorus, stems, stalks, fallen logs, roots, vines, thorns, brambles.
 * Matching is case-insensitive so CamelCase mid-words like Cradlewood/Jinglestem hit.
 */
public final class TreeLikeFeatures {
    private static final List<String> KEYWORDS = List.of(
            "tree", "fungus", "mushroom", "shroom", "chorus", "wood", "stem", "stalk", "log", "mold",
            "root", "vine", "thorn", "bramble", "post");

    private static final ClassValue<Boolean> CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Feature.class; c = c.getSuperclass()) {
                String name = c.getSimpleName().toLowerCase(Locale.ROOT);
                for (String keyword : KEYWORDS) {
                    if (name.contains(keyword)) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        }
    };

    private TreeLikeFeatures() {
    }

    public static boolean isTreeLike(Feature<?> feature) {
        return CACHE.get(feature.getClass());
    }
}
