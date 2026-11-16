package dimblend.worldgen;

import it.unimi.dsi.fastutil.shorts.ShortList;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;

/**
 * Post-processing fluid predicates for freshly generated chunks.
 *
 * <p>A source water block whose five spread neighbours (down and the four horizontals) are all
 * themselves source water or watertight has no fluid tick worth running: {@code FlowingFluid}
 * would look for a spread target, find none, and reschedule. Two complementary filters use that
 * exact invariant:
 *
 * <ol>
 *   <li><b>Worker-thread proto-mark pruning (PPG-2)</b>: at {@code ChunkStatus.SPAWN} — strictly
 *       after {@code LIGHT -> INITIALIZE_LIGHT(radius=1) -> FEATURES(radius=1)}, so all 3x3
 *       chunks' carvers, structures, biome features, corridors, walls, and depots have finished
 *       modifying {@code chunk} — interior ({@code lx, lz in 1..14}) enclosed source-water marks
 *       are compacted out of {@code ProtoChunk#postProcessing} off-thread before serialization
 *       or main-thread iteration.</li>
 *   <li><b>Main-thread promotion guard (PPG-1)</b>: inside {@code LevelChunk#postProcessGeneration},
 *       remaining marks (including chunk-border columns {@code lx/lz in {0, 15}} and vanilla
 *       dimension chunks) skip {@code FluidState#tick} when all five spread neighbours across
 *       loaded {@code FULL} chunks are source water or watertight.</li>
 * </ol>
 *
 * <p>Every predicate is conservative: anything uncertain (chunk neighbour not loaded, unknown
 * state, the {@code finite_water} mod's custom water rules) returns {@code false} so vanilla
 * runs unchanged.
 */
public final class PostProcessFluidFilter {
    /** {@code finite_water} replaces water-source rules; when present, never filter. */
    private static final String FINITE_WATER_MOD = "finite_water";

    private static volatile Boolean finiteWaterLoaded;

    @FunctionalInterface
    interface BlockLookup {
        @Nullable
        BlockState get(int x, int y, int z);
    }

    private PostProcessFluidFilter() {
    }

    /**
     * Whether the {@code finite_water} mod is loaded. Cached once a {@link ModList} exists;
     * outside a running game (unit tests) it reports {@code false} without caching so a later
     * loaded environment is still detected.
     */
    public static boolean isFiniteWaterLoaded() {
        Boolean cached = finiteWaterLoaded;
        if (cached != null) {
            return cached;
        }
        ModList modList = ModList.get();
        if (modList == null) {
            return false;
        }
        boolean loaded = modList.isLoaded(FINITE_WATER_MOD);
        finiteWaterLoaded = loaded;
        return loaded;
    }

    /**
     * True when a fluid tick at a cell facing {@code neighborState} provably cannot spread.
     *
     * <ul>
     *   <li>Source water: occupied by still water that will not be replaced.</li>
     *   <li>Motion-blocking solid without waterlogging: water cannot enter it. Flowing water is
     *       deliberately <b>not</b> blocking — a source next to flowing water must still tick to
     *       convert it.</li>
     * </ul>
     */
    public static boolean isWaterSpreadBlockedNeighbor(@Nullable BlockState neighborState) {
        if (neighborState == null) {
            return false;
        }
        FluidState neighborFluid = neighborState.getFluidState();
        if (neighborFluid.getType() == Fluids.WATER && neighborFluid.isSource()) {
            return true;
        }
        if (neighborFluid.isEmpty()
                && neighborState.blocksMotion()
                && !(neighborState.getBlock() instanceof LiquidBlockContainer)) {
            return true;
        }
        return false;
    }

