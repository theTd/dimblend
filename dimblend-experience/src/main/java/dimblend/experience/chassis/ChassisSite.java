package dimblend.experience.chassis;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.compat.sable.VoidFitVoxelizer;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.Map;

public final class ChassisSite {
    /**
     * Create's marker for where a bezier connection runs. It is written by
     * {@code TrackBlockEntity#manageFakeTracksAlong} when a curve is attached, carries no
     * collision, and the depot cannot avoid it: the siding's east end has a curve leaving
     * it, so a fake track lands just past the last rail.
     */
    private static final String FAKE_TRACK_ID = "create:fake_track";

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
        return FAKE_TRACK_ID.equals(String.valueOf(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())));
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
        if (!loaded(level, center)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-24, -1, -3), center.offset(24, 1, 3))) {
            BlockState state = level.getBlockState(pos);
            if (controllerPresent && pos.equals(center)) continue;
            BlockState expected = built.get(pos);
            if (expected != null) {
                if (!state.equals(expected)) return false;
                continue;
            }
            if (!isIgnorable(state, false)) return false;
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
        return true;
    }
}
