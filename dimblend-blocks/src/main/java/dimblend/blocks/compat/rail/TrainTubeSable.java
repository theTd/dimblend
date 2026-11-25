package dimblend.blocks.compat.rail;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Sable 相关的查询都收在这里，方便集中核对 API。
 *
 * <p>所有方法都假定 {@code sable} 在场（调用方在 {@code DimBlendBlocks} 的 {@code create && sable}
 * 守卫之内）；本类被加载即意味着守卫已通过。</p>
 */
public final class TrainTubeSable {

    private TrainTubeSable() {
    }

    /** 放置判定：管道只能装在 Sable 子层级（列车结构）上，创造模式也不放宽（与末影物流箱同口径）。 */
    public static boolean canPlace(Level level, BlockPos pos, @Nullable Entity placer) {
        if (!TrainTubes.STRICT_SABLE_IN_CREATIVE
                && placer instanceof Player player
                && player.isCreative()) {
            return true;
        }
        return SableCompanion.INSTANCE.getContaining(level, (Vec3i) pos) != null;
    }

    @Nullable
    public static SubLevel subLevelAt(Level level, BlockPos pos) {
        return SableCompanion.INSTANCE.getContaining(level, (Vec3i) pos) instanceof SubLevel subLevel
                ? subLevel
                : null;
    }

    @Nullable
    public static UUID subLevelId(Level level, BlockPos pos) {
        SubLevel subLevel = subLevelAt(level, pos);
        return subLevel == null ? null : subLevel.getUniqueId();
    }

    @Nullable
    public static SubLevel subLevel(ServerLevel level, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return container == null ? null : container.getSubLevel(id);
    }

    /** 把子层级坐标系里的方块中心投影成世界坐标（不在子层级上时就是原坐标）。 */
    public static Vec3 toWorld(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (SableCompanion.INSTANCE.getContaining(level, (Vec3i) pos) == null) {
            return center;
        }
        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, center);
    }

    /**
     * 两个子层级是否<b>直接挂接</b>（锁定判定 #2：只认相邻挂接，不认通讯/间接关系）。
     *
     * <p>做法是不依赖 simurail 的类型：扫一侧的所有 {@link BlockEntitySubLevelActor}，
     * 看有没有谁把另一侧声明成自己的连接依赖（simurail 的自动车钩 {@code AutomaticCouplerBlockEntity}
     * 正是靠 {@code sable$getConnectionDependencies()} 表态）。双向各查一次以容忍实现只登记一侧。</p>
     */
    public static boolean directlyCoupled(ServerLevel level, UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) {
            return false;
        }
        SubLevel a = subLevel(level, first);
        SubLevel b = subLevel(level, second);
        if (a == null || b == null) {
            return false;
        }
        return actorDependsOn(a, b) || actorDependsOn(b, a);
    }

    private static boolean actorDependsOn(SubLevel from, SubLevel target) {
        if (from.getPlot() == null || target.getUniqueId() == null) {
            return false;
        }
        for (BlockEntitySubLevelActor actor : from.getPlot().getBlockEntityActors()) {
            if (actor == null) {
                continue;
            }
            for (SubLevel dependency : actor.sable$getConnectionDependencies()) {
                if (dependency != null && target.getUniqueId().equals(dependency.getUniqueId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
