package dimblend.blocks.compat.create.funnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.Vec3;

/**
 * 卸料漏斗"对齐 Create 6.0.10 的纯算术/纯判定"自检。
 *
 * <p>覆盖三态修复里真正会看错的地方：</p>
 * <ul>
 *   <li>过滤器图标位置/可见面/朝向必须按传送带漏斗分支走（否则延展态图标被吞、方向被立起来）；</li>
 *   <li>门帘偏移必须按形态取值（否则门帘糊在皮带条上）；</li>
 *   <li>形态更新必须保住延展态（否则扳手延展后被邻居变化打回收回态）；</li>
 *   <li>护目镜提示行首必须有 4 空格给图标让位。</li>
 * </ul>
 */
class DumpFunnelLayoutTest {

    private static final double EPS = 1.0e-6;
    private static final DumpFunnelLayout.Shape NO_BELT = null;

    // ---------------------------------------------------------------- 护目镜缩进

    @Test
    void goggleGutterIsFourSpacesToClearTheIcon() {
        assertEquals(4, DumpFunnelLayout.GOGGLE_GUTTER.length(), "catnip forGoggles 默认字体下是 4 个空格（16px）");
        assertTrue(DumpFunnelLayout.GOGGLE_GUTTER.isBlank());
        assertFalse(DumpFunnelLayout.GOGGLE_GUTTER.contains("\t"), "必须是空格，制表符宽度随字体变");
    }

    @Test
    void goggleLineKeepsKeyAndPrependsTheGutter() {
        Component line = DumpFunnelLayout.goggleLine("dimblend_blocks.dump_funnel.no_filter");

        assertTrue(line.getContents() instanceof PlainTextContents.LiteralContents, "首段必须是字面量缩进");
        assertEquals(DumpFunnelLayout.GOGGLE_GUTTER, ((PlainTextContents.LiteralContents) line.getContents()).text());

        assertEquals(1, line.getSiblings().size(), "缩进之后只能接一段可翻译文本");
        assertTrue(line.getSiblings().get(0).getContents() instanceof TranslatableContents);
        assertEquals("dimblend_blocks.dump_funnel.no_filter",
                ((TranslatableContents) line.getSiblings().get(0).getContents()).getKey());
    }

    // ---------------------------------------------------------------- 图标位置

    @Test
    void extendedSlotSitsOnTheExtendedShellInsteadOfInsideTheBody() {
        // 朝向 SOUTH 时旋转角为 0，可以逐字比对 Create 的 voxelSpace(8, 15.5, 13)
        Vec3 offset = DumpFunnelLayout.slotOffset(Direction.UP, Direction.SOUTH, DumpFunnelLayout.Shape.EXTENDED);

        assertEquals(8 / 16.0, offset.x, EPS);
        assertEquals(15.5 / 16.0, offset.y, EPS);
    }

