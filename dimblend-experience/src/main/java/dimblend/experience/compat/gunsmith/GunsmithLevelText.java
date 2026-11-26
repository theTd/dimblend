package dimblend.experience.compat.gunsmith;

/**
 * 枪械经验等级在 TaCZ 原生「等级」行上的<b>显示口径</b>（纯函数，无 UI / 无注册表依赖，可单测）。
 *
 * <p>背景（2026-11-26 取证）：TaCZ 的 {@code ClientGunTooltip} 直接拿 {@code IGun} 的等级 API 拼这一行，
 * 而它自己的 {@code ModernKineticGunItem#getLevel(int exp)} / {@code getMaxLevel()} 全是返回 0 的桩，
 * 于是 {@code 0 >= 0} 恒成立，任何枪都显示 {@code "0 (MAX)"}。本模组的真实等级写在
 * {@code custom_data -> DimBlendGunsmithLevel} 里，由 {@code GunsmithGunLevelTooltipMixin}
 * 覆写这一行，文本口径就锁在这里：</p>
 *
 * <ul>
 *   <li>{@code 0..49} → 只显示数字（如 {@code "0"}、{@code "49"}），<b>绝不出现 MAX</b>；</li>
 *   <li>{@code 50} → {@code "50 (MAX)"}（只有满级才 MAX）；</li>
 *   <li>越界值一律先 {@link GunsmithMath#clampLevel}（{@code -3 → "0"}、{@code 51 → "50 (MAX)"}）。</li>
 * </ul>
 */
public final class GunsmithLevelText {

    /** 满级后缀（与 TaCZ 原生 {@code "%d (MAX)"} 完全一致，含前导空格）。 */
    public static final String MAX_SUFFIX = " (MAX)";

    /** 该等级是否已到上限（只有上限才显示 MAX）。 */
    public static boolean isMax(int level) {
        return GunsmithMath.clampLevel(level) >= GunsmithMath.MAX_LEVEL;
    }

    /** 「等级」行里那个括号/数字部分（TaCZ 的 {@code tooltip.tacz.gun.level} 前缀由调用方拼）。 */
    public static String levelText(int level) {
        int clamped = GunsmithMath.clampLevel(level);
        return isMax(clamped) ? clamped + MAX_SUFFIX : Integer.toString(clamped);
    }

    private GunsmithLevelText() {
    }
}
