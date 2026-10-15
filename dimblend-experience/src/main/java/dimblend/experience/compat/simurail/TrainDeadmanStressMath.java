package dimblend.experience.compat.simurail;

/**
 * E7' 无人值守刹车锁存的强制网络过载反算（纯函数，零依赖）。
 *
 * <p>意图（2026-10-15 窗子拍板）：锁存要保持"掉下车=事故"而非"主动跳车=免费自动
 * 停车"——锁存期间让驱动列车前进的 Create 网络过载，触发动力源（CDG 柴油机）自身
 * 过载惩罚（B6：2 秒确认警告 → 4 秒引信 → 爆炸）。</p>
 *
 * <p>口径：Create 网络实际应力需求 = 缓存 impact × |理论转速|；强制 impact 按网络
 * 当前总容量反算，保证需求 > 容量即过载。容量与需求都随转速正比缩放，故倍率口径
 * 跨转速恒定（约 1.5 倍）。</p>
 */
public final class TrainDeadmanStressMath {

    /** 锁存瞬间无网络的兜底强制 impact（窗子原数 65536，见 spec E7'）。 */
    public static final float FALLBACK_IMPACT = 65536.0F;
    /** 强制需求目标倍率：需求目标 = 容量 × 1.5 + 1（+1 保证零容量网络同样过载）。 */
    public static final float OVERLOAD_FACTOR = 1.5F;

    /**
     * 按网络当前总容量反算强制 impact：{@code (capacity × 1.5 + 1) / max(|speed|, 1)}。
     * {@code max(..., 1)} 防零转速把 impact 放到无穷——锁存时网络若停转，impact 按
     * 1 倍转速档反算，一旦启动（|speed|≥1）需求即 = 容量 × 1.5 + 1，立即过载。
     */
    public static float forcedImpact(float networkCapacity, float theoreticalSpeed) {
        return (networkCapacity * OVERLOAD_FACTOR + 1.0F) / Math.max(Math.abs(theoreticalSpeed), 1.0F);
    }

    private TrainDeadmanStressMath() {
    }
}
