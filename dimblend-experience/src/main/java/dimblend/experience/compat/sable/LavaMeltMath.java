package dimblend.experience.compat.sable;

/**
 * G6 岩浆熔毁的纯计算：硬度 → 熔毁进度速率。
 *
 * <p>用户拍板口径（2026-10-11）：熔毁速度 = 徒手挖掘的 {@value #SPEED_MULTIPLIER} 倍
 * （徒手 16 秒的方块被岩浆浇 4 秒熔掉）。徒手每 tick 进度沿用原版公式
 * {@code 1 / (hardness × divisor)}：不需要正确工具时 divisor=30、需要时 divisor=100
 * （Player#getDigSpeed 的 1/30 与 1/100 分支），熔毁速率同式乘 4。硬度决定时间，
 * 无阈值——黑曜石级硬度（50）需约 62.5 秒持续浇淋，等效抗岩浆；硬度 -1
 * （基岩、structure_void）不可熔。</p>
 */
public final class LavaMeltMath {

    /** 熔毁速度倍率（相对徒手）。 */
    public static final double SPEED_MULTIPLIER = 4.0D;
    /** 接触容差：熔岩格与方块投影体积的 AABB 距离不超过该值即视为"被岩浆浇着"。 */
    public static final double CONTACT_TOLERANCE = 0.25D;

    /** 是否可被岩浆熔毁：硬度负（基岩、structure_void、屏障等）免疫。 */
    public static boolean meltable(float destroySpeed) {
        return destroySpeed >= 0.0F;
    }

    /**
     * 每 tick 熔毁进度（累计到 1.0 熔毁）。硬度 0（火把、草丛类）即熔，
     * 返回 {@link Double#POSITIVE_INFINITY} 由调用方按当前 tick 立即熔毁处理。
     */
    public static double perTickProgress(float destroySpeed, boolean requiresCorrectToolForDrops) {
        if (destroySpeed < 0.0F) {
            return 0.0D;
        }
        if (destroySpeed == 0.0F) {
            return Double.POSITIVE_INFINITY;
        }
        double divisor = requiresCorrectToolForDrops ? 100.0D : 30.0D;
        return SPEED_MULTIPLIER / (destroySpeed * divisor);
    }

    /** 熔毁所需 tick 数（硬度 0 为 0，硬度负为无穷大），测试与文档口径核对用。 */
    public static double ticksToMelt(float destroySpeed, boolean requiresCorrectToolForDrops) {
        double perTick = perTickProgress(destroySpeed, requiresCorrectToolForDrops);
        return 1.0D / perTick;
    }

    private LavaMeltMath() {
    }
}
