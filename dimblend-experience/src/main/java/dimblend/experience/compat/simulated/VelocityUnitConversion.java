package dimblend.experience.compat.simulated;

import java.util.Locale;

/**
 * 速度传感器显示连接器的两种换算单位（2026-11-26 slice-1 #1A）。
 *
 * <p>口径来源（运行 jar {@code simulated-neoforge-1.21.1-1.3.0.jar}，嵌在
 * {@code create-aeronautics-bundled-1.21.1-1.3.0.jar} 的 {@code META-INF/jarjar} 里）：
 * {@code dev.simulated_team.simulated.content.display_sources.VelocitySensorDisplaySource#provideLine}
 * 字节码偏移 43 起——选择值 {@code getInt("VeclotySensorSelection") == 0} 时输出
 * {@code SimLang.number(Math.abs(vbe.getAdjustedVelocity())).text(" m/s")}。
 * 也就是说原版 m/s 口径是<b>取绝对值</b>（正负方向被藏掉），数值走
 * {@code SimLang#number} → Catnip {@code LangNumberFormat} 的默认格式。</p>
 *
 * <p>本类只做换算与格式化，不引用 simulated / Create 的任何类型，因此可以纯单测。
 * {@code v} 一律取 {@code VelocitySensorBlockEntity#getAdjustedVelocity()} 的<b>带符号</b>
 * 原始值（float，正值 = 沿传感器朝向轴的 {@code dot} 为正），新单位按需求保留正负方向：
 * 负值带负号，正值不额外加正号。原 m/s 模式不受影响，仍是绝对值。</p>
 */
public final class VelocityUnitConversion {

    /** 1 m/s = 3.6 km/h。 */
    public static final double KMH_PER_MPS = 3.6D;

    /** 1 m/s = 3600 / 1609.344 mph（= 2.2369362920544025）。 */
    public static final double MPH_PER_MPS = 3600.0D / 1609.344D;

    public static final String KMH_UNIT = "km/h";
    public static final String MPH_UNIT = "mph";

    public static double kilometresPerHour(double metresPerSecond) {
        return metresPerSecond * KMH_PER_MPS;
    }

    public static double milesPerHour(double metresPerSecond) {
        return metresPerSecond * MPH_PER_MPS;
    }

    /** {@code "12.3 km/h"} / {@code "-12.3 km/h"}。 */
    public static String kilometresPerHourText(double metresPerSecond) {
        return format(metresPerSecond, KMH_PER_MPS, KMH_UNIT);
    }

    /** {@code "7.6 mph"} / {@code "-7.6 mph"}。 */
    public static String milesPerHourText(double metresPerSecond) {
        return format(metresPerSecond, MPH_PER_MPS, MPH_UNIT);
    }

    /**
     * 换算后只保留一位小数（{@code Locale.ROOT}，不随客户端语言变小数点）、保留符号、单位在后。
     *
     * <p>两个必须显式处理的边界：</p>
     * <ul>
     *   <li>{@code -0.04 m/s} 按 {@code %.1f} 会写成 {@code "-0.0"}——这里归一成 {@code "0.0"}，
     *       避免显示成"负零"；</li>
     *   <li>非有限值（子层级位姿异常时的 {@code NaN}/{@code Infinity}）直接落 {@code 0.0}，
     *       不让 {@code "NaN km/h"} 出现在显示屏上。</li>
     * </ul>
     */
    public static String format(double metresPerSecond, double factor, String unit) {
        double converted = metresPerSecond * factor;
        if (!Double.isFinite(converted)) {
            converted = 0.0D;
        }
        String value = String.format(Locale.ROOT, "%.1f", converted);
        if ("-0.0".equals(value)) {
            value = "0.0";
        }
        return value + " " + unit;
    }

    private VelocityUnitConversion() {
    }
}
