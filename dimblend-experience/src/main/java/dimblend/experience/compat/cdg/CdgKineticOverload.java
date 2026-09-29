package dimblend.experience.compat.cdg;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * 柴油机过载的实时复核。{@code isOverStressed()} 是 {@code updateFromNetwork} 写下的缓存；
 * 加载期 {@code addSilently} 会改网络账本但不 sync，缓存可以停在 true，同时
 * {@code (stress, networkSize)} 不再变化。缓存位为真时先 {@code updateNetwork()}，
 * 让 {@code calculateCapacity/calculateStress} 写回缓存，再读这个位。
 */
public final class CdgKineticOverload {

    /**
     * 现在是否应按过载累计。无网络、应力冲击关闭、或缓存位为假：直接否。
     * 缓存位为真时刷新账本；实时容量已经盖住应力则缓存被清掉，返回否。
     */
    public static boolean refreshedOverstressed(KineticBlockEntity be) {
        if (!be.hasNetwork() || !IRotate.StressImpact.isEnabled() || !be.isOverStressed()) {
            return false;
        }
        be.getOrCreateNetwork().updateNetwork();
        return be.isOverStressed();
    }

    private CdgKineticOverload() {
    }
}
