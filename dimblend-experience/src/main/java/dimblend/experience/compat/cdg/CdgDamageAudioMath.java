package dimblend.experience.compat.cdg;

/**
 * 柴油机"活塞损坏"期间的偶发灭火音参数（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 *
 * <p>2026-11-26 口径：损坏期间不再有运转音（由 {@code IEngine#enabled()} 归零从源头掐掉），
 * 改由引擎自己"嘶——"一声灭火音，间隔 3~6 秒随机，音量 0.35~0.5、音调 0.85~1.15，
 * 既不刷屏也不至于让人以为机器彻底哑了。</p>
 *
 * <p>为什么用"随机 roll ∈ [0,1)"而不是直接吃 {@code RandomSource}：
 * 本类不允许依赖 MC（单测在纯 JVM 里跑），随机源由调用方给一个
 * {@code level.random.nextFloat()}。</p>
 */
public final class CdgDamageAudioMath {

    /** 灭火音间隔下限：60 tick = 3 秒。 */
    public static final int MIN_INTERVAL_TICKS = 60;

    /** 灭火音间隔上限：120 tick = 6 秒（含）。 */
    public static final int MAX_INTERVAL_TICKS = 120;

    /** 灭火音音量下限（自然音量，不刷屏）。 */
    public static final float MIN_VOLUME = 0.35F;

    /** 灭火音音量上限。 */
    public static final float MAX_VOLUME = 0.5F;

    /** 灭火音音调下限（略低，像一团闷住的火被浇熄）。 */
    public static final float MIN_PITCH = 0.85F;

    /** 灭火音音调上限（含）。 */
    public static final float MAX_PITCH = 1.15F;

    /** 间隔档数：60..120 共 61 档。 */
    private static final int INTERVAL_BANDS = MAX_INTERVAL_TICKS - MIN_INTERVAL_TICKS + 1;

    /**
     * 下一次灭火音还要等多少 tick（60~120，含两端）。
     *
     * @param roll01 {@code level.random.nextFloat()} 的返回值（约定 [0,1)）；
     *               越界值按端点夹紧，便于单测直接喂 0.0 / 1.0
     */
    public static int nextIntervalTicks(float roll01) {
        int band = (int) Math.floor(clamp01(roll01) * (double) INTERVAL_BANDS);
        if (band >= INTERVAL_BANDS) {
            band = INTERVAL_BANDS - 1;
        }
        return MIN_INTERVAL_TICKS + band;
    }

    /** 灭火音音量（0.35~0.5）。 */
    public static float volume(float roll01) {
        return MIN_VOLUME + clamp01(roll01) * (MAX_VOLUME - MIN_VOLUME);
    }

    /** 灭火音音调（0.85~1.15）。 */
    public static float pitch(float roll01) {
        return MIN_PITCH + clamp01(roll01) * (MAX_PITCH - MIN_PITCH);
    }

    /**
     * 冷却推进 1 tick：>0 递减，到 0 表示"这一 tick 该响"。
     *
     * @return 推进后的剩余 tick（0 = 立即响，调用方响完再 {@link #nextIntervalTicks}）
     */
    public static int tickDown(int ticksLeft) {
        return ticksLeft > 0 ? ticksLeft - 1 : 0;
    }

    private static float clamp01(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return value >= 1.0F ? 1.0F : value;
    }

    private CdgDamageAudioMath() {
    }
}
