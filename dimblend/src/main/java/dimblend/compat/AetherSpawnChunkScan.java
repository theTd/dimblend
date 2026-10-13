package dimblend.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Bounds Aether's spawn-rule block-entity scans ({@code Swet.inRadiusOfBanner},
 * {@code AechorPlant.inRadiusOfFlowers}) for {@code SwetSpawnBannerScanMixin} and
 * {@code AechorPlantSpawnFlowerScanMixin}.
 *
 * <p>Aether 1.21.1-1.5.10 passes the same number, 40, both as the block distance a banner or
 * flower must be within and as the chunk radius it scans: {@code ChunkPos.rangeClosed(center, 40)}
 * is 81 x 81 = 6561 chunks per spawn check, where only the 7 x 7 around the spawn can hold a block
 * within 40 blocks. Each chunk is fetched with {@code getChunk(x, z, FULL, false)}, which does not
 * create tickets but, for a chunk that already has a full-level ticket and is still loading or
 * generating, schedules the work and parks the server thread until it finishes. A train running
 * into new terrain always has such chunks in range, so every Swet spawn check waited on the chunk
 * frontier (0.2-0.8 s server stalls about every 2 s in the 2026-10-14 slow-tick capture).
 *
 * <p>The scan is cut to the chunks the block radius can reach and reads only chunks that are
 * already fully loaded. Behavioural differences: a banner or flower in a chunk that has not
 * finished loading no longer blocks a spawn; and a check run off the server thread (none in
 * vanilla or Aether: natural spawning ticks on it) sees no chunks instead of waiting for them.
 */
public final class AetherSpawnChunkScan {
    private AetherSpawnChunkScan() {
    }

    /**
     * Chunk radius around the spawn position's chunk that holds every block within
     * {@code blockRadius} blocks of it, whatever its position inside its chunk.
     */
    public static int chunkRadius(int blockRadius) {
        return (Math.max(0, blockRadius) + 15) >> 4;
    }

    /**
     * Chunk radius to scan: Aether's own, but no larger than {@code blockRadius} can reach. Taking
     * the minimum keeps a future Aether that already passes a chunk radius unchanged.
     *
     * @param scannedChunks chunk radius Aether passes to {@code ChunkPos.rangeClosed}
     * @param blockRadius   largest block distance the method checks
     */
    public static int scanRadius(int scannedChunks, int blockRadius) {
        return Math.min(scannedChunks, chunkRadius(blockRadius));
    }

    /**
     * {@code level.getChunk(x, z, status, require)} without waiting: on a server level a
     * non-required full chunk is taken only if it is already loaded ({@code getChunkNow}, which
     * also answers null off the server thread); anything else (world-gen regions, other statuses,
     * required chunks) is passed through unchanged.
     */
    @Nullable
    public static ChunkAccess chunkIfLoaded(LevelAccessor level, int x, int z, ChunkStatus status, boolean require) {
        if (!require && status == ChunkStatus.FULL && level instanceof ServerLevel serverLevel) {
            return serverLevel.getChunkSource().getChunkNow(x, z);
        }
        return level.getChunk(x, z, status, require);
    }
}
