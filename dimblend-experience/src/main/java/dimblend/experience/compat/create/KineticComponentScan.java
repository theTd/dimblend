package dimblend.experience.compat.create;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import dimblend.experience.mixin.compat.create.RotationPropagatorInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 与转速无关的 Create 动力连通分量扫描（F2：零转速的静置网络也算连通）。
 *
 * <p>Create 的 {@code KineticNetwork} 只在转起来之后才存在（零转速 BE 的 network 为 null），
 * 不能拿来判静置连通；这里按 Create 自己的连接规则逐块广搜：候选位置走
 * {@link RotationPropagatorInvoker}（Create 私有枚举），连接判定走公共
 * {@link RotationPropagator#isConnected}（双向任一成立即连，同 Create 私有
 * {@code findConnectedNeighbour}），只多一道 {@code isLoaded} 守卫——扫描绝不强制加载区块，
 * 未加载的那一截等其区块加载（BE 重新接入）时再扫。</p>
 *
 * <p>唯一的偏离是分轴类（{@link SplitShaftBlockEntity}）的静置语义：Create 的离合器/
 * 顺序齿轮箱只有“有动力源”时才会按红石/指令断开（无源时一律返回倍率 1），
 * 照搬会把“用通电离合器隔开的两台引擎”在静置时误判为连通。故无源分轴按运转后的
 * 语义判：原版变速箱恒连通；原版离合器通电即隔断；其余（顺序齿轮箱、第三方离合类）
 * 静置时语义取决于源方向/转速，保守视为隔断——一旦转起来有了源，就按 Create 实际倍率判，
 * 起转本身会经 {@code handleAdded} 触发复扫，不会漏成永久绕过。</p>
 *
 * <p>签名只用原版类型：调用方 {@code PortableEngineExclusivity} 是常驻事件订阅者，
 * Create 缺席时不得在其反射扫描中碰到 Create 类型；本类只在 Create 在场时被调用。</p>
 */
public final class KineticComponentScan {

    private KineticComponentScan() {
    }

    /**
     * 从 {@code start} 出发广搜其所在动力连通分量，返回分量内满足 {@code match} 的方块位置
     * （含起点自身）。{@code visited} 由调用方跨多次调用共享以避免同一分量重复扫描；
     * 起点已在 {@code visited} 中、未加载或不是动力 BE 时返回空表。
     */
    public static List<BlockPos> collectMatching(ServerLevel level, BlockPos start, Set<BlockPos> visited,
                                                 Predicate<BlockState> match) {
        List<BlockPos> matched = new ArrayList<>();
        if (visited.contains(start) || !level.isLoaded(start)) {
            return matched;
        }
        if (!(level.getBlockEntity(start) instanceof KineticBlockEntity origin)) {
            return matched;
        }
        visited.add(origin.getBlockPos());
        ArrayDeque<KineticBlockEntity> queue = new ArrayDeque<>();
        queue.add(origin);
        while (!queue.isEmpty()) {
            KineticBlockEntity current = queue.poll();
            if (match.test(current.getBlockState())) {
                matched.add(current.getBlockPos());
            }
            if (isIdleSeparator(current)) {
                continue; // 可从任一侧到达，但不经它把两侧连成一片
            }
            for (BlockPos candidate : RotationPropagatorInvoker.dimblend$getPotentialNeighbourLocations(current)) {
                if (visited.contains(candidate)) {
                    continue;
                }
                KineticBlockEntity neighbour = connectedNeighbour(level, current, candidate);
                if (neighbour != null && visited.add(neighbour.getBlockPos())) {
                    queue.add(neighbour);
                }
            }
        }
        return matched;
    }

    /** 同 Create 私有 {@code findConnectedNeighbour}，多一道已加载守卫。 */
    private static KineticBlockEntity connectedNeighbour(ServerLevel level, KineticBlockEntity current,
                                                         BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IRotate) || !state.hasBlockEntity()) {
            return null;
        }
        if (!(level.getBlockEntity(pos) instanceof KineticBlockEntity neighbour)) {
            return null;
        }
        if (RotationPropagator.isConnected(current, neighbour) || RotationPropagator.isConnected(neighbour, current)) {
            return neighbour;
        }
        return null;
    }

    /**
     * 无源分轴按运转语义是否隔断两侧（见类注释）。精确类比对而非 instanceof：
     * 第三方可能继承原版离合器/变速箱并改写倍率语义（如反相离合器），不能套原版口径。
     */
    private static boolean isIdleSeparator(KineticBlockEntity be) {
        if (!(be instanceof SplitShaftBlockEntity) || be.hasSource()) {
            return false;
        }
        if (be.getClass() == GearshiftBlockEntity.class) {
            return false;
        }
        if (be.getClass() == ClutchBlockEntity.class) {
            return be.getBlockState().getOptionalValue(BlockStateProperties.POWERED).orElse(false);
        }
        return true;
    }
}
