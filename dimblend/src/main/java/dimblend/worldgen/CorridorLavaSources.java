package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import dimblend.fluid.NetherLavaFlow;
import dimblend.mixin.FlowingFluidSpreadInvoker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/** Generation-only lava treatment. No event/tick/load/player placement entry points. */
public final class CorridorLavaSources {
    private static final Direction[] OUTLETS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN
    };

    private CorridorLavaSources() { }

    /** Only lower-stage dependencies: no same-stage neighbor waits or future joins. */
    public static UnaryOperator<ChunkStep.Builder> finalizationDependencies(UnaryOperator<ChunkStep.Builder> original) {
        return builder -> original.apply(builder).addRequirement(ChunkStatus.FEATURES, 2).blockStateWriteRadius(0);
    }

    /** All possible feature writers of the source halo have finished before this call. */
    public static void finish(WorldGenRegion level, ChunkAccess origin, RotatingChunkGenerator rotating) {
        if (level.getLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL || !isNewProto(origin)
                || CorridorLavaOriginals.finished(origin)
                || !CorridorLavaPlan.touchesOutput(origin.getPos().getMinBlockZ(), origin.getPos().getMaxBlockZ())) return;
        // The existing radius-2 FEATURES barrier precedes every transaction here.
        // No source scan, snapshot, output request or block write runs in FEATURES.
        synchronized (rotating) {
            ChunkPos center = origin.getPos();
            Map<Long, ChunkAccess> chunks = new HashMap<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = center.x + dx;
                    int z = center.z + dz;
                    if (!level.hasChunk(x, z)) continue;
                    // WorldGenRegion reads the generation cache; never ask ServerLevel.
                    ChunkAccess chunk = level.getChunk(x, z);
                    if (chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                        chunks.put(ChunkPos.asLong(x, z), chunk);
                    }
                }
            }
            IntPredicate lane = x -> NetherLavaFlow.isNetherSpeedLane(
                    BandLayout.laneName(rotating.delegateForBlockX(x)));
            finishStableChunks(origin, chunks, lane, x -> RegionBoundaryWall.ownsBoundaryColumn(rotating, x));
        }
    }

    /**
     * The production final transaction, also exercised with isolated real proto chunks
     * by GameTests. Call only after all natural FEATURES writers of the source halo
     * have returned; callers serialize neighboring transactions as finish() does.
     */
    public static void finishStableChunks(ChunkAccess origin, Map<Long, ChunkAccess> chunks,
            IntPredicate lane, IntPredicate boundaryColumn) {
        if (!isNewProto(origin) || CorridorLavaOriginals.finished(origin)
                || !CorridorLavaPlan.touchesOutput(origin.getPos().getMinBlockZ(), origin.getPos().getMaxBlockZ())) return;
        ChunkPos center = origin.getPos();
        OriginalView view = new OriginalView(chunks, origin.getMinBuildHeight(), origin.getHeight());
        List<BlockPos> sources = new ArrayList<>();
        for (ChunkAccess chunk : chunks.values()) {
            // Old FULL chunks can provide collision context, never sources or writes.
            if (!isNewProto(chunk)) continue;
            int minZ = Math.max(-CorridorLavaPlan.HALF_WIDTH, chunk.getPos().getMinBlockZ());
            int maxZ = Math.min(CorridorLavaPlan.HALF_WIDTH, chunk.getPos().getMaxBlockZ());
            if (minZ > maxZ) continue;
            boolean eligibleLane = false;
            for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++) {
                if (lane.test(x)) {
                    eligibleLane = true;
                    break;
                }
            }
            if (!eligibleLane) continue;
            Set<BlockPos> candidates = CorridorLavaOriginals.sources(chunk);
            // Vanilla's palette-aware findBlocks skips sections with no lava.
            // Saved candidates recover sources already erased by a higher shaft.
            chunk.findBlocks(state -> state.getFluidState().is(FluidTags.LAVA)
                    && state.getFluidState().isSource(), (pos, state) -> {
                        if (CorridorLavaPlan.inSourceRange(pos.getY(), pos.getZ())) candidates.add(pos.immutable());
                    });
            for (BlockPos source : candidates) {
                if (!CorridorLavaPlan.inSourceRange(source.getY(), source.getZ()) || !lane.test(source.getX())) continue;
                if (source.getX() < center.getMinBlockX() - 1
                        || source.getX() > center.getMaxBlockX() + 1
                        || source.getZ() < center.getMinBlockZ() - 1
                        || source.getZ() > center.getMaxBlockZ() + 1) continue;
                FluidState fluid = view.getFluidState(source);
                if (!fluid.is(FluidTags.LAVA) || !fluid.isSource()) continue;
                sources.add(source);
                // Preserve only the stable state before our own final writes.
                CorridorLavaOriginals.remember(chunk, source, view.getBlockState(source));
                for (Direction direction : OUTLETS) {
                    BlockPos neighbor = source.relative(direction);
                    remember(view, chunks, neighbor);
                    // Fluid replacement predicates may read the target's fluid height.
                    remember(view, chunks, neighbor.above());
                }
            }
        }
        CorridorLavaPlan plan = plan(view, sources);
        plan.forEachClear(cell -> write(chunks, origin, boundaryColumn, cell, Blocks.AIR.defaultBlockState()));
        plan.forEachGlass(cell -> write(chunks, origin, boundaryColumn, cell, Blocks.RED_STAINED_GLASS.defaultBlockState()));
        // These requests come exclusively from other stable final transactions.
        // A source owner may already be FULL; no rescan of that chunk is needed.
        CorridorLavaOriginals.outputs(origin).forEach((pos, state) ->
                write(chunks, origin, boundaryColumn, cell(pos), state));
        // Publish before INITIALIZE_LIGHT: later neighboring tasks must not mutate
        // metadata that can already have moved into a FULL chunk.
        CorridorLavaOriginals.markFinished(origin);
    }

    /** No live chunks (including ImposterProtoChunk wrappers), even if loaded in this region. */
    public static boolean isNewProto(ChunkAccess chunk) {
        return chunk instanceof ProtoChunk && !(chunk instanceof ImposterProtoChunk)
                && chunk.getPersistedStatus().isBefore(ChunkStatus.FULL) && !chunk.isUpgrading();
    }

    /** Shared with GameTests: all decisions are taken before any world mutation. */
    public static CorridorLavaPlan plan(BlockGetter original, Iterable<BlockPos> sources) {
        CorridorLavaPlan plan = new CorridorLavaPlan();
        for (BlockPos source : sources) {
            if (!CorridorLavaPlan.inSourceRange(source.getY(), source.getZ())) continue;
            FluidState fluid = original.getFluidState(source);
            if (!fluid.is(FluidTags.LAVA) || !fluid.isSource() || !canFlowOut(original, source)) continue;
            List<CorridorLavaPlan.Cell> air = new ArrayList<>(4);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = source.relative(direction);
                if (original.getBlockState(adjacent).isAir()) air.add(cell(adjacent));
            }
            plan.openSource(cell(source), air);
        }
        return plan;
    }

    /** Calls all three vanilla checks: replaceability, face occlusion and liquid containment. */
    public static boolean canFlowOut(BlockGetter original, BlockPos source) {
        BlockState from = original.getBlockState(source);
        FluidState sourceFluid = from.getFluidState();
        if (!sourceFluid.is(FluidTags.LAVA) || !sourceFluid.isSource()
                || !(sourceFluid.getType() instanceof FlowingFluid fluid)) return false;
        for (Direction direction : OUTLETS) {
            BlockPos target = source.relative(direction);
            BlockState to = original.getBlockState(target);
            if (((FlowingFluidSpreadInvoker) fluid).dimblend$canSpreadTo(
                    original, source, from, direction, target, to, to.getFluidState(), fluid.getFlowing())) {
                return true;
            }
        }
        return false;
    }

    private static CorridorLavaPlan.Cell cell(BlockPos pos) {
        return new CorridorLavaPlan.Cell(pos.getX(), pos.getY(), pos.getZ());
    }

    private static void remember(OriginalView view, Map<Long, ChunkAccess> chunks, BlockPos pos) {
        ChunkAccess chunk = chunks.get(ChunkPos.asLong(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)));
        if (chunk != null && isNewProto(chunk)) {
            CorridorLavaOriginals.remember(chunk, pos, view.getBlockState(pos));
        }
    }

    private static void write(Map<Long, ChunkAccess> chunks, ChunkAccess origin,
            IntPredicate boundaryColumn, CorridorLavaPlan.Cell cell, BlockState replacement) {
        ChunkAccess target = chunks.get(ChunkPos.asLong(Math.floorDiv(cell.x(), 16), Math.floorDiv(cell.z(), 16)));
        if (target == null || !isNewProto(target)
                || boundaryColumn.test(cell.x())) return;
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        if (target != origin) {
            if (CorridorLavaOriginals.finished(target)) return;
            // A neighbor may reach FULL before us; leave its source's cross-edge
            // request on our proto chunk now, while both are still new terrain.
            CorridorLavaOriginals.remember(target, pos, CorridorLavaOriginals.state(target, pos));
            CorridorLavaOriginals.request(target, pos, replacement);
            return;
        }
        applyToNewProto(target, pos, replacement);
    }

    /** Actual no-drop application; shared by generation and isolated real-ProtoChunk GameTests. */
    public static void applyToNewProto(ChunkAccess target, BlockPos pos, BlockState replacement) {
        if (!isNewProto(target) || CorridorLavaOriginals.finished(target)
                || !target.getPos().equals(new ChunkPos(pos)) || target.isOutsideBuildHeight(pos)) return;
        CorridorLavaOriginals.remember(target, pos, CorridorLavaOriginals.state(target, pos));
        replacement = CorridorLavaOriginals.request(target, pos, replacement);
        BlockState current = target.getBlockState(pos);
        if (current == replacement) return;
        // Glass is air-only even after another decorator has visited the target.
        if (replacement.is(Blocks.RED_STAINED_GLASS) && !current.isAir()) return;
        // Direct proto mutation: no destroyBlock, drops, neighbor notifications or synchronous loads.
        if (current.hasBlockEntity()) target.removeBlockEntity(pos);
        target.setBlockState(pos, replacement, false);
        target.setUnsaved(true);
    }

    private record OriginalView(Map<Long, ChunkAccess> chunks, int getMinBuildHeight, int getHeight) implements BlockGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            ChunkAccess chunk = chunks.get(ChunkPos.asLong(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)));
            if (chunk == null) return Blocks.BEDROCK.defaultBlockState();
            // FULL terrain is context only: never recover a prior generation snapshot there.
            return isNewProto(chunk) ? CorridorLavaOriginals.state(chunk, pos) : chunk.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }
    }
}
