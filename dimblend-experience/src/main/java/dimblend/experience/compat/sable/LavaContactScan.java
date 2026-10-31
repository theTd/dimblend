package dimblend.experience.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * 岩浆接触扫描的几何口径（纯计算，无可变状态）：投影 AABB 外扩
 * {@link LavaMeltMath#CONTACT_TOLERANCE} 的格范围，以及"只有世界柱最高方块才多收上方
 * 一格"的柱顶判定。
 *
 * <p>柱顶 +1 格的由来（2026-10-26）：体素化按 {@code floor(中心 ± 半 extent)} 取保守
 * 覆盖格，一个方块在水平面上覆盖 1-2 格（scale=1 时每轴 2 格）。熔岩停在柱顶空位上方，
 * 其格面比方块 AABB 顶面最多高近一格，只按 AABB+0.25 扫描会把"浇在车顶上"的熔岩漏判，
 * 车顶就永远等不到第一次接触。</p>
 *
 * <p>只有柱顶享受 +1（2026-10-31 用户实机缺陷）：原先每个方块都多收一格，岩浆灌进舱内
 * 后液面正好落在"非柱顶方块保守覆盖格的再上一格"，于是地板（隔着 +1 的 structure_void）、
 * 任意高度的侧壁都被判成"被浇着"；同材质方块共享同轮 {@code elapsed}，一起到点一起熔
 * ——用户看到的是整根岩浆柱切进车里、从上到下同时融毁。非柱顶方块上方压着更高的方块或
 * 它的空位，多收的那格根本不可能有岩浆，收紧后不改变任何合法写入。</p>
 *
 * <p>柱顶按世界 (x,z) 柱判定、取该柱最高方块：一块跨多柱的方块只要在任一柱里是最高，
 * 就享受 +1（它露出的一角确实是液面接触面）；等高的方块并列柱顶，都享受 +1。</p>
 */
public final class LavaContactScan {

    /** 新建柱顶索引：缺省 {@link Integer#MIN_VALUE}，缺柱永不命中（y=0 的合法格也不会误判）。 */
    public static Long2IntOpenHashMap columnTops() {
        Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
        tops.defaultReturnValue(Integer.MIN_VALUE);
        return tops;
    }

    /** 该方块中心所在的世界格 y（柱内比较用；与体素化 {@code floor(中心)} 同一取整口径）。 */
    public static int blockCellY(double wy) {
        return (int) Math.floor(wy);
    }

    /** 接触扫描的下界（含）：AABB 下沿外扩 {@code expand} 的格，夹到世界最低层。 */
    public static int scanMinY(double wy, double hy, double expand, int minBuildHeight) {
        return Math.max(minBuildHeight, (int) Math.floor(wy - hy - expand));
    }

    /**
     * 接触扫描的上界（含）：AABB 上沿外扩 {@code expand} 的格；仅柱顶方块再向上多收一
     * 格（熔岩停在柱顶保守覆盖格之上的真实液面），夹到世界最高层。非柱顶方块不再多收。
     */
    public static int scanMaxY(double wy, double hy, double expand, int maxBuildHeight, boolean columnTop) {
        int top = (int) Math.floor(wy + hy + expand);
        if (columnTop) {
            top = Math.max(top, (int) Math.floor(wy + hy) + 1);
        }
        return Math.min(maxBuildHeight, top);
    }

    /**
     * 把该方块投影覆盖的每个世界柱的最高方块 y 抬到该方块。方块属于哪个柱按水平保守
     * 覆盖 {@code floor(wx ± hx)} × {@code floor(wz ± hz)} 取（与体素化同口径），
     * 高度按 {@link #blockCellY} 取。
     */
    public static void raiseCoveredColumns(Long2IntOpenHashMap tops,
            double wx, double wy, double wz, double hx, double hz) {
        int y = blockCellY(wy);
        int x0 = (int) Math.floor(wx - hx);
        int x1 = (int) Math.floor(wx + hx);
        int z0 = (int) Math.floor(wz - hz);
        int z1 = (int) Math.floor(wz + hz);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                long column = VoidFitColumnTops.columnKey(x, z);
                if (y > tops.get(column)) {
                    tops.put(column, y);
                }
            }
        }
    }

    /** 该方块是否是它所覆盖的任一世界柱的最高方块（只有柱顶享受 +1 格接触）。 */
    public static boolean isCoveredColumnTop(Long2IntMap tops,
            double wx, double wy, double wz, double hx, double hz) {
        int y = blockCellY(wy);
        int x0 = (int) Math.floor(wx - hx);
        int x1 = (int) Math.floor(wx + hx);
        int z0 = (int) Math.floor(wz - hz);
        int z1 = (int) Math.floor(wz + hz);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (tops.get(VoidFitColumnTops.columnKey(x, z)) == y) {
                    return true;
                }
            }
        }
        return false;
    }

    private LavaContactScan() {
    }
}
