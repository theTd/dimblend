package dimblend.experience.compat.lava;

/**
 * 载入期岩浆结算的纯判定（不碰 Level、不做 IO，全部可在单测里跑）：
 * 轨道走廊带的几何（哪些格算「会流到轨道上」）、每区块与每 tick 的预算怎么切。
 *
 * <p>几何常量抄自 {@code dimblend.worldgen.OakTrackCorridor}（experience 模块不能 import
 * dimblend 模块的类，故此处各写一份并注明来源）：轨道在 {@code CORRIDOR_Z = 0}、
 * {@code TRACK_Y = 64}、隧道中段半宽 {@code VAULT_RADIUS = 9}、平顶在轨道上方
 * {@code VAULT_APEX_DY = 17} 格。结算带在 z 方向再各放 {@link #LAVA_REACH} 格：下界口径
 * 的岩浆单步最多横向铺 4 格（ultraWarm 下 {@code LavaFluid#getSlopeFindDistance} 为 4），
 * 所以只有 |z| ≤ 13 的岩浆可能流进隧道。</p>
 *
 * <p>y 方向：{@link #BAND_MIN_Y} 是轨道行本身（vault 地板 dy=-1 仍属地形，见
 * {@code OakTrackCorridor.VAULT_FLOOR_DY}，不算走廊内）；上界到平顶再上一格——顶盖开口
 * 上方的岩浆会直接灌进走廊，也要算在「会流到轨道上」里。</p>
 */
public final class LavaSettleMath {

    /** 单区块每轮结算最多处理的流体格数（T-10 DoD 常量）；超出部分顺延到下一轮。 */
    public static final int CELL_BUDGET = 64;

    /** 每 tick 最多消化的待结算区块数（单区块最坏 {@link #CELL_BUDGET} 格 → 每 tick ≤ 128 格）。 */
    public static final int CHUNKS_PER_TICK = 2;

    /**
     * 每 tick 所有活跃区块加起来最多跑多少个原版流体 step。
     *
     * <p><b>这是「卡顿上界」，不是「轮数上界」。</b> 2026-11-04 的实测（spark 与日志）表明：原来
     * 每区块固定 8 轮、每轮 1 tick，等于只把扩散链推进 8 格；而 ultraWarm 岩浆要铺到轨道并停住
     * 需要 20–30 格（`LavaFluid` 的 `getDropOff=1`、`getSlopeFindDistance=4`，垂直每步 1 格、
     * 水平每步 1 格，结算带高 19 格）。8 格之后剩下的部分就当着玩家的面按原版 10 tick/步流完，
     * 也就是用户报告的「本应被结算但还在流动」。
     *
     * <p>现在改为「跑到前沿不再增长为止」，代价由每 tick 的流体步预算封顶：一个区块一 tick 内
     * 可以跑多轮，只要总数不超过预算。128 步 ≈ 一条扩散链铺完的四五倍，正常 1–2 tick 收敛；这也是原来「2 区块 × 64 格」的每 tick 上限，没有把卡顿放大。
     */
    public static final int STEPS_PER_TICK = 128;

    /**
     * 单区块总共允许跑多少个原版流体 step（硬上界，与 {@link #STEPS_PER_TICK} 一起保证不会
     * 无限跑）。2 048 步是「收敛」与「卡死」之间的取舍：正常扩散链只需要几十步，用光这个数
     * 说明地形异常（例如岩浆一直在往新格子里漏），此时放弃并记一条调试日志。
     */
    public static final int STEP_BUDGET = 2048;

    /**
     * 单区块最多跑几轮。每轮至少推进 1 格前沿，所以它只是循环护栏；真正的界是
     * {@link #STEP_BUDGET} 与 {@link #STEPS_PER_TICK}。
     */
    public static final int PASS_BUDGET = 512;


