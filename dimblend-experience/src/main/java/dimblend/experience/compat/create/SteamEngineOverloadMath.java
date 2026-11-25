package dimblend.experience.compat.create;

/**
 * 蒸汽引擎过载数值换算（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 调用方见 {@code SteamEngineOverload}（compat.create）与对应 mixin。
 */
public final class SteamEngineOverloadMath {

    /** 过载警告窗口：16 秒 × 20 tps。 */
    public static final int OVERLOAD_WINDOW_TICKS = 320;
    /** 警告音间隔：20 tick = 1 秒（overstress 素材 1.49 秒，近无缝衔接）。 */
    public static final int SOUND_INTERVAL_TICKS = 20;
    /** 警告粒子间隔：2 tick。 */
    public static final int PARTICLE_INTERVAL_TICKS = 2;
    /** 断轴排气总时长：32 秒 × 20 tps（2026-11-25 拍板，原 8 秒）。 */
    public static final int EXHAUST_DURATION_TICKS = 640;
    /** 排气音重播间隔：160 tick = 8 秒，0/160/320/480 共 4 次覆盖 32 秒。 */
    public static final int EXHAUST_SOUND_INTERVAL_TICKS = 160;
    /** 第一阶段云粒子：count=1、speed=0.05、delta 全 0。 */
    public static final int OVERLOAD_PARTICLE_COUNT = 1;
    public static final double OVERLOAD_PARTICLE_SPEED = 0.05D;
    /** 第二阶段云粒子：count=5、speed=0.1、delta 全 0。 */
    public static final int EXHAUST_PARTICLE_COUNT = 5;
    public static final double EXHAUST_PARTICLE_SPEED = 0.1D;

    /** 第一阶段每 tick 是否播粒子（overloadAge 含进入当 tick，从 0 起数）。 */
    public static boolean shouldEmitOverloadParticle(int overloadAgeTicks) {
        return overloadAgeTicks % PARTICLE_INTERVAL_TICKS == 0;
    }

    /** 第一阶段每 tick 是否播警告音（overloadAge 含进入当 tick，从 0 起数）。 */
    public static boolean shouldPlayOverloadSound(int overloadAgeTicks) {
        return overloadAgeTicks % SOUND_INTERVAL_TICKS == 0;
    }

    /** 过载年龄是否已满窗口（满则断轴，不再是第一阶段）。 */
    public static boolean windowElapsed(int overloadAgeTicks) {
        return overloadAgeTicks >= OVERLOAD_WINDOW_TICKS;
    }

    /** 第二阶段每 tick 是否播排气粒子（exhaustAge 含断轴当 tick，从 0 起数）。 */
    public static boolean shouldEmitExhaustParticle(int exhaustAgeTicks) {
        return exhaustAgeTicks % PARTICLE_INTERVAL_TICKS == 0;
    }

    /** 第二阶段是否播完（播满 640 tick = 32 秒即停）。 */
    public static boolean exhaustDone(int exhaustAgeTicks) {
        return exhaustAgeTicks >= EXHAUST_DURATION_TICKS;
    }

    /**
     * 第二阶段是否播排气音（32 秒里 4 次：age = 0 / 160 / 320 / 480）。
     *
     * <p>原 8 秒窗口只播 1 次；窗口拉到 32 秒后素材只有 1.49 秒，只播一次后面 30 秒是哑的，
     * 所以按 {@link #EXHAUST_SOUND_INTERVAL_TICKS} 重播，最后一次在 480（第 25 秒），
     * 到 640 收尾时尾巴刚好放完。</p>
     */
    public static boolean shouldPlayExhaustSound(int exhaustAgeTicks) {
        return exhaustAgeTicks >= 0
                && exhaustAgeTicks < EXHAUST_DURATION_TICKS
                && exhaustAgeTicks % EXHAUST_SOUND_INTERVAL_TICKS == 0;
    }

    private SteamEngineOverloadMath() {
    }
}
