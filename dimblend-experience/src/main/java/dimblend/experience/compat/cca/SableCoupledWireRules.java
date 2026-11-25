package dimblend.experience.compat.cca;

import java.util.UUID;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * 「Sable 载具之间不允许直接拉电线」口径（2026-11-25 用户口径）。
 *
 * <p>允许的电线只有三类：真实世界 ↔ 真实世界、同一载具内部、以及<b>直接相邻耦合</b>
 * 的两台载具之间（车钩已经把它们焊成一个编组，电网拼接才有物理依据）。
 * 其余跨载具/载具↔世界的连线一律不可连：{@code sable_createaddition_compat} 计算
 * {@code wireDistanceSquared} 时我们直接返回无穷远，Create: Addition 自己就会按
 * "过长" 拒连（{@code WireConnectResult.LONG}），不另造一套连接状态机。</p>
 *
 * <p>断钩时（{@code AutomaticCouplerBlockEntity#removePartner}）按同一口径反扫：
 * 原本允许、现在不再允许的连线全部断开。判定只读子层级 UUID，纯函数部分可单测。</p>
 */
public final class SableCoupledWireRules {

    /** 同载具或同在真实世界：允许。 */
    public static boolean isSameWiringDomain(UUID a, UUID b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 跨载具（含载具↔真实世界）：需要"直接耦合"才允许。 */
    public static boolean isCrossWiringDomain(UUID a, UUID b) {
        return !isSameWiringDomain(a, b);
    }

    /**
     * 连线是否放行。
     *
     * @param directlyCoupled 两端所在载具是否被车钩直接耦合
     */
    public static boolean allowsWire(UUID a, UUID b, boolean directlyCoupled) {
        if (isSameWiringDomain(a, b)) {
            return true;
        }
        return directlyCoupled;
    }

    /** 断钩后必须清理的连线：任何跨域连线都不再合法。 */
    public static boolean mustBreakAfterUncouple(UUID a, UUID b) {
        return isCrossWiringDomain(a, b);
    }

    /** 方块所在的子层级 UUID；不在任何载具内返回 null。 */
    public static UUID subLevelId(Level level, BlockPos pos) {
        SubLevel subLevel = containing(level, pos);
        return subLevel == null ? null : subLevel.getUniqueId();
    }

    /** 两端连线当前是否放行（含耦合判定）。 */
    public static boolean allowsWire(Level level, BlockPos first, BlockPos second) {
        SubLevel a = containing(level, first);
        SubLevel b = containing(level, second);
        UUID idA = a == null ? null : a.getUniqueId();
        UUID idB = b == null ? null : b.getUniqueId();
        return allowsWire(idA, idB, isDirectlyCoupled(a, b));
    }

    /**
     * 两台载具是否被车钩直接耦合——读 Simurail 车钩自己声明的连接依赖，
     * 不重复实现一遍车钩配对逻辑。
     */
    public static boolean isDirectlyCoupled(SubLevel a, SubLevel b) {
        if (a == null || b == null || a == b) {
            return false;
        }
        for (SubLevel dependency : couplerDependencies(a)) {
            if (dependency != null && dependency.getUniqueId().equals(b.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    private static Iterable<SubLevel> couplerDependencies(SubLevel subLevel) {
        if (!ModList.get().isLoaded("simurail")) {
            return java.util.List.of();
        }
        return CouplerScan.dependencies(subLevel);
    }

    /** 方块实体所在的子层级；不在任何载具内返回 null。 */
    public static SubLevel subLevelOf(net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (blockEntity == null || !ModList.get().isLoaded("sable")) {
            return null;
        }
        return Sable.HELPER.getContaining(blockEntity);
    }

    private static SubLevel containing(Level level, BlockPos pos) {
        if (!ModList.get().isLoaded("sable")) {
            return null;
        }
        return Sable.HELPER.getContaining(level, pos);
    }

    /** Only resolved behind the simurail gate; keeps the coupler types off the load path. */
    private static final class CouplerScan {
        private static Iterable<SubLevel> dependencies(SubLevel subLevel) {
            java.util.List<SubLevel> result = new java.util.ArrayList<>();
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor
                    : subLevel.getPlot().getBlockEntityActors()) {
                if (actor instanceof com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity coupler) {
                    for (SubLevel dependency : coupler.sable$getConnectionDependencies()) {
                        if (dependency != null) {
                            result.add(dependency);
                        }
                    }
                }
            }
            return result;
        }
    }

    private SableCoupledWireRules() {
    }
}
