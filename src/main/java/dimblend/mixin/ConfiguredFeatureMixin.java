package dimblend.mixin;

import dimblend.worldgen.BuildingLikeFeatures;
import dimblend.worldgen.GeodeRules;
import dimblend.worldgen.OakTrackCorridor;
import dimblend.worldgen.OrePileRules;
import dimblend.worldgen.RegionBoundaryNoStructureZone;
import dimblend.worldgen.TreeLikeFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Funnels every biome-decoration feature placement (vanilla and modded alike — custom Feature
 * classes never go through TreeFeature). Surface-lane geodes are cancelled by
 * {@link GeodeRules} and ore piles in the rotating dimension are gated by
 * {@link OrePileRules} before the retreat rules: small buildings that ride the
 * decoration pipeline instead of structure starts (Twilight Forest huts, wells, ruins,
 * graveyards) retreat from the corridor no-structure zone and from
 * {@link RegionBoundaryNoStructureZone} at walled region boundaries, while
 * tree/fungus-like features only retreat when their origin column crosses the
 * corridor vault, so post-decoration carving cannot leave unsupported canopies.
 */
@Mixin(ConfiguredFeature.class)
public abstract class ConfiguredFeatureMixin {

    @Shadow
    public abstract Feature<?> feature();

    @Shadow
    public abstract FeatureConfiguration config();

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void dimblend$retreatBlockedFeatureOrigins(
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean geode = GeodeRules.intercept(config(), level, pos);
        if (geode != null) {
            cir.setReturnValue(geode);
            return;
        }
        Boolean orePile = OrePileRules.intercept(feature(), config(), level, generator, random, pos);
        if (orePile != null) {
            cir.setReturnValue(orePile);
            return;
        }
        if (BuildingLikeFeatures.isBuildingLike(feature())) {
            if (OakTrackCorridor.blocksBuildingOrigin(level, pos)
                    || RegionBoundaryNoStructureZone.blocksBuildingOrigin(level, pos)) {
                cir.setReturnValue(false);
            }
            return;
        }
        int dz = Math.abs(pos.getZ() - OakTrackCorridor.CORRIDOR_Z);
        if (dz > OakTrackCorridor.VAULT_RADIUS) {
            return;
        }
        if (!TreeLikeFeatures.isTreeLike(feature())) {
            return;
        }
        if (OakTrackCorridor.blocksTreeLikeOrigin(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
