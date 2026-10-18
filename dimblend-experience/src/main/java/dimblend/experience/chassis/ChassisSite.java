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
    private ChassisSite() {}

    public static AABB bounds(BlockPos center) {
        return new AABB(center.getX() - 24, center.getY() - 1, center.getZ() - 3,
                center.getX() + 25, center.getY() + 2, center.getZ() + 4);
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
            } else if (!state.isAir() && !(state.getBlock() instanceof TrackBlock)) {
                return false;
            }
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
