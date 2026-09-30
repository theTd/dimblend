package dimblend.experience.compat.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.mixin.compat.create.KineticNetworkUnloadedAccessor;
import net.minecraft.world.level.Level;

/**
 * Create 应力网络未加载份额的补扣（Create 6.0.10-281 字节码）。
 *
 * <p>读档后 {@code initFromTE} 把整网存档总量记进 unloadedCapacity/unloadedStress，
 * 每个成员只在自己首 tick 的 {@code initialize → addSilently} 扣回份额。已加载但还没
 * tick 的成员（模拟距离外的加载圈、入服时尚未升到 ticking 的区块）若先被一次重建
 * （{@code detachKinetics + attachKinetics}）碰到：{@code removeSource/remove} 因不在
 * members 提前 return，{@code attach} 再经 {@code add()} 收进 members——份额既在
 * members 里又留在 unloaded 里，首 tick 的 addSilently 因已在 members 提前 return，
 * 永不扣回，整网应力永久虚高（柴油机过载确认就是被它点着的）。</p>
 *
 * <p>本类在该成员首 tick 前第一次离开存档网络（换网/置空/被拆）时，按 addSilently
 * 同口径把它的存档份额从该网络 unloaded 账本扣掉。成员一旦首 tick，调用方即停止
 * 调用，此后账本由 Create 原逻辑负责。调用方见 {@code KineticUnloadedShareMixin}。</p>
 */
public final class KineticUnloadedShare {

    /**
     * 首 tick 前离开存档网络时补扣份额。
     *
     * @param savedSpeed            读档时的理论转速（重建会先把它清 0，必须用读档快照）
     * @param savedStressApplied    读档时的 {@code AddedStress}
     * @param savedCapacityProvided 读档时的 {@code AddedCapacity}
     */
    public static void releaseBeforeFirstTick(KineticBlockEntity be, float savedSpeed,
            float savedStressApplied, float savedCapacityProvided) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide() || !be.hasNetwork()) {
            return;
        }
        // Create 的 setNetwork/remove 紧接着同样 getOrCreateNetwork()，同一查找不新增副作用
        KineticNetwork network = be.getOrCreateNetwork();
        // 未初始化：账本里还没有这份（将来 initFromTE 的残留属 Create 原有口径，不在此处理）；
        // 已在 members：份额已按在网成员计入，不能再扣
        if (network == null || !network.initialized || network.members.containsKey(be)) {
            return;
        }
        KineticNetworkUnloadedAccessor ledger = (KineticNetworkUnloadedAccessor) network;
        if (be.isSource()) {
            ledger.dimblend$setUnloadedCapacity(KineticUnloadedShareMath.released(
                    ledger.dimblend$unloadedCapacity(),
                    KineticUnloadedShareMath.capacityShare(savedCapacityProvided, be.getGeneratedSpeed())));
        }
        ledger.dimblend$setUnloadedStress(KineticUnloadedShareMath.released(
                ledger.dimblend$unloadedStress(),
                KineticUnloadedShareMath.stressShare(savedStressApplied, savedSpeed)));
        ledger.dimblend$setUnloadedMembers(KineticUnloadedShareMath.releasedMember(ledger.dimblend$unloadedMembers()));
    }

    private KineticUnloadedShare() {
    }
}
