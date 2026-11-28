package dimblend.mirage;

import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * {@link WorldGenRegion} over a private 3×3 map of {@link ProtoChunk}s, for mirage generation.
 * Zero mixins: the vanilla constructor accepts any {@link StaticCache2D} of
 * {@link GenerationChunkHolder}s, and the three methods that would otherwise throw or reach the
 * real chunk cache are overridden.
 *
 * <p>Why all three overrides are mandatory (vanilla 1.21.1):</p>
 * <ul>
 *   <li>{@link WorldGenRegion#getChunk(int, int, ChunkStatus, boolean)} throws a
 *       {@code ReportedException} for anything outside the step's dependency radius and for
 *       statuses past {@link ChunkStatus#FEATURES}. Decoration probes neighbors, so it must
 *       answer {@code null} instead.</li>
 *   <li>{@link WorldGenRegion#hasChunk(int, int)} derives its radius from
 *       {@code generatingStep.directDependencies().size()}, which is 2 for FEATURES — the 5×5
 *       NPE trap: callers then read a chunk that was never put in our 3×3 map.</li>
 *   <li>{@link WorldGenRegion#setBlock} consults {@code level.onBlockStateChange} and the real
 *       light engine; mirage writes must go straight into the mapped {@code ProtoChunk}s.</li>
 *   <li>{@link WorldGenRegion#getBlockState} / {@link WorldGenRegion#getFluidState} call
 *       {@code getChunk(...)}, which throws a {@code ReportedException} for a chunk outside our
 *       3×3 map — the live capture showed {@code CrashReport} stack building costing ~50 ms per
 *       failing decoration, 20+ times per seam. Reading air / empty fluid instead keeps a feature
 *       that probes one chunk too far from turning into a crash report.</li>
 *   <li>{@link WorldGenRegion#getBlockEntity} reaches into chunk NBT and the level's registries
 *       ("Tried to access a block entity before it was created"); mirage chunks never render
 *       block entities, so no feature may see one.</li>
 * </ul>
 *
 * <p>Only {@code FEATURES} semantics are needed: the same region shape is used for
 * {@code buildSurface} / {@code applyCarvers} / {@code applyBiomeDecoration} (write radius 1,
 * read radius 1), matching the vanilla FEATURES step's {@code blockStateWriteRadius}.
 */
public final class MirageWorldGenRegion extends WorldGenRegion {
    private static final ChunkStep FEATURES_STEP =
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);

    private final ChunkPos center;
    private final Map<Long, ProtoChunk> chunks;

    /**
     * @param chunks every chunk of the 3×3 neighborhood around {@code center}, keyed by
     *               {@link ChunkPos#asLong(int, int)}; the center chunk must be present
     */
    public MirageWorldGenRegion(ServerLevel level, ChunkPos center, Map<Long, ProtoChunk> chunks) {
        super(level,
                cacheFor(center, chunks),
                FEATURES_STEP,
                centerChunk(center, chunks));
        this.center = center;
        this.chunks = chunks;
    }

    private static ProtoChunk centerChunk(ChunkPos center, Map<Long, ProtoChunk> chunks) {
        ProtoChunk chunk = chunks.get(ChunkPos.asLong(center.x, center.z));
        if (chunk == null) {
            throw new IllegalArgumentException("mirage center chunk missing: " + center);
        }
        return chunk;
    }

    private static StaticCache2D<GenerationChunkHolder> cacheFor(
            ChunkPos center, Map<Long, ProtoChunk> chunks) {
        return StaticCache2D.create(center.x, center.z, 1,
                (x, z) -> new MirageHolder(new ChunkPos(x, z),
                        chunks.get(ChunkPos.asLong(x, z))));
    }

    @Nullable
    private ProtoChunk chunkAt(int chunkX, int chunkZ) {
        if (Math.abs(chunkX - this.center.x) > 1 || Math.abs(chunkZ - this.center.z) > 1) {
            return null;
        }
        return this.chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    @Override
    @Nullable
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean nonnull) {
        ProtoChunk chunk = this.chunkAt(chunkX, chunkZ);
        if (chunk == null || !status.isOrBefore(ChunkStatus.FEATURES)) {
            return null;
        }
        return chunk;
    }

    /** Exact 3×3 answer: never claim a chunk merely because the FEATURES step could hold it. */
    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return this.chunkAt(chunkX, chunkZ) != null;
    }

    /**
     * Air outside the 3×3 map instead of vanilla's {@code ReportedException}: decoration probes
     * neighbours through this, and a probe one chunk too far must not crash a whole batch.
     */
    @Override
    public BlockState getBlockState(BlockPos pos) {
        ProtoChunk chunk = this.chunkAt(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        ProtoChunk chunk = this.chunkAt(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
    }

    /**
     * Mirage chunks carry no live block entities and are never rendered with them, so answering
     * {@code null} avoids vanilla's NBT reconstruction and its "Tried to access a block entity
     * before it was created" report for spawners and chests a feature just placed.
     */
    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        return Math.abs(chunkX - this.center.x) <= 1 && Math.abs(chunkZ - this.center.z) <= 1;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        return this.setBlock(pos, state, flags, 512);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (!this.ensureCanWrite(pos)) {
            return false;
        }
        ProtoChunk chunk = this.chunkAt(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk == null) {
            return false;
        }
        chunk.setBlockState(pos, state, false);
        return true;
    }

    /** Holder adapter: only the two abstract accessors plus the read methods are used. */
    private static final class MirageHolder extends GenerationChunkHolder {
        @Nullable
        private final ProtoChunk chunk;

        MirageHolder(ChunkPos pos, @Nullable ProtoChunk chunk) {
            super(pos);
            this.chunk = chunk;
        }

        @Override
        public int getTicketLevel() {
            return 33;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }

        @Override
        @Nullable
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return this.chunk;
        }

        @Override
        @Nullable
        public ChunkAccess getChunkIfPresent(ChunkStatus status) {
            return this.chunk;
        }

        @Override
        @Nullable
        public ChunkAccess getLatestChunk() {
            return this.chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return this.chunk == null ? null : this.chunk.getPersistedStatus();
        }
    }
}
