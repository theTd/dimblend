package dimblend.experience.compat.cca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 跨 Sable 子层级线缆几何（{@link CrossSableWireGeometry}）单元测试：
 * 2px 见方截面、CCA 下垂曲线、环心居中、端点贴合，以及"跨子层级"判据。
 */
class CrossSableWireGeometryTest {

    private static final float EPS = 1.0E-5F;

    private static final float WIDTH = 0.125F; // 2 / 16 格 = 2 像素

    // ---------------------------------------------------------------- 常数

    @Test
    void halfWidthIsExactlyTwoPixels() {
        assertEquals(0.0625F, CrossSableWireGeometry.HALF_WIDTH);
        assertEquals(1.0F / 16.0F, CrossSableWireGeometry.HALF_WIDTH);
        assertEquals(2.0F / 16.0F, 2.0F * CrossSableWireGeometry.HALF_WIDTH);
        assertEquals(WIDTH, 2.0F * CrossSableWireGeometry.HALF_WIDTH);
        assertEquals(24, CrossSableWireGeometry.SEGMENTS);
        assertEquals(12, CrossSableWireGeometry.FLOATS_PER_RING);
    }

    // ---------------------------------------------------------------- 曲线

    @Test
    void centerlineMatchesCcaCurveAndEndpoints() {
        float x = 4.0F;
        float y = 2.0F;
        float z = -3.0F;
        float dis = 6.0F;
        float maxLength = 16.0F;
        float[] points = CrossSableWireGeometry.centerline(x, y, z, dis, maxLength);

        assertEquals((CrossSableWireGeometry.SEGMENTS + 1) * 3, points.length);

        // P[0] = (0,0,0)；P[24] = (x,y,z)（两端下垂为 0）
        assertPointEquals(0.0F, 0.0F, 0.0F, points, 0);
        assertPointEquals(x, y, z, points, CrossSableWireGeometry.SEGMENTS);

        // 任意分段：fx = x·part、fz = z·part，fy 走 CCA 的抛物线 + hang
        for (int index = 0; index <= CrossSableWireGeometry.SEGMENTS; index++) {
            float part = (float) index / (float) CrossSableWireGeometry.SEGMENTS;
            float expectedY = (y > 0.0F
                    ? y * part * part
                    : y - y * (1.0F - part) * (1.0F - part))
                    + CrossSableWireGeometry.hang(part, dis, maxLength);
            assertPointEquals(x * part, expectedY, z * part, points, index);
        }

        // y < 0 走另一支：y − y(1−part)²
        float[] descending = CrossSableWireGeometry.centerline(1.0F, -2.0F, 0.0F, 4.0F, 16.0F);
        int mid = 12;
        float part = 0.5F;
        assertEquals(-2.0F - (-2.0F) * (1.0F - part) * (1.0F - part)
                        + CrossSableWireGeometry.hang(part, 4.0F, 16.0F),
                descending[mid * 3 + 1], EPS);
    }

    @Test
    void hangUsesCcaFormulaAndFallsBackToDefaultLength() {
        float part = 0.25F;
        float expected = (float) Math.sin(-part * (float) Math.PI) * (0.5F * 8.0F / 16.0F);
        assertEquals(expected, CrossSableWireGeometry.hang(part, 8.0F, 16.0F), EPS);

        // maxLength <= 0 回落到 16（CCA 配置默认值）
        assertEquals(CrossSableWireGeometry.hang(part, 8.0F, 16.0F),
                CrossSableWireGeometry.hang(part, 8.0F, 0.0F), 0.0F);
        assertEquals(CrossSableWireGeometry.hang(part, 8.0F, 16.0F),
                CrossSableWireGeometry.hang(part, 8.0F, -5.0F), 0.0F);

        // 两端归零、中点最深（向 −Y 垂）
        assertEquals(0.0F, CrossSableWireGeometry.hang(0.0F, 8.0F, 16.0F), EPS);
        assertEquals(0.0F, CrossSableWireGeometry.hang(1.0F, 8.0F, 16.0F), 1.0E-6F);
        assertEquals(-0.25F, CrossSableWireGeometry.hang(0.5F, 8.0F, 16.0F), EPS);
    }

