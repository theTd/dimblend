package dimblend.experience.compat.simurail;

/**
 * E10/E11 扳手充能的判定口径（纯函数，供单测钉死）。
 *
 * <ul>
 * <li><b>冲能即断钩</b>：充能态的自动车钩按 Simurail 的「已通电」语义处理——
 * 不再寻找伙伴，已有伙伴在物理步里被解除。因此 {@code isPowered()} 对充能态返回真。</li>
 * <li><b>自动取消充能</b>：车钩处于充能态、确实已断钩（没有伙伴），并且半径内
 * 没有另一只车钩时，充能自动结束——它已经不再隔开任何东西，留着只会妨碍下次连挂。</li>
 * </ul>
 *
 * <p>半径取 2 格，与 Simurail {@code AutomaticCouplerBlockEntity#findPartner}
 * 的搜索半径一致：连挂判定用的就是这个距离，取消充能也用它才自洽。</p>
 */
public final class CouplerChargeMath {

    /** 另一只车钩能维持充能的半径（格）。 */
    public static final double NEIGHBOUR_RADIUS = 2.0D;

    /** 充能态在 BE NBT 里的键（落盘与更新包共用同一个 write/read）。 */
    public static final String TAG_WRENCH_CHARGED = "DimBlendWrenchCharged";

    /**
     * @param wrenchCharged     当前是否处于扳手充能态
     * @param hasPartner        是否仍挂着另一只车钩（有链接即未断钩）
     * @param otherCouplerNear  半径内是否存在另一只车钩
     * @return 是否应当取消充能
     */
    public static boolean shouldDecharge(boolean wrenchCharged, boolean hasPartner, boolean otherCouplerNear) {
        return wrenchCharged && !hasPartner && !otherCouplerNear;
    }

    /** 距离平方与半径的比较，避免在扫描里反复开方。 */
    public static boolean isWithin(double distanceSquared, double radius) {
        return distanceSquared <= radius * radius;
    }

    private CouplerChargeMath() {
    }
}