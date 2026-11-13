package dimblend.experience.compat.cdg;

/**
 * 新生成区块的 CDG 原油储量折算纯函数（无 Minecraft / CDG 依赖，便于单测）。
 *
 * <p>规则（仅对安装本改动后新生成的区块生效，既有存档已生成区块不追溯）：
 * <ul>
 *   <li>取消自然生成的无限油田（原 {@code amount > infiniteThreshold -> Integer.MAX_VALUE} 改为按真实噪声储量继续折算）；</li>
 *   <li>有限油田（含原超无限阈值的噪声峰值）储量统一乘以 {@link #NEW_CHUNK_RESERVE_MULTIPLIER}（{@code 10%}），非零油田保底 {@code 1 mB}；</li>
 *   <li>原油探测器在新生成区块上的高储量分界线同步乘以 {@code 10%}，保持空/低/高三档反馈比例一致。</li>
 * </ul>
 */
public final class CdgOilBalance {

    public static final double NEW_CHUNK_RESERVE_MULTIPLIER = 0.10D;

    /**
     * 对新生成区块未截断前的原始储量（mB）做 10% 折算。
     *
     * @param rawAmount         去除了无限油田 {@code Integer.MAX_VALUE} 提升后的原始储量（mB）
     * @param infiniteThreshold CDG 配置的无限油田阈值（仅在外部插件如 KubeJS 直接硬编码返回 {@code Integer.MAX_VALUE} 时作兜底基数）
     */
    public static int scaleNewChunkBaseAmount(int rawAmount, int infiniteThreshold) {
        if (rawAmount <= 0) {
            return 0;
        }
        int finiteBase = rawAmount == Integer.MAX_VALUE
                ? Math.max(1, infiniteThreshold)
                : rawAmount;
        return Math.max(1, (int) Math.round(finiteBase * NEW_CHUNK_RESERVE_MULTIPLIER));
    }

    /**
     * 新生成区块已落库储量读取净化：保留管理员显式设定的 {@code Integer.MAX_VALUE}（{@code /cdg oil infinite}），
     * 但禁止普通正储量因高于 {@code OIL_CHUNK_INFINITE_THRESHOLD} 被自动抬升为无限油田。
     */
    public static int sanitizeStoredNewChunkAmount(int storedAmount) {
        return Math.max(0, storedAmount);
    }

    /**
     * 新生成区块上原油探测器的阈值同步乘以 10%。
     */
    public static int scaleNewChunkThreshold(int thresholdMb) {
        if (thresholdMb <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(thresholdMb * NEW_CHUNK_RESERVE_MULTIPLIER));
    }

    private CdgOilBalance() {
    }
}
