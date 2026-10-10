package dimblend.experience.gametest;

import dimblend.experience.Config;

/**
 * Sable 相关 GameTest 的共享配置覆写（引用计数）。vanilla GameTest 在同一世界内
 * 并发执行不同 batch 的用例——每个用例"保存原值 → 设测试值 → 结束时还原"的私有
 * 模式在并发下互相踩踏：先结束者的还原会把仍在运行的用例的维度门/功能开关拨回
 * 生产值，门控一关，在途用例的载具再也等不到拟合/熔毁，成片误败。
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
    private static boolean savedLavaMelt;

    /** 进入一条 Sable 用例：首个进入者快照原值并应用测试值（可重入，后续进入者不改值）。 */
    public static synchronized void acquire() {
        if (depth++ == 0) {
            savedRotatingId = Config.ROTATING_DIMENSION_ID.get();
            savedVoidFit = Config.SABLE_STRUCTURE_VOID_FIT.get();
            savedRefitTicks = Config.SABLE_VOID_FIT_REFIT_TICKS.get();
            savedLavaMelt = Config.SABLE_LAVA_MELT.get();
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
            Config.SABLE_STRUCTURE_VOID_FIT.set(true);
            Config.SABLE_VOID_FIT_REFIT_TICKS.set(1);
            Config.SABLE_LAVA_MELT.set(true);
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
        Config.SABLE_LAVA_MELT.set(savedLavaMelt);
    }

    private GameTestSableRules() {
    }
}