    /**
     * Read one block out of a chunk without touching the level. Air-only sections short-circuit
     * to air instead of walking their palette.
     */
    @Nullable
    private static BlockState stateInChunk(ChunkAccess chunk, int x, int y, int z) {
        int sectionIndex = chunk.getSectionIndex(y);
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return null;
        }
        LevelChunkSection section = sections[sectionIndex];
        return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
    }

    /**
     * True when the scheduled {@code FluidState.tick} at {@code pos} provably does nothing:
     * an enclosed source water block, i.e. itself {@link Blocks#WATER} and all five spread
     * neighbours source water or watertight within loaded chunks.
     */
    public static boolean canSkipPostProcessTick(LevelChunk self, Level level, BlockPos pos, FluidState fluidState) {
        if (isFiniteWaterLoaded()) {
            return false;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return canSkipPostProcessTickAt(
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                level.getMinBuildHeight(),
                fluidState,
                (bx, by, bz) -> {
                    int targetChunkX = bx >> 4;
                    int targetChunkZ = bz >> 4;
                    LevelChunk target = targetChunkX == chunkX && targetChunkZ == chunkZ
                            ? self
                            : level.getChunkSource().getChunkNow(targetChunkX, targetChunkZ);
                    return target == null ? null : stateInChunk(target, bx, by, bz);
                });
    }

    static boolean canSkipPostProcessTickAt(
            int x,
            int y,
            int z,
            int minBuildHeight,
            FluidState fluidState,
            BlockLookup blockAt
    ) {
        if (fluidState.getType() != Fluids.WATER || !fluidState.isSource()) {
            return false;
        }
        if (y <= minBuildHeight) {
            return false;
        }
        BlockState selfState = blockAt.get(x, y, z);
        if (selfState == null || !selfState.is(Blocks.WATER)) {
            return false;
        }
        return isWaterSpreadBlockedNeighbor(blockAt.get(x, y - 1, z))
                && isWaterSpreadBlockedNeighbor(blockAt.get(x - 1, y, z))
                && isWaterSpreadBlockedNeighbor(blockAt.get(x + 1, y, z))
                && isWaterSpreadBlockedNeighbor(blockAt.get(x, y, z - 1))
                && isWaterSpreadBlockedNeighbor(blockAt.get(x, y, z + 1));
    }

    /**
     * Prunes enclosed interior ({@code lx, lz in 1..14}) {@link Blocks#WATER} marks from
     * {@code chunk.getPostProcessing()} on the worldgen worker thread at {@code ChunkStatus.SPAWN}.
     *
     * <p>Running at {@code SPAWN} (after {@code LIGHT -> INITIALIZE_LIGHT(radius=1) ->
     * FEATURES(radius=1)}) guarantees that {@code CARVERS} (where {@code Blocks.WATER} is in
     * {@code #minecraft:overworld_carver_replaceables}) and all 3x3 chunks' {@code FEATURES} /
     * structures have already finished mutating {@code chunk}. Non-{@link Blocks#WATER} blocks
     * (kelp, waterlogged slabs, etc.) are never pruned because {@code postProcessGeneration} also
     * runs {@code Block.updateFromNeighbourShapes} on them.
     */
    public static void pruneEnclosedInteriorWaterMarks(ChunkAccess chunk) {
        if (isFiniteWaterLoaded()) {
            return;
        }
        prunePostProcessingLists(
                chunk.getPostProcessing(),
                chunk.getMinSection(),
                chunk.getMinBuildHeight(),
                (lx, y, lz) -> stateInChunk(chunk, lx, y, lz));
    }

    static void prunePostProcessingLists(
            ShortList[] lists,
            int minSectionY,
            int minBuildHeight,
            BlockLookup localBlockAt
    ) {
        if (lists == null) {
            return;
        }
        for (int sectionIndex = 0; sectionIndex < lists.length; sectionIndex++) {
            ShortList list = lists[sectionIndex];
            if (list == null || list.isEmpty()) {
                continue;
            }
            int sectionY = minSectionY + sectionIndex;
            int size = list.size();
            int write = 0;
            for (int read = 0; read < size; read++) {
                short packed = list.getShort(read);
                int lx = packed & 15;
                int ly = (packed >>> 4) & 15;
                int lz = (packed >>> 8) & 15;
                int y = SectionPos.sectionToBlockCoord(sectionY, ly);
                if (!canPruneInteriorProtoMark(lx, y, lz, minBuildHeight, localBlockAt)) {
                    if (write != read) {
                        list.set(write, packed);
                    }
                    write++;
                }
            }
            if (write < size) {
                list.removeElements(write, size);
            }
        }
    }

    static boolean canPruneInteriorProtoMark(
            int lx,
            int y,
            int lz,
            int minBuildHeight,
            BlockLookup localBlockAt
    ) {
        if (lx < 1 || lx > 14 || lz < 1 || lz > 14 || y <= minBuildHeight) {
            return false;
        }
        BlockState self = localBlockAt.get(lx, y, lz);
        if (self == null || !self.is(Blocks.WATER) || !self.getFluidState().isSource()) {
            return false;
        }
        return isWaterSpreadBlockedNeighbor(localBlockAt.get(lx, y - 1, lz))
                && isWaterSpreadBlockedNeighbor(localBlockAt.get(lx - 1, y, lz))
                && isWaterSpreadBlockedNeighbor(localBlockAt.get(lx + 1, y, lz))
                && isWaterSpreadBlockedNeighbor(localBlockAt.get(lx, y, lz - 1))
                && isWaterSpreadBlockedNeighbor(localBlockAt.get(lx, y, lz + 1));
    }
}