    @Test
    void extendedSlotIsPushedOutwardPastTheBlockCenter() {
        Vec3 retracted = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.SOUTH,
                DumpFunnelLayout.Shape.RETRACTED);
        Vec3 extended = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.SOUTH,
                DumpFunnelLayout.Shape.EXTENDED);

        // Create 的收回态 voxelSpace(8, 13, 7.5)：贴在下缘、靠中间
        assertEquals(8 / 16.0, retracted.x, EPS);
        assertEquals(13 / 16.0, retracted.y, EPS);
        // 延展态更高、更靠"喷口"那一端；差这么远才不会藏进方块体内
        assertTrue(extended.y > retracted.y, "延展态图标必须抬到外壳顶上");
        assertTrue(Math.abs(extended.z - 0.5) > Math.abs(retracted.z - 0.5), "延展态图标必须推出中心");
    }

    @Test
    void perpendicularSlotUsesTheSideAngle() {
        Vec3 offset = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.SOUTH,
                DumpFunnelLayout.Shape.PUSHING);

        // Create 的 PULLING/PUSHING 分支：voxelSpace(8, 12, 8.675) 按"面"的角旋转
        assertEquals(8 / 16.0, offset.x, EPS);
        assertEquals(12 / 16.0, offset.y, EPS);
        assertEquals(8.675 / 16.0, offset.z, EPS);
    }

    @Test
    void plainFunnelKeepsCreatesHorizontalBodySlot() {
        Vec3 horizontal = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.SOUTH, NO_BELT);
        assertEquals(8 / 16.0, horizontal.x, EPS);
        assertEquals(12.2 / 16.0, horizontal.y, EPS);
        assertEquals(8.55 / 16.0, horizontal.z, EPS);

        // 立式漏斗：UP 贴在下颌（y=2），DOWN 贴在头顶（y=14），都在"南"侧
        Vec3 up = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.UP, NO_BELT);
        assertEquals(2 / 16.0, up.y, EPS);
        assertEquals(15.5 / 16.0, up.z, EPS);

        Vec3 down = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.DOWN, NO_BELT);
        assertEquals(14 / 16.0, down.y, EPS);
        assertEquals(15.5 / 16.0, down.z, EPS);
    }

    @Test
    void slotOffsetsDifferPerFacingSoTheIconFollowsTheFunnel() {
        // 收回态仅在 side == funnelFacing 的面激活，偏移随 side 旋转
        Vec3 south = DumpFunnelLayout.slotOffset(Direction.SOUTH, Direction.SOUTH,
                DumpFunnelLayout.Shape.RETRACTED);
        Vec3 east = DumpFunnelLayout.slotOffset(Direction.EAST, Direction.EAST,
                DumpFunnelLayout.Shape.RETRACTED);
        Vec3 north = DumpFunnelLayout.slotOffset(Direction.NORTH, Direction.NORTH,
                DumpFunnelLayout.Shape.RETRACTED);

        // 面向 SOUTH / EAST / NORTH 三种朝向必须落在三个不同位置（同高度、同到中心的水平距离）
        assertNotEquals(south.z, east.z, "朝向变化必须真的转图标");
        assertNotEquals(south.z, north.z);
        assertEquals(south.y, east.y, EPS);
        assertEquals(south.y, north.y, EPS);
        // 收回态基准点 voxelSpace(8, 13, 7.5)，离方块水平中心 0.5 px = 0.03125 格；旋转只改方向不改半径
        for (Vec3 offset : new Vec3[] {south, east, north}) {
            assertEquals(0.03125, Math.hypot(offset.x - 0.5, offset.z - 0.5), EPS);
        }

        // 延展态统一在顶面（side == UP）激活，偏移随 funnelFacing 旋转
        Vec3 extSouth = DumpFunnelLayout.slotOffset(Direction.UP, Direction.SOUTH,
                DumpFunnelLayout.Shape.EXTENDED);
        Vec3 extEast = DumpFunnelLayout.slotOffset(Direction.UP, Direction.EAST,
                DumpFunnelLayout.Shape.EXTENDED);
        Vec3 extNorth = DumpFunnelLayout.slotOffset(Direction.UP, Direction.NORTH,
                DumpFunnelLayout.Shape.EXTENDED);
        assertNotEquals(extSouth.z, extEast.z, "延展态朝向变化必须真的转图标");
        assertNotEquals(extSouth.z, extNorth.z);
        assertEquals(extSouth.y, extEast.y, EPS);
        assertEquals(extSouth.y, extNorth.y, EPS);
    }

    // ---------------------------------------------------------------- 图标可见面

    @Test
    void extendedSlotIsOnlyVisibleFromAbove() {
        assertTrue(DumpFunnelLayout.slotSideActive(Direction.UP, Direction.NORTH,
                DumpFunnelLayout.Shape.EXTENDED), "延展态的槽位在喷口顶面");
        assertFalse(DumpFunnelLayout.slotSideActive(Direction.NORTH, Direction.NORTH,
                DumpFunnelLayout.Shape.EXTENDED));
        assertFalse(DumpFunnelLayout.slotSideActive(Direction.EAST, Direction.NORTH,
                DumpFunnelLayout.Shape.EXTENDED));
        assertFalse(DumpFunnelLayout.slotSideActive(Direction.DOWN, Direction.NORTH,
                DumpFunnelLayout.Shape.EXTENDED));
    }

    @Test
    void nonExtendedSlotIsVisibleFromTheFacingSide() {
        for (DumpFunnelLayout.Shape shape : new DumpFunnelLayout.Shape[] {DumpFunnelLayout.Shape.RETRACTED,
                DumpFunnelLayout.Shape.PUSHING, DumpFunnelLayout.Shape.PULLING}) {
            assertTrue(DumpFunnelLayout.slotSideActive(Direction.NORTH, Direction.NORTH, shape), shape.name());
            assertFalse(DumpFunnelLayout.slotSideActive(Direction.UP, Direction.NORTH, shape), shape.name());
        }
    }

    @Test
    void plainFunnelSlotFollowsFacingOrTheHorizontalSides() {
        assertTrue(DumpFunnelLayout.slotSideActive(Direction.NORTH, Direction.NORTH, NO_BELT));
        assertFalse(DumpFunnelLayout.slotSideActive(Direction.SOUTH, Direction.NORTH, NO_BELT));

        // 立式漏斗：图标从四个水平面都能点到，顶/底面不显示
        assertTrue(DumpFunnelLayout.slotSideActive(Direction.NORTH, Direction.UP, NO_BELT));
        assertTrue(DumpFunnelLayout.slotSideActive(Direction.WEST, Direction.UP, NO_BELT));
        assertFalse(DumpFunnelLayout.slotSideActive(Direction.UP, Direction.UP, NO_BELT));
        assertFalse(DumpFunnelLayout.slotSideActive(Direction.DOWN, Direction.UP, NO_BELT));
    }

    // ---------------------------------------------------------------- 图标朝向

    @Test
    void beltShapesUseBeltRotationsAndExtendedFlattens() {
        // 延展态：压平贴顶（原版最后一条分支：rotateY(facing) + rotateX(90)）
        assertEquals(DumpFunnelLayout.SlotRotation.FLATTENED,
                DumpFunnelLayout.slotRotation(Direction.NORTH, DumpFunnelLayout.Shape.EXTENDED, false));
        // 收回态：只做 Sided 旋转
        assertEquals(DumpFunnelLayout.SlotRotation.BELT_SIDED,
                DumpFunnelLayout.slotRotation(Direction.NORTH, DumpFunnelLayout.Shape.RETRACTED, false));
        // 垂直形态：Sided + 前倾 22.5°
        assertEquals(DumpFunnelLayout.SlotRotation.BELT_SIDED_TILTED,
                DumpFunnelLayout.slotRotation(Direction.NORTH, DumpFunnelLayout.Shape.PUSHING, false));
        assertEquals(DumpFunnelLayout.SlotRotation.BELT_SIDED_TILTED,
                DumpFunnelLayout.slotRotation(Direction.NORTH, DumpFunnelLayout.Shape.PULLING, false));
    }

    @Test
    void plainHorizontalFunnelTiltsLikeCreateAndVerticalFunnelsOnlyUseSided() {
        assertEquals(DumpFunnelLayout.SlotRotation.FUNNEL_TILTED,
                DumpFunnelLayout.slotRotation(Direction.NORTH, NO_BELT, true));
        assertEquals(DumpFunnelLayout.SlotRotation.VERTICAL,
                DumpFunnelLayout.slotRotation(Direction.UP, NO_BELT, true));
        assertEquals(DumpFunnelLayout.SlotRotation.VERTICAL,
                DumpFunnelLayout.slotRotation(Direction.DOWN, NO_BELT, false));
    }

    // ---------------------------------------------------------------- 门帘偏移

    @Test
    void flapOffsetFollowsTheShapeLikeCreate() {
        // 传送带漏斗：EXTENDED 0.5（挂到延展喷口前缘）、垂直形态 -0.125、收回态 0.0
        assertEquals(0.5f, DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.EXTENDED), 1.0e-6f);
        assertEquals(-0.125f, DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.PUSHING), 1.0e-6f);
        assertEquals(-0.125f, DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.PULLING), 1.0e-6f);
        assertEquals(0.0f, DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.RETRACTED), 1.0e-6f);
        // 普通漏斗沿用 Create 的 -0.0625
        assertEquals(-0.0625f, DumpFunnelLayout.flapOffset(NO_BELT), 1.0e-6f);
    }

    @Test
    void extendedFlapIsTheOneThatClearsTheBeltStrip() {
        assertTrue(DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.EXTENDED) > 0.4f,
                "延展态门帘必须被推到喷口外侧，否则它会横在皮带条上方");
        assertTrue(DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.RETRACTED)
                < DumpFunnelLayout.flapOffset(DumpFunnelLayout.Shape.EXTENDED));
    }

    // ---------------------------------------------------------------- 形态更新规则

    @Test
    void extendedShapeSurvivesAnyNeighborUpdate() {
        // 这是三态里"接入传送带并延展"能否留住的唯一保障
        assertEquals(DumpFunnelLayout.Shape.EXTENDED, DumpFunnelLayout.resolveNeighborShape(
                DumpFunnelLayout.Shape.RETRACTED, DumpFunnelLayout.Shape.EXTENDED),
                "算出收回态也不能把延展态收回去");
        assertEquals(DumpFunnelLayout.Shape.EXTENDED, DumpFunnelLayout.resolveNeighborShape(
                DumpFunnelLayout.Shape.EXTENDED, DumpFunnelLayout.Shape.EXTENDED));
        // 但传送带没对齐 / 被拆时仍要回退（由 updateShape 的 isOnValidBelt 分支负责，不在这里）
        assertEquals(DumpFunnelLayout.Shape.PUSHING, DumpFunnelLayout.resolveNeighborShape(
                DumpFunnelLayout.Shape.PUSHING, DumpFunnelLayout.Shape.RETRACTED));
    }

    @Test
    void pullingIsNotOverriddenByPushingButPushingWinsOtherwise() {
        assertEquals(DumpFunnelLayout.Shape.PULLING, DumpFunnelLayout.resolveNeighborShape(
                DumpFunnelLayout.Shape.PUSHING, DumpFunnelLayout.Shape.PULLING),
                "当前是 PULLING 时不能被邻居顶成 PUSHING（Create 原版就有这条）");
        assertEquals(DumpFunnelLayout.Shape.RETRACTED, DumpFunnelLayout.resolveNeighborShape(
                DumpFunnelLayout.Shape.RETRACTED, DumpFunnelLayout.Shape.RETRACTED));
    }

    @Test
    void computedPullingCollapsesToPushingBecauseWeNeverPull() {
        assertEquals(DumpFunnelLayout.Shape.PUSHING, DumpFunnelLayout.normalizeComputedShape(
                DumpFunnelLayout.Shape.PULLING, DumpFunnelLayout.Shape.RETRACTED));
        assertEquals(DumpFunnelLayout.Shape.PUSHING, DumpFunnelLayout.normalizeComputedShape(
                DumpFunnelLayout.Shape.PULLING, DumpFunnelLayout.Shape.EXTENDED));
        // 现状就是 PULLING 时原样保留，交给 resolveNeighborShape 的原版规则处理
        assertEquals(DumpFunnelLayout.Shape.PULLING, DumpFunnelLayout.normalizeComputedShape(
                DumpFunnelLayout.Shape.PULLING, DumpFunnelLayout.Shape.PULLING));
        assertEquals(DumpFunnelLayout.Shape.RETRACTED, DumpFunnelLayout.normalizeComputedShape(
                DumpFunnelLayout.Shape.RETRACTED, DumpFunnelLayout.Shape.EXTENDED));
    }
}
