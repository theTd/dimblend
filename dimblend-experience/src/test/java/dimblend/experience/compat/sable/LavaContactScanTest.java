package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * 岩浆接触扫描的柱顶口径（见 {@link LavaContactScan}）：只有世界柱最高方块多收上方一格，
 * 非柱顶方块不多收（2026-10-31 修：原先每个方块都多收，灌满后舱内液面会把地板/任意高度
 * 侧壁一并判成"被浇着"，同轮一起熔毁）。
 */
class LavaContactScanTest {

    /** 1 格方块（中心 5.5、半 extent 0.5、容差 0.25）的保守覆盖格上界为 6。 */
    private static final double CENTER = 5.5D;
    private static final double HALF = 0.5D;
    private static final double EXPAND = 0.25D;

    @Test
    void onlyColumnTopGetsOneExtraCellAboveItsCoverage() {
        // 非柱顶：最多扫到自己的保守覆盖格（上界 6）——再上一格隔着上方方块/空位
        assertEquals(6, LavaContactScan.scanMaxY(CENTER, HALF, EXPAND, 319, false));
        // 柱顶：多收一格到 7（熔岩停在柱顶空位上一格的真实液面）
        assertEquals(7, LavaContactScan.scanMaxY(CENTER, HALF, EXPAND, 319, true));
    }

    @Test
    void scanRangeClampsToWorldHeight() {
        assertEquals(319, LavaContactScan.scanMaxY(320.5D, HALF, EXPAND, 319, true));
        assertEquals(-64, LavaContactScan.scanMinY(-62.5D, HALF, EXPAND, -64));
        // 未夹取的原始口径：下界 = floor(中心 − 半 extent − 容差)
        assertEquals(-63, LavaContactScan.scanMinY(-62.0D, HALF, EXPAND, -128));
    }

    @Test
    void blockCellYFloorsTowardsNegativeInfinity() {
        // 与体素化 floor(中心±半 extent) 同一取整口径：负坐标不能截断成 0
        assertEquals(5, LavaContactScan.blockCellY(5.5D));
        assertEquals(-60, LavaContactScan.blockCellY(-59.5D));
        assertEquals(-61, LavaContactScan.blockCellY(-60.5D));
    }

    @Test
    void floorUnderRoofIsNotColumnTop() {
        Long2IntOpenHashMap tops = LavaContactScan.columnTops();
        // 地板（中心 y=2.5）与正上方屋顶（中心 y=5.5）覆盖同一组世界柱 (2,2)…(3,3)
        LavaContactScan.raiseCoveredColumns(tops, 2.5D, 2.5D, 2.5D, HALF, HALF);
        LavaContactScan.raiseCoveredColumns(tops, 2.5D, 5.5D, 2.5D, HALF, HALF);
        assertTrue(LavaContactScan.isCoveredColumnTop(tops, 2.5D, 5.5D, 2.5D, HALF, HALF),
                "roof must own the column tops");
        assertFalse(LavaContactScan.isCoveredColumnTop(tops, 2.5D, 2.5D, 2.5D, HALF, HALF),
                "floor shielded by the roof must not inherit the extra contact cell");
    }

    @Test
    void lowerBlockBecomesColumnTopAfterTheUpperOneMeltsAway() {
        Long2IntOpenHashMap tops = LavaContactScan.columnTops();
        // 只登记下方方块 = "上方那块已经熔掉"的下一轮扫描：它成为新的柱顶
        LavaContactScan.raiseCoveredColumns(tops, 2.5D, 2.5D, 2.5D, HALF, HALF);
        assertTrue(LavaContactScan.isCoveredColumnTop(tops, 2.5D, 2.5D, 2.5D, HALF, HALF),
                "the highest remaining block of the column must get the extra contact cell");
        assertEquals(2, LavaContactScan.blockCellY(2.5D));
    }

    @Test
    void columnsAreIndependentInXZ() {
        Long2IntOpenHashMap tops = LavaContactScan.columnTops();
        // 柱 A 的高块只在 x=2..3 / z=2..3；柱 B 的低块在另一个位置
        LavaContactScan.raiseCoveredColumns(tops, 2.5D, 9.5D, 2.5D, HALF, HALF);
        LavaContactScan.raiseCoveredColumns(tops, 8.5D, 3.5D, 8.5D, HALF, HALF);
        assertTrue(LavaContactScan.isCoveredColumnTop(tops, 2.5D, 9.5D, 2.5D, HALF, HALF));
        assertTrue(LavaContactScan.isCoveredColumnTop(tops, 8.5D, 3.5D, 8.5D, HALF, HALF));
    }

    @Test
    void partiallyCoveredBlockIsTopInOneOfItsColumns() {
        Long2IntOpenHashMap tops = LavaContactScan.columnTops();
        // 一块跨 (0,0)…(1,1) 四柱；另一块只压在 x=1 那一列上且更高
        LavaContactScan.raiseCoveredColumns(tops, 0.5D, 4.5D, 0.5D, HALF, HALF);
        LavaContactScan.raiseCoveredColumns(tops, 1.5D, 6.5D, 0.5D, HALF, HALF);
        // 低块在 (0,0) 柱仍是最高方块 → 享受 +1（另一列被高块压着不影响它自己露出的一角）
        assertTrue(LavaContactScan.isCoveredColumnTop(tops, 0.5D, 4.5D, 0.5D, HALF, HALF));
        assertTrue(LavaContactScan.isCoveredColumnTop(tops, 1.5D, 6.5D, 0.5D, HALF, HALF));
    }

    @Test
    void missingColumnNeverMatches() {
        Long2IntOpenHashMap tops = LavaContactScan.columnTops();
        assertEquals(Integer.MIN_VALUE, tops.get(VoidFitColumnTops.columnKey(5, 5)));
        // 缺柱缺省值 MIN_VALUE：y=0 的合法高度也不会误判成柱顶
        assertFalse(LavaContactScan.isCoveredColumnTop(tops, 0.5D, 0.5D, 0.5D, HALF, HALF));
    }

    @Test
    void negativeCoordinatesAreKeyedIndependently() {
        Long2IntOpenHashMap tops = LavaContactScan.columnTops();
        LavaContactScan.raiseCoveredColumns(tops, -9.5D, -20.5D, 4.5D, HALF, HALF);
        LavaContactScan.raiseCoveredColumns(tops, -9.5D, 8.5D, -4.5D, HALF, HALF);
        // 中心 -20.5 的方块所属格 = floor(-20.5) = -21；-4.5 的方块覆盖 z=-5/-4 两柱
        assertEquals(-21, tops.get(VoidFitColumnTops.columnKey(-10, 4)));
        assertEquals(8, tops.get(VoidFitColumnTops.columnKey(-10, -5)));
    }
}
