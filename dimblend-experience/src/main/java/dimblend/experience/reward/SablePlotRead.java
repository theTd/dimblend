package dimblend.experience.reward;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Reads the block that a Sable sub-level actually stores behind a world position.
 *
 * <p><b>The bug this replaces.</b> A sub-level's blocks are not stored at the coordinates where
 * the vehicle appears. Sable copies them into a <i>plot</i>: a chunk region parked far away in the
 * same {@code ServerLevel}, around chunk 1,280,000 / block 20.5 million. The world bounding box
 * is only where the vehicle is <i>rendered</i>. So code that asks
 * {@code level.getBlockState(worldPos)} for the positions inside that box always reads <b>air</b>.
 *
 * <p>That is why two features the user reported as missing never ran: the "only the structure is
 * left, so dissolve" test saw a permanently empty wagon, and the "touching a warp gate dissolves
 * it" test never found a gate.
 *
 * <p><b>The mapping.</b> Sable's own readers do exactly this: take the world position, push it
 * through the pose's inverse to get the block's local coordinate, and read the level at that
 * coordinate — the plot chunk holding it is registered in the same level. See
 * {@code SubLevelEntityShadowRenderer}, which converts a world box with
 * {@code bounds.transformInverse(renderPose, localBounds)} and then reads
 * {@code level.getBlockState(localPos)}. The pose's rotation point is the plot anchor, so the
 * inverse transform lands on absolute plot block coordinates; that is what makes a moved (and
 * rotated) wagon read correctly instead of only at assembly time.
 */
public final class SablePlotRead {

    /**
     * Converts a world block position to the plot coordinate holding that block.
     *
     * @return the plot position, or {@code null} when the sub-level is gone
     */
    public static BlockPos toPlotPos(ServerSubLevel car, BlockPos worldPos) {
        if (car == null || car.isRemoved()) {
            return null;
        }
        Vec3 local = car.logicalPose().transformPositionInverse(Vec3.atCenterOf(worldPos));
        return BlockPos.containing(local);
    }

    /**
     * Reads the block of {@code car} that appears at {@code worldPos}.
     *
     * @return the state, or {@code null} when the sub-level is gone
     */
    public static BlockState blockAt(ServerLevel level, ServerSubLevel car, BlockPos worldPos) {
        BlockPos plotPos = toPlotPos(car, worldPos);
        if (plotPos == null) {
            return null;
        }
        return level.getBlockState(plotPos);
    }

    /**
     * True when the position belongs to this sub-level rather than to the world or a neighbour.
     *
     * <p>Read through {@code car.boundingBox()}: the plot is a contiguous block region, so a
     * plot coordinate outside the sub-level's own box is another sub-level's chunk or plain air.
     */
    public static boolean owns(ServerSubLevel car, BlockPos worldPos) {
        BlockPos plotPos = toPlotPos(car, worldPos);
        if (plotPos == null) {
            return false;
        }
        var box = car.boundingBox();
        return plotPos.getX() >= Mth.floor(box.minX()) && plotPos.getX() <= Mth.floor(box.maxX())
                && plotPos.getY() >= Mth.floor(box.minY()) && plotPos.getY() <= Mth.floor(box.maxY())
                && plotPos.getZ() >= Mth.floor(box.minZ()) && plotPos.getZ() <= Mth.floor(box.maxZ());
    }

    private SablePlotRead() {
    }
}