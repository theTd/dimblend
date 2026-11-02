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
 * the "keep the original" branch turns any mineral pile that is not
 * coal / copper / iron / gold / zinc into emerald ore instead (deepslate ground
 * gets {@code deepslate_emerald_ore}), so every ore type takes part in the roll.
 * Gate rolls use a seed derived from world seed + origin + targets so the
 * worldgen {@link RandomSource} that shapes the blob is not consumed.
 */
public final class OrePileRules {
    private static final long GATE_SALT = 0x4F524550494C45L;
    private static final TagKey<Block> ZINC_ORES =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores/zinc"));
    private static final TagKey<Block> DEEPSLATE_GROUND =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores_in_ground/deepslate"));

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
        RandomSource gate = gateRandom(level.getSeed(), pos, ore);
        return switch (decide(surface, ore, gate)) {
            case CANCEL -> Boolean.FALSE;
            case ORIGINAL -> null;
            case EMERALD -> placeEmerald(feature, ore, level, generator, random, pos);
            case ANDESITE -> placeAndesite(feature, ore, level, generator, random, pos);
        };
    }

    /** 门控掷骰的结果，单独成枚举以便在没有 Level 的单测里断言分支走向。 */
    enum Outcome {
        /** 整堆取消。 */
        CANCEL,
        /** 原样交给原版生成。 */
        ORIGINAL,
        /** 整堆换成安山岩。 */
        ANDESITE,
        /** 整堆换成绿宝石（仅地表 lane 的非五种矿）。 */
        EMERALD
    }

    /**
     * 两次门控掷骰的纯决策，不接触 Level：第一次 50% 取消；存活堆第二次 50% 决定
     * 原样生成（地表 lane 的非五种矿在这里改走绿宝石分支）还是整堆换安山岩。
     */
    static Outcome decide(boolean surface, OreConfiguration ore, RandomSource gate) {
        if (gate.nextBoolean()) {
            return Outcome.CANCEL;
        }
        if (!gate.nextBoolean()) {
            return surface && !isSurfaceAllowed(ore) ? Outcome.EMERALD : Outcome.ORIGINAL;
        }
        return Outcome.ANDESITE;
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

    /** 逐个 target 选绿宝石变体：深板岩地层用深板岩绿宝石矿，其余用普通绿宝石矿。 */
    static OreConfiguration toEmerald(OreConfiguration ore) {
        List<OreConfiguration.TargetBlockState> targets = new ArrayList<>(ore.targetStates.size());
        for (OreConfiguration.TargetBlockState target : ore.targetStates) {
            targets.add(OreConfiguration.target(target.target, emeraldFor(target.state)));
        }
        return new OreConfiguration(targets, ore.size, ore.discardChanceOnAirExposure);
    }

    static BlockState emeraldFor(BlockState original) {
        boolean deepslate = original.is(DEEPSLATE_GROUND) || isDeepslateId(original);
        return (deepslate ? Blocks.DEEPSLATE_EMERALD_ORE : Blocks.EMERALD_ORE).defaultBlockState();
    }

    private static boolean isDeepslateId(BlockState original) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(original.getBlock());
        return id != null && id.getPath().startsWith("deepslate_");
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean placeEmerald(
            Feature<?> feature,
            OreConfiguration ore,
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            BlockPos pos
    ) {
        return ((Feature) feature).place(toEmerald(ore), level, generator, random, pos);
    }
}
