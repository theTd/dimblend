package dimblend.experience.compat.cdg;

/**
 * B3 柴油机额定波动输出取整（2026-10-16 窗子拍板「取个整吧」；纯函数，零依赖）：
 * 到额定后转速在 80%~100% 额定间随机跳变（系数抽取在 tick 状态机内不变），
 * 最终输出四舍五入到整数 rpm——rated × 0.8~1.0 原会产生小数（如 204.8）。
 *
 * <p>不超额定论证：波动系数恒 &lt; 1（{@code FLUCT_MIN + random×(1-FLUCT_MIN)} ∈
 * [0.8, 1.0)）且 CDG 额定转速为整数 rpm，乘积 &lt; rated → 取整结果 ≤ rated。</p>
 */
public final class CdgFluctuationMath {

    /** 波动输出：rated × fluctFactor 四舍五入到整数 rpm（结果 ∈ [round(0.8×rated), rated]）。 */
    public static float fluctuatedSpeed(float rated, float fluctFactor) {
        return Math.round(rated * fluctFactor);
    }

    private CdgFluctuationMath() {
    }
}
