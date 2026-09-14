package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.neoforged.neoforge.common.Tags;

/**
 * Rotating-dimension ore-blob policy, applied at {@code ConfiguredFeature#place}
 * so every vanilla and modded {@link OreConfiguration} pile is gated the same way.
 * Dirt / granite / tuff blobs are ignored because they are not mineral ores.
 *
 * <p>Global: keep half the piles (independent of blob {@code size}); of the
 * survivors, replace the whole pile with andesite half the time. Surface lane:
 * cancel any mineral pile that is not coal / copper / iron / gold / zinc.
 * Gate rolls use a seed derived from world seed + origin + targets so the
 * worldgen {@link RandomSource} that shapes the blob is not consumed.
 */
public final class OrePileRules {
    private static final long GATE_SALT = 0x4F524550494C45L;
    private static final TagKey<Block> ZINC_ORES =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores/zinc"));

    private OrePileRules() {
    }

    /**
     * {@code null} = run vanilla placement. Non-null is the value
     * {@code ConfiguredFeature#place} should return instead.
     */
    @Nullable
    public static Boolean intercept(
            Feature<?> feature,
            FeatureConfiguration config,
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            BlockPos pos
    ) {
        if (!(config instanceof OreConfiguration ore) || !isMineralPile(ore)) {
            return null;
        }
        if (level.getLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return null;
        }
        if (!(level.getLevel().getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating)) {
            return null;
        }
        boolean surface = BandLayout.isSurfaceOverworld(rotating.delegateForBlockX(pos.getX()));
        if (surface && !isSurfaceAllowed(ore)) {
            return Boolean.FALSE;
        }
        RandomSource gate = gateRandom(level.getSeed(), pos, ore);
        if (gate.nextBoolean()) {
            return Boolean.FALSE;
        }
        if (!gate.nextBoolean()) {
            return null;
        }
        return placeAndesite(feature, ore, level, generator, random, pos);
    }

    static boolean isMineralPile(OreConfiguration ore) {
        for (OreConfiguration.TargetBlockState target : ore.targetStates) {
            if (isMineral(target.state)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSurfaceAllowed(OreConfiguration ore) {
        boolean sawMineral = false;
        for (OreConfiguration.TargetBlockState target : ore.targetStates) {
            if (!isMineral(target.state)) {
                continue;
            }
            sawMineral = true;
            if (!isSurfaceMineral(target.state)) {
                return false;
            }
        }
        return sawMineral;
    }

    private static boolean isMineral(BlockState state) {
        if (state.is(Tags.Blocks.ORES)) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("_ore") || path.endsWith("ore") || path.contains("debris");
    }

    private static boolean isSurfaceMineral(BlockState state) {
        return state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES)
                || state.is(ZINC_ORES)
                || isZincPath(state);
    }

    private static boolean isZincPath(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("zinc") && (path.contains("ore") || state.is(Tags.Blocks.ORES));
    }

    private static RandomSource gateRandom(long worldSeed, BlockPos pos, OreConfiguration ore) {
        long hash = worldSeed ^ GATE_SALT;
        hash ^= BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        hash = Long.rotateLeft(hash, 17) ^ Integer.toUnsignedLong(ore.size * 0x9E3779B9);
        for (OreConfiguration.TargetBlockState target : ore.targetStates) {
            hash = Long.rotateLeft(hash, 13) ^ BuiltInRegistries.BLOCK.getId(target.state.getBlock());
        }
        return RandomSource.create(hash);
    }

    private static OreConfiguration toAndesite(OreConfiguration ore) {
        BlockState andesite = Blocks.ANDESITE.defaultBlockState();
        List<OreConfiguration.TargetBlockState> targets = new ArrayList<>(ore.targetStates.size());
        for (OreConfiguration.TargetBlockState target : ore.targetStates) {
            targets.add(OreConfiguration.target(target.target, andesite));
        }
        return new OreConfiguration(targets, ore.size, ore.discardChanceOnAirExposure);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean placeAndesite(
            Feature<?> feature,
            OreConfiguration ore,
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            BlockPos pos
    ) {
        return ((Feature) feature).place(toAndesite(ore), level, generator, random, pos);
    }
}
