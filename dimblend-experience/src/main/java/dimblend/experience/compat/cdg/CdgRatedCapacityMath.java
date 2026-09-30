package dimblend.experience.compat.cdg;

/**
 * B7 爬梯/波动只改转速、应力容量恒按额定（用户拍板 2026-09-30；纯函数，零 Minecraft/NeoForge
 * 依赖，可单元测试）。调用方见两处 CDG mixin。
 *
 * <p>Create 按"每转容量 × |产出转速|"记源容量（{@code KineticNetwork.getActualCapacityOf}）。
 * CDG 1.3.15 的每转容量按额定转速折算，爬梯/波动把产出转速压低后，总 SU 随之按比例缩水：
 * 单机时应力同比缩、比值不变，多台同网（网速取最快一台，其余按自身较低转速计容量）或下游
 * 转速控制器（应力不随柴油机转速变）就会真过载。这里把折算分母换成实际转速，使总 SU 恒为
 * 额定值——与 CDG 模拟调速（throttle）下总 SU 不随转速变的原版口径一致。</p>
 */
public final class CdgRatedCapacityMath {

    /** CDG 每转容量公式分母下限（{@code Math.max(0.01F, speed)}）。 */
    public static final float MIN_DIVISOR = 0.01F;

    /**
     * 普通/组合式：CDG 每转容量 = 额定总 SU / max(0.01, 额定转速)，本函数给出替换后的分母。
     * 产出转速非零时取 max(0.01, |产出转速|)，每转容量 × |产出转速| 恒等于额定总 SU；
     * 产出为 0（停机/闩锁）或非数时保留原分母。
     */
    public static float capacityDivisor(float ratedDivisor, float generatedSpeed) {
        float actual = Math.abs(generatedSpeed);
        if (!(actual > 0.0F)) {
            return ratedDivisor;
        }
        return Math.max(MIN_DIVISOR, actual);
    }

    /**
     * 巨型机：轴总容量 = Σ 各引擎每转容量 × 轴转速（轴转速取引擎表里最快一台）。
     * 本机传给轴的每转容量按 额定/轴转速 放大，使本机贡献 = 每转容量 × max(额定, 轴转速)：
     * 轴转速低于本机额定（爬梯/波动）时补足到额定；高于额定（混烧时另一台更快）时
     * 保留原版"随轴转速计"的口径，不低于现状。轴转速或额定非正/非数时原样返回。
     */
    public static float shaftCapacityPerRpm(float capacityPerRpm, float ratedSpeed, float shaftSpeed) {
        float rated = Math.abs(ratedSpeed);
        float shaft = Math.abs(shaftSpeed);
        if (!(shaft > 0.0F) || !(rated > shaft)) {
            return capacityPerRpm;
        }
        return capacityPerRpm * rated / shaft;
    }

    private CdgRatedCapacityMath() {
    }
}
