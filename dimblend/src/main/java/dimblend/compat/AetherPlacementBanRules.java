package dimblend.compat;

import java.util.function.IntFunction;

/** Position-only policy, independent of Aether recipes and Minecraft bootstrap. */
public final class AetherPlacementBanRules {
    private AetherPlacementBanRules() { }

    public static boolean shouldLiftBan(boolean rotating, int blockX, int bandSize,
                                        IntFunction<String> laneAtRegion) {
        return rotating && bandSize > 0 && laneAtRegion != null
                && "aether".equals(laneAtRegion.apply(Math.floorDiv(blockX, bandSize)));
    }

    /** Client has no world seed/layout. Prediction uses the checked biome; the server rechecks the lane. */
    public static boolean predictLiftBan(boolean rotating, String biomeNamespace) {
        return rotating && "aether".equals(biomeNamespace);
    }
}