    // ---------------------------------------------------------------- 截面

    @Test
    void crossSectionIsASquareOfExactPixelWidthForEveryOrientation() {
        // 水平（+X）、水平斜线（3-4-5）、纯竖直、任意斜向
        assertSquareCrossSection(4.0F, 0.0F, 0.0F, 8.0F, 16.0F);
        assertSquareCrossSection(3.0F, 0.0F, 4.0F, 5.0F, 16.0F);
        assertSquareCrossSection(0.0F, 5.0F, 0.0F, 5.0F, 16.0F);
        assertSquareCrossSection(2.0F, 3.0F, 1.0F, 6.0F, 16.0F);
    }

    @Test
    void horizontalWiresKeepExactVerticalHeightAndHorizontalWidth() {
        // 纯水平：截面在 Y 与横向法线方向各 0.125
        assertAxisExtents(4.0F, 0.0F, 0.0F);
        // 水平斜线：竖直高度 0.125，沿水平法线 (−dz,0,dx)/len 的宽度 0.125
        assertAxisExtents(3.0F, 0.0F, 4.0F);
    }

    @Test
    void verticalWireKeepsExactTwoPixelSquareInXAndZ() {
        float[] mesh = CrossSableWireGeometry.mesh(0.0F, 5.0F, 0.0F, 5.0F, 16.0F);
        for (int ring = 0; ring <= CrossSableWireGeometry.SEGMENTS; ring++) {
            int base = ring * CrossSableWireGeometry.FLOATS_PER_RING;
            assertEquals(WIDTH, extent(mesh, base, 0), EPS, "ring " + ring + " X 宽度");
            assertEquals(WIDTH, extent(mesh, base, 2), EPS, "ring " + ring + " Z 宽度");
        }
    }

    @Test
    void zeroLengthWireStaysFiniteAndDoesNotExplode() {
        float[] mesh = CrossSableWireGeometry.mesh(0.0F, 0.0F, 0.0F, 0.0F, 16.0F);
        assertEquals((CrossSableWireGeometry.SEGMENTS + 1) * CrossSableWireGeometry.FLOATS_PER_RING,
                mesh.length);
        for (float value : mesh) {
            assertTrue(Float.isFinite(value), "退化线缆不允许 NaN/Infinity: " + value);
        }
    }

    // ---------------------------------------------------------------- 跨层级判据

