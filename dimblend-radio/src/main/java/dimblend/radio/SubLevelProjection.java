package dimblend.radio;

import java.util.ArrayList;
import java.util.List;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Sable 结构（子层级）坐标换算：电台唯一入口。
 *
 * <p>Sable 结构上的方块实际存在远处的 plot 网格里（原点 10000 plot × 128 区块 × 16 格
 * = 20,480,000），结构上唱片机的 {@link BlockPos} 是 plot 坐标，不是玩家所在的世界坐标。
 * 凡是拿唱片机位置跟玩家比距离、给声源定位、按玩家位置找唱片机的地方，都必须经这里换算，
 * 否则结构上的电台在客户端看来远在两千万格外，永远不建实例。</p>
 *
 * <p>走 sable-companion 软依赖 shim（与 dimblend 本体同款）：未装 Sable 时
 * {@code projectOutOfSubLevel} 原样返回、{@code getAllIntersecting} 为空，行为同地面唱片机。</p>
 */
public final class SubLevelProjection {

    /** 唱片机中心的世界坐标：在结构上按结构当前逻辑位姿投影，否则原样。 */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        // 走 Position 重载（Vec3 重载已标 @Deprecated，前者包一层转调后者，行为相同）
        Position center = Vec3.atCenterOf(pos);
        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, center);
    }

    /**
     * 与世界点 {@code center} 半径 {@code radius} 立方体相交的每个结构，返回 center 在该结构
     * plot 坐标系里的对应点。位姿变换只有旋转+平移（保距），plot 内以此为心扫同半径，
     * 覆盖的就是玩家身边那片结构方块。
     */
    public static List<Vec3> plotCentersNear(Level level, Vec3 center, double radius) {
        BoundingBox3d box = new BoundingBox3d(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        List<Vec3> out = new ArrayList<>();
        for (SubLevelAccess subLevel : SableCompanion.INSTANCE.getAllIntersecting(level, box)) {
            out.add(subLevel.logicalPose().transformPositionInverse(center));
        }
        return out;
    }

    private SubLevelProjection() {
    }
}
