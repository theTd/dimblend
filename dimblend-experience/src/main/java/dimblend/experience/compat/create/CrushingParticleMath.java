package dimblend.experience.compat.create;

/**
 * Create 粉碎轮粒子在 Sable plot 坐标系下的生成坐标精度修正（纯算术，零三方类型依赖）。
 *
 * <h2>根因：Create 以 {@code float} 计算粒子生成点</h2>
 * <p>Create 6.0.10 的两处粉碎轮粒子发射点都把方块整数坐标先转成 {@code float} 再加随机抖动：</p>
 * <ul>
 *   <li>{@code CrushingWheelControllerBlock#animateTick}（运行时字节码 {@code 30..43}）：
 *       {@code (double) ((float) pos.getX() + rand.nextFloat())} → {@code i2f / fadd / f2d}；</li>
 *   <li>{@code CrushingWheelControllerBlockEntity#spawnParticles}（运行时字节码 {@code 89..116}）：
 *       {@code worldPosition.getX() + r.nextFloat()} 同样是 {@code i2f / fadd / f2d}。</li>
 * </ul>
 * <p>在真实世界坐标里这不明显；但 Sable 子层级的方块活在 plot 坐标系里，
 * {@code plotPos * (1 << 7) chunks * 16} 且 {@code DEFAULT_ORIGIN = 10000}
 * （{@code SubLevelContainer}），于是 plot 方块坐标从 {@code 20,480,000} 起
 * （本仓既有常量 {@code RewardWagonPlotScanTest.PLOT_X}）。该数量级已越过 {@code 2^24 = 16,777,216}，
 * {@code float} 的 ULP 升到 <b>2.0</b>：</p>
 * <ul>
 *   <li>{@code (float) 20_480_001} = {@code 20_480_000} 或 {@code 20_480_002} → 整数本身就被挪了最多 1 格；</li>
 *   <li>再 {@code + rand.nextFloat()}（{@code < 1}，小于半个 ULP）一定舍回同一个偶数 →
 *       <b>子格抖动被完全吞掉</b>，四颗粒子全部落在同一个整数格角上。</li>
 * </ul>
 * <p>结果正好是玩家描述的「每次都会错位 / 位置每次会固定，只有挪动轮子才会换个地方 /
 * 摆的位置不同错位的相对位置也不同 / 就没见到能在正确位置的」：错位量是
 * {@code roundToEven(plotX) - plotX}，只由方块在 plot 里的坐标决定。</p>
 *
 * <h2>修法</h2>
 * <p>越过 {@link #SUB_BLOCK_LIMIT} 的轴一律走 {@link #exactJitteredCoordinate}——整数先升 {@code double}
 * 再加 {@code float} 抖动（{@code double + float} 在 {@code double} 里算，不丢位）。阈值以下保持
 * Create 原样，普通世界的行为与随机数消耗逐位不变。</p>
 */
public final class CrushingParticleMath {

    /**
     * 从这个绝对值起，{@code float} 的 ULP 不小于 1，{@code [0,1)} 的子格抖动已无法表出
     * （{@code 2^23 = 8,388,608}，对应 {@code Math.ulp((float) v) == 1.0f}）。
     */
    public static final int SUB_BLOCK_LIMIT = 1 << 23;

    /**
     * 三个轴中是否有任意一轴已经落在「{@code float} 表示不了子格抖动」的区间。
     *
     * @param x 方块 X（plot 坐标）
     * @param y 方块 Y
     * @param z 方块 Z（plot 坐标）
     * @return true 表示必须用 {@link #exactJitteredCoordinate} 生成粒子坐标
     */
    public static boolean needsExactDouble(int x, int y, int z) {
        return losesSubBlockJitter(x) || losesSubBlockJitter(y) || losesSubBlockJitter(z);
    }

    /**
     * 该整数坐标经 {@code (float)} 往返后是否已经丢失 {@code [0,1)} 的子格抖动。
     *
     * @param coordinate 方块整数坐标
     * @return {@code |coordinate| >= 2^23} 时为 true
     */
    public static boolean losesSubBlockJitter(int coordinate) {
        return Math.abs((long) coordinate) >= SUB_BLOCK_LIMIT;
    }

    /**
     * 修正后的生成坐标：整数先升 {@code double}，再加抖动（抖动本身是 {@code float}，其 24 位精度在
     * {@code double} 里被完整保留）。
     *
     * <p>与 Create 的差别只在 {@code (float)} 那一步：{@code double} 在
     * {@code |v| < 2^52} 的整数上精确，{@code + float} 的误差不超过 {@code 2^-52} 格。</p>
     *
     * @param coordinate 方块整数坐标
     * @param jitter     {@code RandomSource#nextFloat()} 的返回值
     * @return 修正后的坐标
     */
    public static double exactJitteredCoordinate(int coordinate, float jitter) {
        return (double) coordinate + jitter;
    }

    /**
     * 复刻 Create 的口径（{@code i2f / fadd / f2d}），只用于单测与诊断对比，生产路径不使用。
     *
     * @param coordinate 方块整数坐标
     * @param jitter     {@code RandomSource#nextFloat()} 的返回值
     * @return Create 实际生成出来的坐标
     */
    public static double quantizedJitteredCoordinate(int coordinate, float jitter) {
        return (float) coordinate + jitter;
    }

    private CrushingParticleMath() {
    }
}
