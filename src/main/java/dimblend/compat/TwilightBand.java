package dimblend.compat;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.YShiftedNoiseChunkGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Runtime predicates for the Twilight Forest latitude inside {@code dimblend:rotating}.
 * Never treats the whole rotating dimension as Twilight — nether/overworld bands stay vanilla.
 */
public final class TwilightBand {
    public static final int DEFAULT_Y_OFFSET = 64;

    private TwilightBand() {
    }

    public static boolean isRotating(LevelAccessor level) {
        return level instanceof Level world && world.dimension() == DimBlendRegistries.ROTATING_LEVEL;
    }

    public static boolean isTwilightBiome(Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(key -> "twilightforest".equals(key.location().getNamespace()))
                .orElse(false);
    }

    public static boolean isTwilightPos(LevelAccessor level, BlockPos pos) {
        return isRotating(level) && isTwilightBiome(level.getBiome(pos));
    }

    public static boolean isTwilightColumn(LevelAccessor level, int blockX) {
        if (!(level instanceof Level world) || world.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        ChunkGenerator generator = chunkGenerator(world);
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        return BandLayout.isTwilight(rotating.delegateForBlockX(blockX));
    }

    public static int yOffset(ChunkGenerator generator) {
        return generator instanceof YShiftedNoiseChunkGenerator shifted ? shifted.yOffset() : 0;
    }

    public static int sampleY(LevelAccessor level) {
        ChunkGenerator generator = chunkGenerator(level);
        if (generator instanceof RotatingChunkGenerator rotating) {
            for (ChunkGenerator delegate : rotating.delegates()) {
                if (BandLayout.isTwilight(delegate)) {
                    return delegate.getSeaLevel();
                }
            }
        }
        if (generator instanceof YShiftedNoiseChunkGenerator shifted) {
            return shifted.getSeaLevel();
        }
        return DEFAULT_Y_OFFSET;
    }

    @Nullable
    public static ServerLevel rotating(MinecraftServer server) {
        return server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
    }

    public static BlockPos landingInTwilight(ServerLevel rotating, int fromX, int fromZ) {
        ChunkGenerator generator = rotating.getChunkSource().getGenerator();
        int x = fromX;
        if (generator instanceof RotatingChunkGenerator rotatingGen) {
            Integer region = nearestTwilightRegion(rotatingGen, fromX);
            if (region != null) {
                x = region * rotatingGen.bandSize() + rotatingGen.bandSize() / 2;
            }
        }
        return rotating.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, fromZ));
    }

    @Nullable
    public static Integer nearestTwilightRegion(RotatingChunkGenerator rotating, int fromX) {
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return null;
        }
        int from = BandLayout.regionOfBlockX(fromX, rotating.bandSize());
        for (int radius = 0; radius <= 1024; radius++) {
            for (int candidate : new int[]{from + radius, from - radius}) {
                ChunkGenerator delegate = layout.delegate(candidate);
                if (BandLayout.isTwilight(delegate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Nullable
    private static ChunkGenerator chunkGenerator(LevelAccessor level) {
        if (level instanceof ServerLevel server) {
            return server.getChunkSource().getGenerator();
        }
        return null;
    }
}
