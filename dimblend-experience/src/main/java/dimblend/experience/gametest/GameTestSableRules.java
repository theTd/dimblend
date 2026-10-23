package dimblend.experience.gametest;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.LavaMeltMath;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Sable 相关 GameTest 的共享配置覆写（引用计数）。vanilla GameTest 只并发执行
 * 同一 batch 内的用例，不同 batch 由 GameTestRunner 串行推进。同 batch 用例若各自
 * "保存原值 → 设测试值 → 结束时还原"，先结束者会把仍在运行用例的共享配置拨回，
 * 因此共享键仍统一走引用计数。
 *
 * <p>本类把"所有用例取值一致"的键收敛为引用计数：首个 {@link #acquire} 快照原值
 * 并应用测试值，最后一个 {@link #release} 才还原——套件中途无人拨回。用例在测试
 * 中段对某个键的临时翻转（对照用例）不受 acquire/release 影响，但须自己翻回来。</p>
 *
 * <p>口径冲突的键不纳入：{@code LIMITED_WATER} 在 limited_water 用例（要开）与
 * 载具用例（要关，防 G3 改写挡水用例的水）之间取值互斥，仍按各类私有
 * 保存/还原处理（既有低概率互踩原样保留，见 requirements-spec 复核记录）。</p>
 */
public final class GameTestSableRules {

    private static int depth;
    private static String savedRotatingId;
    private static boolean savedVoidFit;
    private static int savedRefitTicks;
    private static int savedVoidFitScanBudget;
    private static boolean savedLavaMelt;
    private static double savedLavaMeltSpeed;
    private static int savedLavaMeltScanBudget;

    /** 进入一条 Sable 用例：首个进入者快照原值并应用测试值（可重入，后续进入者不改值）。 */
    public static synchronized void acquire() {
        if (depth++ == 0) {
            savedRotatingId = Config.ROTATING_DIMENSION_ID.get();
            savedVoidFit = Config.SABLE_STRUCTURE_VOID_FIT.get();
            savedRefitTicks = Config.SABLE_VOID_FIT_REFIT_TICKS.get();
            savedVoidFitScanBudget = Config.SABLE_VOID_FIT_SCAN_BUDGET.get();
            savedLavaMelt = Config.SABLE_LAVA_MELT.get();
            savedLavaMeltSpeed = Config.SABLE_LAVA_MELT_SPEED.get();
            savedLavaMeltScanBudget = Config.SABLE_LAVA_MELT_SCAN_BUDGET.get();
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
            Config.SABLE_STRUCTURE_VOID_FIT.set(true);
            Config.SABLE_VOID_FIT_REFIT_TICKS.set(1);
            Config.SABLE_LAVA_MELT.set(true);
            // 熔毁用例的时序断言按默认倍率（4 倍徒手）写就，钉住默认值使套件与服务器配置无关
            Config.SABLE_LAVA_MELT_SPEED.set(LavaMeltMath.DEFAULT_SPEED_MULTIPLIER);
            // 扫描预算钉满：避免同 batch 载具或上轮残留载具让扫描间隔随数量线性放大
            Config.SABLE_VOID_FIT_SCAN_BUDGET.set(64);
            Config.SABLE_LAVA_MELT_SCAN_BUDGET.set(64);
        }
    }

    /** 退出一条 Sable 用例：最后退出者还原套件前的原值。 */
    public static synchronized void release() {
        if (depth == 0 || --depth > 0) {
            return;
        }
        Config.ROTATING_DIMENSION_ID.set(savedRotatingId);
        Config.SABLE_STRUCTURE_VOID_FIT.set(savedVoidFit);
        Config.SABLE_VOID_FIT_REFIT_TICKS.set(savedRefitTicks);
        Config.SABLE_VOID_FIT_SCAN_BUDGET.set(savedVoidFitScanBudget);
        Config.SABLE_LAVA_MELT.set(savedLavaMelt);
        Config.SABLE_LAVA_MELT_SPEED.set(savedLavaMeltSpeed);
        Config.SABLE_LAVA_MELT_SCAN_BUDGET.set(savedLavaMeltScanBudget);
    }

    private GameTestSableRules() {
    }

    private static final long AWAIT_TIMEOUT_NANOS = 10_000_000_000L;
    /** 必须低于用例的 24000-tick 框架超时，保证诊断和清理不会被 vanilla 抢先截断。 */
    private static final int AWAIT_MAX_POLLS = 20_000;
    private static final int AWAIT_STABLE_TICKS = 3;
    private static final double SETTLED_VELOCITY_SQR = 1.0E-8D;

    /** 拟合已写入不代表物理体已静止；后续定点浇淋/放水前还要等速度收敛。 */
    static boolean vehicleSettled(ServerSubLevel subLevel) {
        return subLevel.latestLinearVelocity.lengthSquared() <= SETTLED_VELOCITY_SQR
                && subLevel.latestAngularVelocity.lengthSquared() <= SETTLED_VELOCITY_SQR;
    }

    /**
     * 每 tick 轮询异步就绪，谓词连续 3 tick 为真才回调。区块兑现受墙钟影响，
     * 故设 10 秒墙钟上限；同时设 20000 次轮询上限，确保比 24000-tick 框架超时
     * 先取得现场转储并执行清理。固定 15 tick 不能作为区块就绪窗口。
     */
    public static void await(GameTestHelper helper, String what, BooleanSupplier ready,
            Supplier<String> dump, Runnable cleanup, Runnable onReady) {
        await(helper, what, ready, dump, cleanup, onReady, System.nanoTime(), 0, 0);
    }

    private static void await(GameTestHelper helper, String what, BooleanSupplier ready,
            Supplier<String> dump, Runnable cleanup, Runnable onReady, long startNanos, int streak, int polls) {
        long elapsedNanos = System.nanoTime() - startNanos;
        if (elapsedNanos > AWAIT_TIMEOUT_NANOS || polls >= AWAIT_MAX_POLLS) {
            String diagnostic = dump.get();
            cleanup.run();
            helper.fail(what + " not ready after " + polls + " polls / "
                    + elapsedNanos / 1_000_000_000.0D + "s; " + diagnostic);
            return;
        }
        int nextStreak = ready.getAsBoolean() ? streak + 1 : 0;
        if (nextStreak >= AWAIT_STABLE_TICKS) {
            onReady.run();
            return;
        }
        int carried = nextStreak;
        helper.runAfterDelay(1, () -> await(helper, what, ready, dump, cleanup, onReady, startNanos, carried, polls + 1));
    }

}