    @Test
    void crossSubLevelMatchesAnyBoundaryBetweenDistinctSpaces() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertTrue(CrossSableWireGeometry.isCrossSubLevel(first, second),
                "载具 A ↔ 载具 B 属于跨 Sable");
        assertTrue(CrossSableWireGeometry.isCrossSubLevel(second, first));
        assertTrue(CrossSableWireGeometry.isCrossSubLevel(null, second),
                "真实世界 ↔ 载具也跨越 Sable 边界");
        assertTrue(CrossSableWireGeometry.isCrossSubLevel(first, null),
                "载具 ↔ 真实世界也跨越 Sable 边界");
        assertFalse(CrossSableWireGeometry.isCrossSubLevel(first, first),
                "同一载具内部不换粗线");
        assertFalse(CrossSableWireGeometry.isCrossSubLevel(null, null),
                "真实世界 ↔ 真实世界不换粗线");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 每个环都是边长 0.125 的正方形：4 条边等长、对角线 = 0.125·√2、环心落在中心线上、
     * 环平面与切线垂直。
     */
    private static void assertSquareCrossSection(float x, float y, float z, float dis,
                                                 float maxLength) {
        float[] points = CrossSableWireGeometry.centerline(x, y, z, dis, maxLength);
        float[] directions = CrossSableWireGeometry.tangents(points);
        float[] mesh = CrossSableWireGeometry.mesh(x, y, z, dis, maxLength);
        assertEquals((CrossSableWireGeometry.SEGMENTS + 1) * CrossSableWireGeometry.FLOATS_PER_RING,
                mesh.length);

        float diagonal = (float) Math.sqrt(2.0) * WIDTH;
        for (int ring = 0; ring <= CrossSableWireGeometry.SEGMENTS; ring++) {
            int base = ring * CrossSableWireGeometry.FLOATS_PER_RING;

            // 环心 = 中心线采样点（没有向上偏移）
            float cx = 0.0F;
            float cy = 0.0F;
            float cz = 0.0F;
            for (int corner = 0; corner < 4; corner++) {
                cx += mesh[base + corner * 3];
                cy += mesh[base + corner * 3 + 1];
                cz += mesh[base + corner * 3 + 2];
            }
            cx /= 4.0F;
            cy /= 4.0F;
            cz /= 4.0F;
            assertEquals(points[ring * 3], cx, EPS, "ring " + ring + " 环心 X");
            assertEquals(points[ring * 3 + 1], cy, EPS, "ring " + ring + " 环心 Y");
            assertEquals(points[ring * 3 + 2], cz, EPS, "ring " + ring + " 环心 Z");

            // 4 条边等长；对角线 √2 倍
            for (int corner = 0; corner < 4; corner++) {
                int from = base + corner * 3;
                int to = base + ((corner + 1) % 4) * 3;
                assertEquals(WIDTH, distance(mesh, from, to), EPS,
                        "ring " + ring + " 边 " + corner);
            }
            assertEquals(diagonal, distance(mesh, base, base + 2 * 3), EPS,
                    "ring " + ring + " 对角线");

            // 每个角点相对环心都垂直于切线（截面真正垂直于线缆）
            for (int corner = 0; corner < 4; corner++) {
                float dx = mesh[base + corner * 3] - cx;
                float dy = mesh[base + corner * 3 + 1] - cy;
                float dz = mesh[base + corner * 3 + 2] - cz;
                float dot = dx * directions[ring * 3]
                        + dy * directions[ring * 3 + 1]
                        + dz * directions[ring * 3 + 2];
                assertEquals(0.0F, dot, EPS, "ring " + ring + " corner " + corner + " 切线点积");
            }
        }
    }

    /**
     * 水平类线缆（无垂度，切线水平）：Y 向高度必须正好 0.125；横向宽度沿水平法线
     * （纯 X 向时是 Z、斜线时是 {@code (−dz,0,dx)} 归一化）也正好 0.125。
     *
     * <p>有垂度时截面随切线倾斜，轴对齐极差按 {@code cos(垂角)} 略小于 2px；
     * 边长恒为 0.125 的"正方性"由 {@link #crossSectionIsASquareOfExactPixelWidthForEveryOrientation}
     * 覆盖。</p>
     */
    private static void assertAxisExtents(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + z * z);
        float nx = -z / length;
        float nz = x / length;
        float[] mesh = CrossSableWireGeometry.mesh(x, y, z, 0.0F, 16.0F);
        for (int ring = 0; ring <= CrossSableWireGeometry.SEGMENTS; ring++) {
            int base = ring * CrossSableWireGeometry.FLOATS_PER_RING;
            assertEquals(WIDTH, extent(mesh, base, 1), EPS, "ring " + ring + " 高度");
            assertEquals(WIDTH, projectedExtent(mesh, base, nx, nz), EPS,
                    "ring " + ring + " 横向宽度");
        }
    }

    /** 第 {@code axis} 个坐标轴上的极差。 */
    private static float extent(float[] mesh, int base, int axis) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float value = mesh[base + corner * 3 + axis];
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    /** 水平方向 {@code (nx, 0, nz)} 上的投影极差。 */
    private static float projectedExtent(float[] mesh, int base, float nx, float nz) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float value = mesh[base + corner * 3] * nx + mesh[base + corner * 3 + 2] * nz;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    private static float distance(float[] mesh, int from, int to) {
        float dx = mesh[to] - mesh[from];
        float dy = mesh[to + 1] - mesh[from + 1];
        float dz = mesh[to + 2] - mesh[from + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void assertPointEquals(float x, float y, float z, float[] points, int index) {
        assertEquals(x, points[index * 3], EPS, "point " + index + " X");
        assertEquals(y, points[index * 3 + 1], EPS, "point " + index + " Y");
        assertEquals(z, points[index * 3 + 2], EPS, "point " + index + " Z");
    }
}
