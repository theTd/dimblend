package dimblend.mirage;

import com.mojang.logging.LogUtils;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.OakTrackCorridor;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;

/**
 * In-memory mirage generation: runs one band's real {@link ChunkGenerator} at coordinates that
 * belong to the other band, without touching the real {@code ServerChunkCache} or the disk.
 *
 * <p>Pipeline per request (all on the caller's worker thread):</p>
 * <ol>
 *   <li>allocate {@code ProtoChunk}s only for chunks within Chebyshev 2 of a target (the biome
 *       ring noise needs);</li>
 *   <li>{@code createBiomes} for that sparse halo (noise needs 3×3 biome data);</li>
 *   <li>{@code fillFromNoise → buildSurface → applyCarvers} only for chunks within Chebyshev 1 of
 *       a target, and prime the four FEATURES heightmaps;</li>
 *   <li>{@code applyBiomeDecoration} (no structures — mirage chunks carry no structure starts)
 *       for every target, then {@link OakTrackCorridor#placeForMirage} so the railway continues
 *       seamlessly through the seam;</li>
 *   <li>encode every target with {@link MirageSectionCodec}, quart biomes included.</li>
 * </ol>
 *
 * <p>Neighbors share the halo: a 6×7 request computes 8×9 noise/surface passes instead of
 * 42×9. A thin outer cap (asked for while walking along Z) allocates only its own ring plus the
 * neighbour rings instead of the whole bounding rectangle, which is 5-10× less work. Decoration is
 * the only per-target pass, and all targets are decorated before any is encoded so cross-chunk
 * feature writes (leaves, overhangs) land in both chunks.</p>
 *
 * <p>Never called on the server thread; never calls {@code RegionBoundaryWall},
 * {@code StationDepot}, {@code createStructures}, {@code spawnOriginalMobs} or the real chunk
 * source.</p>
 */
