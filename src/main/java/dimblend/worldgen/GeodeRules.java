package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;

/**
 * Surface-lane geode policy for the rotating dimension, applied at
 * {@code ConfiguredFeature#place}. Vanilla amethyst and any mod feature that
 * uses {@link GeodeConfiguration} (BOP and others) are cancelled on the
 * surface lane so they cannot punch through the Y0–320 surface slice.
 * Underground and other lanes are left untouched.
 */
public final class GeodeRules {
    private GeodeRules() {
    }

    /**
     * {@code null} = run vanilla placement. Non-null is the value
     * {@code ConfiguredFeature#place} should return instead.
     */
    @Nullable
    public static Boolean intercept(FeatureConfiguration config, WorldGenLevel level, BlockPos pos) {
        if (!(config instanceof GeodeConfiguration)) {
            return null;
        }
        if (level.getLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return null;
        }
        if (!(level.getLevel().getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating)) {
            return null;
        }
        if (!BandLayout.isSurfaceOverworld(rotating.delegateForBlockX(pos.getX()))) {
            return null;
        }
        return Boolean.FALSE;
    }
}
