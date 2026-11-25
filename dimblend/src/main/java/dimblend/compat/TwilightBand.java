package dimblend.compat;

import dev.ryanhcode.sable.companion.SableCompanion;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.YShiftedNoiseChunkGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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

    /** World Y of TF's aurora sheet in the real twilight_forest dimension. */
    public static final int TF_AURORA_Y = 256;

    /** {@code TwilightForestRenderInfo.isFoggyAt} treats Y above this as out of the dark-forest canopy. */
    public static final int TF_FOGGY_AT_CEILING_Y = 20;

    /**
     * Highest block Y still treated as "inside a twilight cave" by {@link #weatherSampleY}.
     *
     * <p>The twilight delegate's {@code biome_grid.json} switches every column to
     * {@code twilightforest:underground} below quart Y {@code -3}; after the {@code +64} lift that
     * is block Y {@code 60}. 56 leaves a margin so a player standing on a cave floor just under the
     * grid seam still samples the cave biome instead of the surface one.
     */
    public static final int UNDERGROUND_SAMPLE_MAX_Y = 56;

    /**
     * Absolute Y for a TF-authored client effect after the rotating-dimension
     * terrain lift. No-op when {@code rotating} is false.
     */
    public static double liftedEffectY(double tfWorldY, boolean rotating) {
        return rotating ? tfWorldY + DEFAULT_Y_OFFSET : tfWorldY;
    }

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

    /** True for {@code twilightforest:underground}, the safe cave biome under every TF surface band. */
    public static boolean isTwilightUnderground(Holder<Biome> biome) {
        return isTwilightBiome(biome)
                && biome.unwrapKey()
                        .map(key -> "underground".equals(key.location().getPath()))
                        .orElse(false);
    }

    /**
     * Y at which Twilight Forest's locked-biome weather ({@code TFWeatherRenderer}) must sample
     * biomes and block light inside {@code dimblend:rotating}.
     *
     * <p>TF hardcodes {@code y = 0}. After the {@code +64} lift every {@code (x, 0, z)} column
     * answers {@code twilightforest:underground} — the biome with no restriction entry — so
     * {@code isNearLockedBiome} was false for every position and no curse curtain was ever
     * drawn. Sampling surface bands at the camera's own block Y (never below
     * {@link #DEFAULT_Y_OFFSET}) puts both the biome lookup and the acid-rain
     * {@code LevelRenderer#getLightColor} sample in the air the camera is in; inside a
     * {@code twilightforest:underground} cave the player's own Y is used instead, so a cave under
     * a twilight band stays as calm as the server {@code Enforcement} path treats it.
     *
     * @param viewEntity the entity whose position decides the "inside a TF cave" case (may be null)
     * @param cameraY    camera Y in the same column; the surface sample follows it
     */
    public static int weatherSampleY(Level level, @Nullable Entity viewEntity, double cameraY) {
        if (viewEntity != null
                && viewEntity.getBlockY() < UNDERGROUND_SAMPLE_MAX_Y
                && isTwilightUnderground(level.getBiome(viewEntity.blockPosition()))) {
            return viewEntity.getBlockY();
        }
        return Math.max(Mth.floor(cameraY), DEFAULT_Y_OFFSET);
    }

    /**
     * True while Sable carries the player: standing, walking or jumping on a structure
     * ({@code trackingSubLevel}), riding a seat inside one ({@code vehicleSubLevel}), or lying in
     * a bed whose position lives in a Sable plot.
     *
     * <p>{@code SableCompanion.INSTANCE} is a no-op stub when Sable is not installed, so this call
     * is safe unconditionally (the same way {@code WarpGateBlock#mayPass} uses it).
     */
    public static boolean isOnSable(Player player) {
        if (SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player) != null) {
            return true;
        }
        return player.getSleepingPos()
                .map(pos -> SableCompanion.INSTANCE.getContaining(player.level(), pos) != null)
                .orElse(false);
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
