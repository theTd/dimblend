package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 外框绘制距离裁剪（T-09）：相机到岩浆方块中心的 3D 欧氏距离超过 64 格时不再画描边。
 * 只测纯函数；真正的绘制链路（{@code HeadlightLavaHighlight}）要开游戏才看得到。
 */
class LavaOutlineCullTest {
    private static final float SOURCE = 8 / 9.0F;
    /** 方块 (0,0,0) 的顶面中心：x+0.5、y+height、z+0.5，与 {@link HeadlightLavaMath#surfaceDistanceSqr} 同一取样点。 */
    private static final Vec3 ORIGIN_CENTRE = new Vec3(0.5, SOURCE, 0.5);

    @Test
    void drawDistanceIsSixtyFourBlocks() {
        assertEquals(64.0, HeadlightLavaMath.MAX_DRAW_DISTANCE, 1e-12);
        LavaSurface surface = new LavaSurface(0, 0, 0, SOURCE);
        // 三个轴各自 63.9 格：都保留。
        assertTrue(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 63.9, 0, 0)));
        assertTrue(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 63.9, 0)));
        assertTrue(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 0, 63.9)));
        // 三个轴各自 64.1 格：都剔除。
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 64.1, 0, 0)));
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 64.1, 0)));
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 0, 64.1)));
        // 正好 64.0 格算在范围内（边界用 <=）。
        assertTrue(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 64.0, 0, 0)));
    }

    @Test
    void verticalOffsetCountsAsThreeDimensionalDistance() {
        LavaSurface surface = new LavaSurface(0, 0, 0, SOURCE);
        // 同一列（x/z 相同）的正上方：垂直距离同样计入，不能只算水平距离。
        assertTrue(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 63.9, 0)));
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 64.1, 0)));
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, -64.1, 0)));
        // 水平 63.9 + 垂直 63.9 = 90.4 格：只算水平会保留，3D 欧氏必须剔除。
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 63.9, 63.9, 0)));
        assertFalse(HeadlightLavaMath.withinDrawDistance(surface, offset(ORIGIN_CENTRE, 0, 63.9, 63.9)));
    }

    @Test
    void culledListKeepsScanOrderAndTheBoundaryBlock() {
        Vec3 camera = ORIGIN_CENTRE;
        LavaSurface near = new LavaSurface(10, 0, 0, SOURCE);
        LavaSurface boundary = new LavaSurface(64, 0, 0, SOURCE);
        LavaSurface beyond = new LavaSurface(65, 0, 0, SOURCE);
        // 顺序保持扫描顺序，不按距离重排（排序是 capped 的事）。
        assertEquals(List.of(near, boundary),
                HeadlightLavaMath.withinDrawDistance(List.of(beyond, near, boundary), camera));
        // 空入空出。
        assertTrue(HeadlightLavaMath.withinDrawDistance(List.of(), camera).isEmpty());
        // 全在范围内时原样返回（不复制），避免每次重扫都白建一份列表。
        List<LavaSurface> all = List.of(near, boundary);
        assertSame(all, HeadlightLavaMath.withinDrawDistance(all, camera));
    }

    @Test
    void farBlocksContributeNoOutlineSegment() {
        Vec3 camera = ORIGIN_CENTRE;
        LavaSurface near = new LavaSurface(3, 8, 3, SOURCE);
        LavaSurface beyond = new LavaSurface(3 + 65, 8, 3, SOURCE);
        List<LavaSurface> culled = HeadlightLavaMath.withinDrawDistance(List.of(near, beyond), camera);
        // 远处方块一根线都不该出现在外框里：裁剪后的外框与「只有近处方块」完全一致。
        assertEquals(LavaBoxOutline.edges(List.of(near)), LavaBoxOutline.edges(culled));

        // 生产顺序（先裁剪、再套 1024 面上限）：上限只作用在 64 格内，远处的不会挤掉近处的。
        List<LavaSurface> scanned = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            scanned.add(new LavaSurface(100 + i, 8, 0, SOURCE));
        }
        scanned.add(near);
        List<LavaSurface> lit = HeadlightLavaMath.capped(
                HeadlightLavaMath.withinDrawDistance(scanned, camera), camera, HeadlightLavaMath.MAX_SURFACES);
        assertEquals(List.of(near), lit);
    }

    private static Vec3 offset(Vec3 from, double dx, double dy, double dz) {
        return new Vec3(from.x + dx, from.y + dy, from.z + dz);
    }
}
