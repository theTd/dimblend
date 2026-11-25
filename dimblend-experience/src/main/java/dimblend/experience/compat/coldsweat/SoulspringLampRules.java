package dimblend.experience.compat.coldsweat;

/**
 * 灵魂之泉灯（Soulspring Lamp）容量 256、强度下限 0.80、三段火焰阈值（LOCKED DECISION #15）。
 *
 * <p>全类零 Minecraft 依赖（不用 {@code Mth}，等价数学自己写），这样单测不需要 bootstrap。</p>
 *
 * <p>为什么不改 {@code coldsweat/item.toml}：那份配置是用户自己的（燃料表、维度白名单、
 * 强度倍率都能改）。本 mod 的规则是"<b>至少</b> 256 容量、<b>至少</b> 0.80 强度"，
 * 通过 max/min 夹紧实现——用户把强度调得更高照样生效，调低则被抬到 0.80。</p>
 */
public final class SoulspringLampRules {

    /** 新容量（原版 64）。 */
    public static final double CAPACITY = 256.0D;

    /** 旧容量：tooltip 宽度换算、老存档夹紧都用它作参照。 */
    public static final double LEGACY_CAPACITY = 64.0D;

    /** 强度下限（原版默认 0.6 → 0.80，20% 泄漏，约 369°F 天花板）。 */
    public static final double MIN_STRENGTH = 0.80D;

    /** Cold Sweat 的出厂强度（item.toml 的默认值），只用于说明与单测对照。 */
    public static final double VANILLA_STRENGTH = 0.6D;

    /** 三段火焰（3D 渲染的 0/1/2/3 贴图）阈值。 */
    public static final double FLAME_TIER_3 = 171.0D;

    public static final double FLAME_TIER_2 = 85.0D;

    /** 燃料条最大像素宽度（原版 30）。 */
    public static final int TOOLTIP_MAX_WIDTH = 30;

    /** 燃料夹紧到 {@code [0, CAPACITY]}：任何写入路径（NBT、addFuel、合成默认值）都该过一遍。 */
    public static double clampFuel(double fuel) {
        if (Double.isNaN(fuel)) {
            return 0.0D;
        }
        return Math.min(CAPACITY, Math.max(0.0D, fuel));
    }

    /** 强度下限：配置值不低于 {@link #MIN_STRENGTH}。 */
    public static double effectiveStrength(double configured) {
        return Math.max(configured, MIN_STRENGTH);
    }

    /**
     * 燃料条像素宽度：{@code fuel * 30 / 256}，四舍五入后夹紧 {@code [0, 30]}。
     *
     * <p>原版按 64 容量画（{@code fuel / 2.1333}），容量翻 4 倍后必须同步换分母
     * （{@code 256 / 30 = 8.533333}）；否则 256 容量会画出 120px 的条直接溢出 tooltip。</p>
     */
    public static int tooltipWidth(double fuel) {
        int width = (int) Math.round(clampFuel(fuel) * TOOLTIP_MAX_WIDTH / CAPACITY);
        return Math.min(TOOLTIP_MAX_WIDTH, Math.max(0, width));
    }

    /** 三段火焰：{@code >171 → 3}、{@code >85 → 2}、其余 {@code 1}（熄灭时 0，由点灯位决定）。 */
    public static int flameTier(double fuel) {
        double clamped = clampFuel(fuel);
        if (clamped > FLAME_TIER_3) {
            return 3;
        }
        if (clamped > FLAME_TIER_2) {
            return 2;
        }
        return 1;
    }

    /** 物品模型属性 {@code cold_sweat:soulspring_state} 的返回值：0=灭，1/2/3=三段火焰。 */
    public static float modelState(boolean lit, double fuel) {
        return lit ? flameTier(fuel) : 0.0F;
    }

    /** 新容量下的 tooltip 分母（供 mixin 的 {@code @Constant} 引用）。 */
    public static final double TOOLTIP_DIVISOR = CAPACITY / TOOLTIP_MAX_WIDTH;

    private SoulspringLampRules() {
    }
}