public final class MirageChunkGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 1 (target ring for decoration) + 1 (biome ring for noise). */
    private static final int BIOME_HALO = 2;
    private static final int NOISE_HALO = 1;

    private MirageChunkGenerator() {
    }

    /**
     * Generate and encode every requested chunk.
     *
     * @param targets all keys must share {@code slabX} and {@code side}; the caller (manager)
     *                passes only chunks missing from its cache
     * @return one payload per target, in target iteration order; empty when interrupted or the
     *         request is invalid
     */
    public static List<MirageChunkPayload> generate(
            ServerLevel level, RotatingChunkGenerator rotating, Collection<MirageChunkKey> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        int slabX = -1;
        byte side = 0;
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        Map<Long, MirageChunkKey> targetByKey = new LinkedHashMap<>();
        for (MirageChunkKey key : targets) {
            if (slabX == -1) {
                slabX = key.slabX();
                side = key.side();
            } else if (slabX != key.slabX() || side != key.side()) {
                throw new IllegalArgumentException("one mirage batch per (slabX, side)");
            }
            targetByKey.put(ChunkPos.asLong(key.chunkX(), key.chunkZ()), key);
            minChunkX = Math.min(minChunkX, key.chunkX());
            maxChunkX = Math.max(maxChunkX, key.chunkX());
            minChunkZ = Math.min(minChunkZ, key.chunkZ());
            maxChunkZ = Math.max(maxChunkZ, key.chunkZ());
        }
        int bandSize = rotating.bandSize();
        if (Math.floorMod(slabX, bandSize) != 0) {
            throw new IllegalArgumentException("mirage slabX is not a seam: " + slabX);
        }
        BandLayout layout = rotating.layout();
        int seamRegion = Math.floorDiv(slabX, bandSize);
        int delegateIndex = side == MirageChunkKey.WEST_BAND_INTO_EAST
                ? layout.delegateIndex(seamRegion - 1)
                : layout.delegateIndex(seamRegion);
        int otherIndex = side == MirageChunkKey.WEST_BAND_INTO_EAST
                ? layout.delegateIndex(seamRegion)
                : layout.delegateIndex(seamRegion - 1);
        ChunkGenerator delegate = rotating.delegates().get(delegateIndex);
        String laneName = BandLayout.laneName(delegate);
        String otherLaneName = BandLayout.laneName(rotating.delegates().get(otherIndex));
        int maxDy = OakTrackCorridor.vaultMaxDy(delegate);
        RandomState randomState = rotating.delegateRandom(delegateIndex);
        LevelHeightAccessor height = heightAccessor(level, delegate);
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);

        Map<Long, ProtoChunk> chunks = new HashMap<>();
        try {
            // Sparse halo: only chunks within Chebyshev 2 of a target get a ProtoChunk (biome
            // sharing), only chunks within Chebyshev 1 get noise/surface (neighbour reads). An
            // incremental Z/distance shift re-requests a thin outer cap, and the full bounding-box
            // rectangle for that used to allocate and shape 5-10x more chunks than it needed.
            int spanX = maxChunkX - minChunkX + 1;
            int spanZ = maxChunkZ - minChunkZ + 1;
            boolean[] isTarget = new boolean[spanX * spanZ];
            for (MirageChunkKey key : targetByKey.values()) {
                isTarget[(key.chunkX() - minChunkX) * spanZ + (key.chunkZ() - minChunkZ)] = true;
            }
            for (int chunkX = minChunkX - BIOME_HALO; chunkX <= maxChunkX + BIOME_HALO; chunkX++) {
                for (int chunkZ = minChunkZ - BIOME_HALO; chunkZ <= maxChunkZ + BIOME_HALO; chunkZ++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return List.of();
                    }
                    if (!nearTarget(isTarget, spanX, spanZ, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                            chunkX, chunkZ, BIOME_HALO)) {
                        continue;
                    }
                    ProtoChunk chunk = new ProtoChunk(
                            new ChunkPos(chunkX, chunkZ),
                            UpgradeData.EMPTY,
                            height,
                            biomes,
                            null);
                    chunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
                }
            }
            if (!fillBiomes(level, delegate, randomState, chunks)) {
                return List.of();
            }
            if (!shapeTerrain(level, delegate, randomState, chunks, isTarget, spanX, spanZ,
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ, NOISE_HALO)) {
                return List.of();
            }
            for (MirageChunkKey key : targetByKey.values()) {
                if (Thread.currentThread().isInterrupted()) {
                    return List.of();
                }
                ProtoChunk chunk = chunks.get(ChunkPos.asLong(key.chunkX(), key.chunkZ()));
                if (chunk == null) {
                    continue;
                }
                MirageWorldGenRegion region = new MirageWorldGenRegion(
                        level, new ChunkPos(key.chunkX(), key.chunkZ()), chunks);
                try {
                    delegate.applyBiomeDecoration(region, chunk,
                            level.structureManager().forWorldGenRegion(region));
                    chunk.setPersistedStatus(ChunkStatus.FEATURES);
                } catch (RuntimeException failure) {
                    LOGGER.warn("mirage decoration failed at {} in {}: {}",
                            chunk.getPos(), laneName, failure.toString());
                } finally {
                    try {
                        OakTrackCorridor.placeForMirage(region, chunk, delegate, maxDy);
                    } catch (RuntimeException failure) {
                        LOGGER.warn("mirage corridor carve failed at {} in {}: {}",
                                chunk.getPos(), laneName, failure.toString());
                    }
                }
            }
            List<MirageChunkPayload> out = new ArrayList<>(targetByKey.size());
            for (MirageChunkKey key : targetByKey.values()) {
                ProtoChunk chunk = chunks.get(ChunkPos.asLong(key.chunkX(), key.chunkZ()));
                if (chunk == null) {
                    continue;
                }
                MirageSectionCodec.Encoded encoded = MirageSectionCodec.encodeChunk(chunk, biomes);
                out.add(new MirageChunkPayload(
                        key,
                        delegateIndex,
                        laneName,
                        otherLaneName,
                        chunk.getMinSection(),
                        chunk.getSectionsCount(),
                        encoded.sections(),
                        encoded.heightmap(),
                        encoded.emissive()));
            }
            return out;
        } finally {
            chunks.clear();
        }
    }

    /**
     * True when {@code (chunkX, chunkZ)} is within Chebyshev distance {@code radius} of at least
     * one requested target. {@code isTarget} is the target set over
     * {@code [minChunkX..maxChunkX] x [minChunkZ..maxChunkZ]}.
     */
    private static boolean nearTarget(
            boolean[] isTarget, int spanX, int spanZ, int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ, int chunkX, int chunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            int x = chunkX + dx;
            if (x < minChunkX || x > maxChunkX) {
                continue;
            }
            for (int dz = -radius; dz <= radius; dz++) {
                int z = chunkZ + dz;
                if (z < minChunkZ || z > maxChunkZ) {
                    continue;
                }
                if (isTarget[(x - minChunkX) * spanZ + (z - minChunkZ)]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code createBiomes} for the whole halo; {@code false} when the thread was interrupted. */
    private static boolean fillBiomes(
            ServerLevel level, ChunkGenerator delegate, RandomState randomState,
            Map<Long, ProtoChunk> chunks) {
        for (ProtoChunk chunk : chunks.values()) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            try {
                delegate.createBiomes(randomState, Blender.empty(), level.structureManager(), chunk).join();
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
            } catch (RuntimeException failure) {
                LOGGER.warn("mirage biomes failed at {}: {}", chunk.getPos(), failure.toString());
            }
        }
        return true;
    }

    /** NOISE → SURFACE → CARVERS for the noise ring; {@code false} when interrupted. */
    private static boolean shapeTerrain(
            ServerLevel level, ChunkGenerator delegate, RandomState randomState,
            Map<Long, ProtoChunk> chunks, boolean[] isTarget, int spanX, int spanZ,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int radius) {
        EnumSet<Heightmap.Types> featuresHeightmaps = EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE);
        for (ProtoChunk chunk : chunks.values()) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            ChunkPos pos = chunk.getPos();
            if (!nearTarget(isTarget, spanX, spanZ, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    pos.x, pos.z, radius)) {
                continue;
            }
            MirageWorldGenRegion region = new MirageWorldGenRegion(level, pos, chunks);
            try {
                delegate.fillFromNoise(Blender.empty(), randomState,
                                level.structureManager().forWorldGenRegion(region), chunk)
                        .join();
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                delegate.buildSurface(region,
                        level.structureManager().forWorldGenRegion(region), randomState, chunk);
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                // Skip NoiseBasedChunkGenerator.applyCarvers: vanilla carvers probe a 17x17
                // chunk halo via getChunk(x, z) and NPE on chunkaccess.carverBiome outside our
                // 3x3 mirage region. Mirage chunks only need noise + surface + decoration.
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                Heightmap.primeHeightmaps(chunk, featuresHeightmaps);
            } catch (RuntimeException failure) {
                LOGGER.warn("mirage terrain failed at {}: {}", pos, failure.toString());
            }
        }
        return true;
    }

    /**
     * Chunk height accessor: the level's own unless a delegate claims a taller/shorter range
     * (real chunks are always built against the level accessor; this only guards a future
     * delegate that exceeds it).
     */
    private static LevelHeightAccessor heightAccessor(ServerLevel level, ChunkGenerator delegate) {
        int minY = Math.min(level.getMinBuildHeight(), delegate.getMinY());
        int maxY = Math.max(level.getMaxBuildHeight(), delegate.getMinY() + delegate.getGenDepth());
        if (minY == level.getMinBuildHeight() && maxY == level.getMaxBuildHeight()) {
            return level;
        }
        return LevelHeightAccessor.create(minY, maxY - minY);
    }
}
