package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.api.util.ChunkAvailability;
import com.crystaelix.simurail.api.util.SubLevelUtil;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * E11「附近 2 格内没有其他 sable 车钩」的扫描（服务端）。
 *
 * <p>结构照抄 Simurail {@code AutomaticCouplerBlockEntity#findPartner}：取本车钩的世界
 * 坐标，向 {@link CouplerChargeMath#NEIGHBOUR_RADIUS} 半径收集相交子层级，把该坐标逆变换
 * 回每个子层级局部系后扫方块盒，再用 {@code distanceSquaredWithSubLevels} 量真实世界距离。
 * 平移旋转都保距，局部位移长度等于世界位移长度，所以局部盒开 ±2 不会漏。
 * 这样「2 格」与连挂判定是同一把尺子。</p>
 *
 * <p><b>刻意不按子层级过滤候选</b>，这与 findPartner 相反，也是本扫描最容易写错的地方：
 * 全速拉开的正是要取消充能的那个场景，两只车钩已分属两个不同载具。按「同一子层级」过滤
 * 会永不命中，充能也就永不取消。findPartner 过滤是因为它要找同一列车里能连挂的伙伴；
 * 这里要找的是"别处还有没有车钩"，恰好相反。</p>
 *
 * <p>不过滤类型、不过滤对方是否通电、不过滤对方是否已有伙伴——用户口径是「附近 2 格内
 * 没有其他 sable 的车钩」，任何一只都算。</p>
 *
 * <p>未加载区块一律跳过，绝不触发同步区块加载：服务端主线程的同步加载可能永久卡死，
 * {@link ChunkAvailability} 正是为此存在。</p>
 */
public final class CouplerChargeScan {

    /** @return 半径内是否存在除本车钩以外的另一只自动车钩。 */
    public static boolean isAnotherCouplerNear(Level level, AutomaticCouplerBlockEntity self) {
        Vec3 selfCenter = self.getBlockPos().getCenter();
        Vector3d global = Sable.HELPER.projectOutOfSubLevel(level,
                new Vector3d(selfCenter.x, selfCenter.y, selfCenter.z), new Vector3d());

        MutableBlockPos cursor = new MutableBlockPos();
        Vector3d local = new Vector3d();
        double radius = CouplerChargeMath.NEIGHBOUR_RADIUS;

        for (SubLevel candidate : SubLevelUtil.getIntersectingSubLevels(level, global, radius)) {
            if (candidate == null) {
                local.set(global);
            } else {
                Pose3dc pose = candidate.logicalPose();
                if (pose == null) {
                    local.set(global);
                } else {
                    pose.transformPositionInverse(global, local);
                }
            }
            // 平移旋转都保距：世界系里 2 格内的方块，局部系里也必在 ±2 盒内
            int minX = Mth.floor(local.x) - 2;
            int maxX = Mth.ceil(local.x) + 2;
            int minZ = Mth.floor(local.z) - 2;
            int maxZ = Mth.ceil(local.z) + 2;
            if (!ChunkAvailability.areChunksLoaded(level, minX, minZ, maxX, maxZ)) {
                continue;
            }
            for (int x = minX; x <= maxX; x++) {
                for (int y = Mth.floor(local.y) - 2; y <= Mth.ceil(local.y) + 2; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        cursor.set(x, y, z);
                        BlockEntity found = level.getBlockEntity(cursor);
                        if (found == self || !(found instanceof AutomaticCouplerBlockEntity other) || other.isRemoved()) {
                            continue;
                        }
                        if (isNear(level, selfCenter, other.getBlockPos().getCenter(), radius)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** 两只车钩中心的世界距离是否在半径内（两端各自投影所属载具，跨载具成立）。 */
    private static boolean isNear(Level level, Vec3 a, Vec3 b, double radius) {
        double distanceSquared = Sable.HELPER.distanceSquaredWithSubLevels(
                level, a.x, a.y, a.z, b.x, b.y, b.z);
        return CouplerChargeMath.isWithin(distanceSquared, radius);
    }

    private CouplerChargeScan() {
    }
}