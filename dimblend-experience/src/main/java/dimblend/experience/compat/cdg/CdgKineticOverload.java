package dimblend.experience.compat.cdg;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * 柴油机过载的实时复核。{@code isOverStressed()} 是 {@code updateFromNetwork} 写下的缓存；
 * 加载期 {@code addSilently} 会改网络账本但不 sync，缓存可以停在 true，同时
 * {@code (stress, networkSize)} 不再变化。
 *
 * <p>缓存位为真时先 {@code updateNetwork()}（总量变了就 sync 全网），再以
 * {@code calculateCapacity/calculateStress} 的实时值按 {@link CdgOverloadMath#countsAsOverload}
 * 判定——不能只重读缓存位：{@code updateNetwork()} 仅在网络缓存总量变化时 sync，
 * 总量未变而本 BE 的缓存位单独粘住时它原样保留（Create 6.0.10-281 字节码）。
 * 实时不过载而缓存位仍真，就用实时值只刷本 BE 的缓存位（{@code updateFromNetwork}，
 * 与 Create sync 对单个成员的写法相同），出力/转速显示不再按过载停摆。</p>
 *
 * <p>实时值本身在读档后重建时的重复计数由
 * {@code dimblend.experience.compat.create.KineticUnloadedShare} 修正，本类不再兜底。</p>
 */
public final class CdgKineticOverload {

    /**
     * 现在是否应按过载累计。无网络、应力冲击关闭、或缓存位为假：直接否。
     * 缓存位为真时刷新账本并按实时容量/应力判定；实时不过载则刷掉本 BE 的缓存位，返回否。
     */
    public static boolean refreshedOverstressed(KineticBlockEntity be) {
        if (!be.hasNetwork() || !IRotate.StressImpact.isEnabled() || !be.isOverStressed()) {
            return false;
        }
        KineticNetwork network = be.getOrCreateNetwork();
        network.updateNetwork();
        float capacity = network.calculateCapacity();
        float stress = network.calculateStress();
        if (CdgOverloadMath.countsAsOverload(be.isOverStressed(), capacity, stress)) {
            return true;
        }
        if (be.isOverStressed()) {
            // 总量未变 updateNetwork 不 sync，粘住的缓存位只能在这里刷
            be.updateFromNetwork(capacity, stress, network.getSize());
        }
        return false;
    }

    private CdgKineticOverload() {
    }
}
