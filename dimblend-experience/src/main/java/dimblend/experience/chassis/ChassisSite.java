package dimblend.experience.chassis;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.compat.sable.VoidFitVoxelizer;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ChassisSite {
    /**
     * Create's marker for where a bezier connection runs. It is written by
     * {@code TrackBlockEntity#manageFakeTracksAlong} when a curve is attached, carries no
     * collision, and the depot cannot avoid it: the siding's east end has a curve leaving
     * it, so a fake track lands just past the last rail.
     */
    private static final String FAKE_TRACK_ID = "create:fake_track";
    /**
     * Placed on top of the deck by lush-caves features. It is neither replaceable nor in
     * {@code replaceable_by_trees}, so it needs a name of its own; a moss carpet inside the
     * car's footprint is what broke a fresh depot on 2026-11-02.
     */
    private static final String MOSS_CARPET_ID = "minecraft:moss_carpet";

    private ChassisSite() {}

    public static AABB bounds(BlockPos center) {
        return new AABB(center.getX() - 24, center.getY() - 1, center.getZ() - 3,
                center.getX() + 25, center.getY() + 2, center.getZ() + 4);
    }

    /**
     * Whether a block found inside a chassis footprint is something other than an
     * obstruction.
     *
     * <p>The pure form exists so the decision can be tested without a level. A user hit
     * this on 2026-11-01: a fresh depot built no car at all, logging
     * {@code built=0 assembled=0 attempts=8}, because one {@code create:fake_track} sat at
     * the east end of the siding and the old check only excused {@code TrackBlock}.
     *
     * @param blockId     registry id of the block at this position
     * @param isRealTrack true when the block is a Create/Railways track block
     * @param isOurBlock  true when this position is one of this build's own cells
     */
    static boolean isIgnorable(String blockId, boolean isRealTrack, boolean isOurBlock) {
        if (isOurBlock || isRealTrack) {
            return true;
        }
        if (FAKE_TRACK_ID.equals(blockId)) {
            return true;
        }
        return "minecraft:air".equals(blockId) || "minecraft:cave_air".equals(blockId)
                || "minecraft:void_air".equals(blockId);
    }

    /** Level-backed form: air by state, plus the two exceptions. */
    private static boolean isIgnorable(BlockState state, boolean isOurBlock) {
        if (isOurBlock) {
            return true;
        }
        if (state.isAir()) {
            return true;
        }
        if (state.getBlock() instanceof TrackBlock) {
            return true;
        }
        return FAKE_TRACK_ID.equals(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
    }

    /**
     * Whether a block inside a chassis footprint is worldgen debris a depot may delete
     * rather than treat as an obstruction.
     *
     * <p>A user reported on 2026-11-02 that a freshly generated depot had no car at all:
     * {@code built=0 assembled=0 attempts=8}. The hall is carved by the worldgen pass, but
     * features of neighbouring chunks run after it, so an oak tree left leaves at
     * {@code (8175, 64, 13)} and a lush-caves patch left moss carpets on the deck — inside
     * the car's footprint. One such block is enough to refuse the build.
     *
     * <p>The pure form takes the two registry-backed answers as arguments so the decision
     * stays testable without a level, the same shape as {@link #isIgnorable(String, boolean,
     * boolean)}.
     *
     * @param blockId           registry id of the block at this position
     * @param canBeReplaced     {@code state.canBeReplaced()} — vanilla's "a feature may write
     *                          over this"
     * @param replaceableByTrees {@code state.is(BlockTags.REPLACEABLE_BY_TREES)} — leaves,
     *                          grass, ferns, flowers, vines
     */
    static boolean isDepotDebris(String blockId, boolean canBeReplaced, boolean replaceableByTrees) {
        return canBeReplaced || replaceableByTrees || MOSS_CARPET_ID.equals(blockId);
    }

    private static boolean isDepotDebris(BlockState state) {
        return isDepotDebris(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())),
                state.canBeReplaced(), state.is(BlockTags.REPLACEABLE_BY_TREES));
    }

    /**
     * First block inside the footprint that is still an obstruction, or {@code null} when
     * the footprint is clear of everything but air, tracks and debris. Exists so a refused
     * build says what refused it: the 2026-11-02 depot failed eight times and logged only
     * "left unfinished", which names no cause at all.
     */
    public static BlockPos firstObstruction(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-24, -1, -3), center.offset(24, 1, 3))) {
            BlockState state = level.getBlockState(pos);
            if (isIgnorable(state, false) || isDepotDebris(state)) {
                continue;
            }
            return pos.immutable();
        }
        return null;
    }

    public static boolean loaded(ServerLevel level, BlockPos center) {
        for (int cx = (center.getX() - 24) >> 4; cx <= (center.getX() + 24) >> 4; cx++) {
            for (int cz = (center.getZ() - 3) >> 4; cz <= (center.getZ() + 3) >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /** The only ignored blocks are this controller, completed build cells, and tracks. */
    public static boolean clear(ServerLevel level, BlockPos center, Map<BlockPos, BlockState> built,
            boolean controllerPresent) {
        return clear(level, center, built, controllerPresent, false);
    }

    /**
     * @param clearDebris also delete worldgen debris a feature left in the footprint
     *                    ({@link #isDepotDebris}) instead of refusing. The depot sets this:
     *                    its hall is the mod's own structure, so a leaf that a neighbouring
     *                    chunk's tree dropped into it is not a player's work.
     */
    public static boolean clear(ServerLevel level, BlockPos center, Map<BlockPos, BlockState> built,
            boolean controllerPresent, boolean clearDebris) {
        if (!loaded(level, center)) return false;
        List<BlockPos> debris = clearDebris ? new ArrayList<>() : List.of();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-24, -1, -3), center.offset(24, 1, 3))) {
            BlockState state = level.getBlockState(pos);
            if (controllerPresent && pos.equals(center)) continue;
            BlockState expected = built.get(pos);
            if (expected != null) {
                if (!state.equals(expected)) return false;
                continue;
            }
            if (isIgnorable(state, false)) continue;
            if (!clearDebris || !isDepotDebris(state)) return false;
            debris.add(pos.immutable());
        }
        AABB box = bounds(center);
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        for (var candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) continue;
            var b = subLevel.boundingBox();
            if (!box.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) continue;
            boolean[] occupied = {false};
            int[] examined = {0};
            VoidFitVoxelizer.forEachProjectedBlock(subLevel, (pos, state, x, y, z, hx, hy, hz) -> {
                examined[0]++;
                if (box.intersects(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz)) occupied[0] = true;
            });
            // A plot still arriving from disk is not evidence that the site is clear.
            if (occupied[0] || examined[0] == 0) return false;
        }
        // Only now: a refused site must not have been half-cleaned on the way out.
        for (BlockPos pos : debris) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        return true;
    }
}
