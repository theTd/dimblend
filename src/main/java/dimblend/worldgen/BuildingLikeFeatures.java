package dimblend.worldgen;

import java.util.List;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Class-name based small-building detector for corridor and partition-wall
 * retreat, cached per class. Mod structures below the structure-start threshold
 * (Twilight Forest druid huts, wells, ruins, graveyards, foundations, monoliths)
 * ride the biome-decoration feature pipeline, so
 * {@link OakTrackCorridor#dropBlockedStarts} and
 * {@link RegionBoundaryNoStructureZone#dropBlockedStarts} never see them.
 * Detection walks the superclass chain: every template-based building (subclass of
 * TF's {@code TemplateFeature}) is caught via its family superclass, while the
 * direct-Feature buildings are listed by exact name. Matching is exact-name, not
 * keyword-substring, so flowers, lakes, spikes and other decorations stay untouched.
 */
public final class BuildingLikeFeatures {
    /** Superclass simple names marking a building family (matched anywhere in the chain). */
    private static final List<String> SUPERCLASS_NAMES = List.of(
            "TemplateFeature");

    /** Direct Feature subclasses that are buildings but share no family superclass. */
    private static final List<String> EXACT_NAMES = List.of(
            "GraveyardFeature", "FoundationFeature", "MonolithFeature");

    private static final ClassValue<Boolean> CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Feature.class; c = c.getSuperclass()) {
                String name = c.getSimpleName();
                if (SUPERCLASS_NAMES.contains(name) || EXACT_NAMES.contains(name)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };

    private BuildingLikeFeatures() {
    }

    public static boolean isBuildingLike(Feature<?> feature) {
        return CACHE.get(feature.getClass());
    }
}