    /** 轨道所在 z（OakTrackCorridor.CORRIDOR_Z）。 */
    public static final int TRACK_Z = 0;
    /** 轨道高程（OakTrackCorridor.TRACK_Y）。 */
    public static final int TRACK_Y = 64;
    /** 隧道中段半宽（OakTrackCorridor.VAULT_RADIUS，宽 19 = z[-9, +9]）。 */
    public static final int CORRIDOR_HALF_WIDTH = 9;
    /** 下界口径岩浆的一步水平可达格数（ultraWarm 的 getSlopeFindDistance）。 */
    public static final int LAVA_REACH = 4;
    /** 平顶在轨道上方的格数（OakTrackCorridor.VAULT_APEX_DY）。 */
    public static final int CORRIDOR_APEX_DY = 17;

    /** 结算带 z 下界（含）：隧道左壁再往外 LAVA_REACH 格。 */
    public static final int BAND_MIN_Z = TRACK_Z - CORRIDOR_HALF_WIDTH - LAVA_REACH;
    /** 结算带 z 上界（含）：隧道右壁再往外 LAVA_REACH 格。 */
    public static final int BAND_MAX_Z = TRACK_Z + CORRIDOR_HALF_WIDTH + LAVA_REACH;
    /** 结算带 y 下界（含）：轨道行；vault 地板 dy=-1 是地形，不扫。 */
    public static final int BAND_MIN_Y = TRACK_Y;
    /** 结算带 y 上界（含）：平顶 81 再上一格，接住顶盖开口上方的岩浆。 */
    public static final int BAND_MAX_Y = TRACK_Y + CORRIDOR_APEX_DY + 1;

    /** 预算切分结果：本次处理几格、丢弃几格（丢弃 = 不排队，只记一行 debug）。 */
    public record Plan(int processed, int dropped) {
        /** 没有要处理的格：调用方连日志都不该打。 */
        public boolean idle() {
            return processed == 0;
        }
    }

    /**
     * 一个区块一次结算的格预算：处理 {@code min(候选数, 预算)} 格，其余丢弃。
     * 负值按 0 处理（配置/调用方传错也不放大工作量）。
     */
    public static Plan plan(int candidates, int budget) {
        int cells = Math.max(0, candidates);
        int cap = Math.max(0, budget);
        int processed = Math.min(cells, cap);
        return new Plan(processed, cells - processed);
    }

    /** 本次 tick 从待结算队列里取几个区块：待处理再多也只取 {@link #CHUNKS_PER_TICK} 个。 */
    public static int chunksThisTick(int pending) {
        return Math.min(Math.max(0, pending), CHUNKS_PER_TICK);
    }

    /**
     * 本 tick 分给一个活跃区块的流体步预算：{@link #STEPS_PER_TICK} 在活跃区块之间均分，至少 1。
     *
     * <p>均分而不是「先到先得」，是为了让两辆车同时靠近两个不同区块时不会有一个被饿死。
     */
    public static int stepBudgetFor(int activeJobs) {
        if (activeJobs <= 0) {
            return STEPS_PER_TICK;
        }
        return Math.max(1, STEPS_PER_TICK / activeJobs);
    }

    /** 本轮之后还剩几轮；0 表示该区块结算结束、应出队（固定轮数，不是「跑到不动点」）。 */
    public static int passesLeft(int done, int budget) {
        return Math.max(0, Math.max(0, budget) - Math.max(0, done));
    }

    /** 该格是否落在轨道走廊结算带内（x 由调用方按区块范围限制）。 */
    public static boolean inBand(int y, int z) {
        return z >= BAND_MIN_Z && z <= BAND_MAX_Z && y >= BAND_MIN_Y && y <= BAND_MAX_Y;
    }

    /** 该区块（按区块 z）是否与结算带相交；不相交的区块连扫都不用扫。 */
    public static boolean chunkTouchesBand(int chunkZ) {
        return bandMinZ(chunkZ) <= bandMaxZ(chunkZ);
    }

    /** 区块内与结算带相交的 z 下界（含）。 */
    public static int bandMinZ(int chunkZ) {
        return Math.max(chunkZ << 4, BAND_MIN_Z);
    }

    /** 区块内与结算带相交的 z 上界（含）。 */
    public static int bandMaxZ(int chunkZ) {
        return Math.min((chunkZ << 4) + 15, BAND_MAX_Z);
    }

    private LavaSettleMath() {
    }
}
