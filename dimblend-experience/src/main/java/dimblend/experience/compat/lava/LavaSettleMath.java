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

    /** 单区块每轮结算最多处理的流体格数（T-10 DoD 常量）；超出部分丢弃，不排队。 */
    public static final int CELL_BUDGET = 64;

    /** 每 tick 最多消化的待结算区块数（单区块最坏 {@link #CELL_BUDGET} 格 → 每 tick ≤ 128 格）。 */
    public static final int CHUNKS_PER_TICK = 2;

    /**
     * 每区块最多跑几轮结算（固定轮数，不检查收敛）：每轮 ≤ {@link #CELL_BUDGET} 格、间隔 1 tick。
     * 最坏 {@code 8 × 64 = 512} 个原版流体 step / 区块，摊在 8 tick 上。
     */
    public static final int PASS_BUDGET = 8;

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
