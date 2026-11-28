package dimblend.blocks.compat.rail;

import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
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

    private static boolean actorDependsOn(@Nullable SubLevel from, @Nullable SubLevel target) {
        if (from == null || target == null) {
            return false;
        }
        LevelPlot plot = from.getPlot();
        if (plot == null) {
            return false;
        }
        // target.getUniqueId() 在 SubLevel#setUniqueId 之前是 null（字段初值 null，尽管接口标注 @NotNull）；
        // 交给纯函数统一按"没有目标"处理。
        return TrainTubeSable.<BlockEntitySubLevelActor, SubLevel>dependenciesContainTarget(
                plot.getBlockEntityActors(),
                target.getUniqueId(),
                BlockEntitySubLevelActor::sable$getConnectionDependencies,
                SubLevel::getUniqueId);
    }

    /**
     * 纯函数：{@code actors} 里有没有谁把 {@code targetId} 声明成自己的连接依赖。
     *
     * <p>刻意做成与 MC / Sable 具体类型无关的形状（actor 与依赖元素的类型都走类型参数，
     * 由 {@code dependenciesOf} / {@code idOf} 取值），单测可以直接喂 stub actor + 普通 UUID，
     * 既不用起 MC 引导，也不用造真子层级（{@code SubLevel} 抽象且构造要 {@code Level} 带 plot 容器）。</p>
     *
     * <p>容忍口径与 Sable 自己的 {@code SubLevelHelper} 一致，逐条见下面的注释。</p>
     */
    static <A, D> boolean dependenciesContainTarget(@Nullable Iterable<? extends A> actors, @Nullable UUID targetId,
            Function<? super A, ? extends Iterable<? extends D>> dependenciesOf,
            Function<? super D, UUID> idOf) {
        if (targetId == null || actors == null) {
            return false;
        }
        for (A actor : actors) {
            if (actor == null) {
                continue;
            }
            Iterable<? extends D> dependencies;
            try {
                dependencies = dependenciesOf.apply(actor);
                // 默认实现就是 return null（"我没有依赖"）：消防水枪/水带/各类 Create 兼容 actor、
                // simurail 的 GangwayJoint 等都没覆写这个方法。Sable 的 SubLevelHelper 同样写
                // `if (dependencies == null) continue;`，这里必须照抄，否则 for-each 当场 NPE。
                if (dependencies == null) {
                    continue;
                }
                for (D dependency : dependencies) {
                    if (dependency == null) {
                        continue;
                    }
                    if (targetId.equals(idOf.apply(dependency))) {
                        return true;
                    }
                }
            } catch (NullPointerException brokenActor) {
                // 上游 AutomaticCouplerBlockEntity#sable$getConnectionDependencies 对"对端子层级已卸载/已删除"
                // 的伙伴无条件写 List.of(container.getSubLevel(partnerSubLevelID))，List.of(null) 立刻 NPE
                // （dimblend-dev §8.42）。本模块不依赖 dimblend 核心的 AutomaticCouplerDependencyMixin，
                // 所以只能就地在单个 actor 上兜住：一个坏 actor 不该让挂接判定失败，
                // 更不该让每 tick 复核（TrainTubeNetworkManager#validateLinks）的服务器线程崩掉。
                continue;
            }
        }
        return false;
    }
}
