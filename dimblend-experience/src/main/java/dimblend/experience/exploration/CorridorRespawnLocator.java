package dimblend.experience.exploration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A2 床遗失重生落点：回到走廊旁但保留死亡时的 X 坐标——(死亡x, y=66, z=5)。
 * dimblend 旋转维度的铁路走廊位于 Z=0、Y=64，z=5 在轨道旁的路床上；
 * 走廊条带由 dimblend 预生成（z ∈ [-16, 15]），所以该落点的区块必然可用。
 */
public final class CorridorRespawnLocator {

    public static final double CORRIDOR_Z = 5.0D;
    /** 规格站立高度 y=66 对应的地面块层。dimblend 走廊轨道 Y=64、路床 Y=63，z=5 处为自然地形。 */
    public static final int FLOOR_Y = 65;
    /** 地形起伏容差：只在规格点附近一个矮窗口内扫描（升序取最低命中），超出窗口即兜底，防止命中走廊顶盖上方的高空点。 */
    public static final int SCAN_MIN_FLOOR = FLOOR_Y - 4;
    public static final int SCAN_MAX_FLOOR = FLOOR_Y + 6;
    public static final int SCAN_RANGE_X = 6;

    /**
     * 在 (deathX, 站立y≈66, CORRIDOR_Z) 附近寻找“脚下实心、上方两格空心”的
     * 站立点；窗口内找不到时返回基准点 (x+0.5, 66, 5.5)。
     */
    public static Vec3 findRespawnPosition(ServerLevel level, double deathX) {
        int baseX = BlockPos.containing(deathX, 0, 0).getX();
        int z = (int) CORRIDOR_Z;
        for (int y = SCAN_MIN_FLOOR; y <= SCAN_MAX_FLOOR; y++) {
            for (int dx = 0; dx <= SCAN_RANGE_X; dx++) {
                int[] xs = dx == 0 ? new int[]{0} : new int[]{dx, -dx};
                for (int x : xs) {
                    BlockPos floor = new BlockPos(baseX + x, y, z);
                    if (isStandable(level, floor)) {
                        return new Vec3(floor.getX() + 0.5D, y + 1.0D, CORRIDOR_Z);
                    }
                }
            }
        }
        return new Vec3(deathX + 0.5D, FLOOR_Y + 1.0D, CORRIDOR_Z);
    }

    private static boolean isStandable(ServerLevel level, BlockPos floor) {
        BlockState floorState = level.getBlockState(floor);
        if (floorState.getCollisionShape(level, floor).isEmpty()) {
            return false;
        }
        return level.getBlockState(floor.above()).getCollisionShape(level, floor.above()).isEmpty()
                && level.getBlockState(floor.above(2)).getCollisionShape(level, floor.above(2)).isEmpty();
    }

    private CorridorRespawnLocator() {
    }
}
