package dimblend.blocks.compat.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * 跨车厢线缆几何的自检：端点朝向偏移、下垂采样、世界→相机双精度差值、亮度合并。
 *
 * <p>这几条都是"渲染位置不对/线缆看不见"的直接成因，但渲染链路本身跑不进 GameTest，只能靠这里钉住。</p>
 */
class TrainTubeCableGeometryTest {

    private static final double EPSILON = 1.0E-9;

    @Test
    void endpointIsPushedTowardPartnerAlongDominantAxis() {
        Vec3 center = new Vec3(10.5, 20.5, 30.5);
        // 对端在 +X：推到管口表面（0.25），只在 X 上动
        Vec3 plusX = TrainTubeCableGeometry.endpoint(center, center.add(4.0, 0.0, 0.0));
        assertEquals(center.x + TrainTubeCableGeometry.ENDPOINT_OFFSET, plusX.x, EPSILON);
        assertEquals(center.y, plusX.y, EPSILON);
        assertEquals(center.z, plusX.z, EPSILON);
        // 对端在 -Z
        Vec3 minusZ = TrainTubeCableGeometry.endpoint(center, center.add(0.0, 0.0, -3.0));
        assertEquals(center.z - TrainTubeCableGeometry.ENDPOINT_OFFSET, minusZ.z, EPSILON);
        assertEquals(center.x, minusZ.x, EPSILON);
        // 对端在 +Y，但 Y 分量不如 X 大 -> 走 X
        Vec3 mostlyX = TrainTubeCableGeometry.endpoint(center, center.add(2.0, 1.0, 0.0));
        assertEquals(center.x + TrainTubeCableGeometry.ENDPOINT_OFFSET, mostlyX.x, EPSILON);
        assertEquals(center.y, mostlyX.y, EPSILON);
    }

    @Test
    void endpointOffsetNeverLeavesTheBlock() {
        Vec3 center = new Vec3(-7.5, 0.5, 90.5);
        for (Vec3 partner : new Vec3[] {
                center.add(32.0, 0.0, 0.0), center.add(-32.0, 0.0, 0.0),
                center.add(0.0, 32.0, 0.0), center.add(0.0, -32.0, 0.0),
                center.add(0.0, 0.0, 32.0), center.add(0.0, 0.0, -32.0)}) {
            Vec3 endpoint = TrainTubeCableGeometry.endpoint(center, partner);
            assertTrue(Math.abs(endpoint.x - center.x) <= 0.5 + EPSILON);
            assertTrue(Math.abs(endpoint.y - center.y) <= 0.5 + EPSILON);
            assertTrue(Math.abs(endpoint.z - center.z) <= 0.5 + EPSILON);
            assertEquals(TrainTubeCableGeometry.ENDPOINT_OFFSET, endpoint.distanceTo(center), EPSILON);
        }
    }

    @Test
    void coincidentEndpointsStayPut() {
        Vec3 center = new Vec3(1.5, 2.5, 3.5);
        assertEquals(center, TrainTubeCableGeometry.endpoint(center, center));
    }

    @Test
    void sagGrowsWithDistanceAndIsCapped() {
        assertEquals(0.0, TrainTubeCableGeometry.sag(0.0), EPSILON);
        assertEquals(0.0, TrainTubeCableGeometry.sag(-3.0), EPSILON);
        assertTrue(TrainTubeCableGeometry.sag(4.0) < TrainTubeCableGeometry.sag(8.0));
        assertEquals(TrainTubeCableGeometry.MAX_SAG, TrainTubeCableGeometry.sag(1000.0), EPSILON);
    }

    @Test
    void cablePointsKeepBothEndsAndDipOnlyInTheMiddle() {
        Vec3 from = new Vec3(0.0, 10.0, 0.0);
        Vec3 to = new Vec3(8.0, 10.0, 0.0);
        Vec3[] points = TrainTubeCableGeometry.cablePoints(from, to, 4);
        assertEquals(5, points.length);
        assertEquals(from.x, points[0].x, EPSILON);
        assertEquals(from.y, points[0].y, EPSILON);
        assertEquals(from.z, points[0].z, EPSILON);
        assertEquals(to.x, points[4].x, EPSILON);
        assertEquals(to.y, points[4].y, EPSILON);
        assertEquals(to.z, points[4].z, EPSILON);
        // 中点垂得最低，且低于两端；垂幅恰好等于 sag(8)（抛物线 4t(1-t) 在 t=0.5 处为 1）
        assertTrue(points[2].y < points[0].y);
        assertTrue(points[2].y < points[1].y);
        assertEquals(10.0 - TrainTubeCableGeometry.sag(8.0), points[2].y, EPSILON);
    }

    /**
     * §8.47 的红线：世界坐标必须在 double 上减相机，绝不能先转 float。
     * 20,480,000 量级的 float ULP = 2.0，先转 float 会把 0.25 的差值直接舍成 0。
     */
    @Test
    void cameraRelativeKeepsSubBlockPrecisionAtPlotMagnitude() {
        Vec3 camera = new Vec3(20_480_000.5, 0.0, -20_480_000.5);
        Vec3 world = new Vec3(20_480_000.75, 2.0, -20_480_000.25);
        Vec3 relative = TrainTubeCableGeometry.cameraRelative(world, camera);
        assertEquals(0.25, relative.x, EPSILON);
        assertEquals(2.0, relative.y, EPSILON);
        assertEquals(0.25, relative.z, EPSILON);
        // 反例：先按 float 精度取整再相减会得到 0
        assertEquals(0.0, (float) world.x - (float) camera.x, EPSILON);
    }

    @Test
    void brightestLightPrefersTheLitSample() {
        assertEquals(0x00F000F0, TrainTubeCableGeometry.brightestLight(0x00000000, 0x00F000F0, 0x00050010));
        assertEquals(0x00F000F0, TrainTubeCableGeometry.brightestLight(0x00F000F0, 0x00000000, 0x00050010));
        assertEquals(0x00050010, TrainTubeCableGeometry.brightestLight(0x00000000, 0x00050010, 0x00010000));
    }

    @Test
    void drawableDistanceWindow() {
        assertFalse(TrainTubeCableGeometry.isDrawable(0.0));
        assertTrue(TrainTubeCableGeometry.isDrawable(0.004));
        assertTrue(TrainTubeCableGeometry.isDrawable(16.0));
        assertTrue(TrainTubeCableGeometry.isDrawable(TrainTubeCableGeometry.MAX_DRAW_DISTANCE));
        assertFalse(TrainTubeCableGeometry.isDrawable(TrainTubeCableGeometry.MAX_DRAW_DISTANCE + 1.0));
    }
}
