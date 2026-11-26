package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dimblend.blocks.compat.deployer.DumpPanelLayout.Rect;

/**
 * 卸料仪表屏几何：自绘格子必须落在父类 {@code FactoryPanelScreen} 的命中框上。
 *
 * <p>父类硬编码（Create 6.0.10）：输入格 {@code x+68+i%3*20, y+28+i/3*20}（16×16 命中），
 * 地址框 {@code x+36, y+windowHeight-51}（108×10），底栏热区 {@code y+windowHeight-24}。</p>
 */
class DumpPanelLayoutTest {

    @Test
    void inputSlotsFollowTheParentsTwentyPixelGrid() {
        assertEquals(new Rect(68, 28, 16, 16), DumpPanelLayout.inputSlot(0));
        assertEquals(new Rect(88, 28, 16, 16), DumpPanelLayout.inputSlot(1));
        assertEquals(new Rect(108, 28, 16, 16), DumpPanelLayout.inputSlot(2));
        assertEquals(new Rect(68, 48, 16, 16), DumpPanelLayout.inputSlot(3));
        // 第 8 格 = 列 2、行 2 → (68+40, 28+40)
        assertEquals(new Rect(108, 68, 16, 16), DumpPanelLayout.inputSlot(8));
        assertEquals(9, DumpPanelLayout.INPUT_CAPACITY);
    }

    @Test
    void everySlotStaysInsideTheBrownBody() {
        for (int i = 0; i < DumpPanelLayout.INPUT_CAPACITY; i++) {
            Rect slot = DumpPanelLayout.inputSlot(i);
            assertTrue(DumpPanelLayout.insideWindow(slot), "输入格 " + i + " 出窗口");
            assertTrue(DumpPanelLayout.insideBody(slot), "输入格 " + i + " 压到标题条或灰底栏");
        }
        Rect monitored = DumpPanelLayout.monitoredSlot();
        assertTrue(DumpPanelLayout.insideWindow(monitored));
        assertTrue(DumpPanelLayout.insideBody(monitored));
    }

    @Test
    void monitoredSlotDoesNotCollideWithInputGridOrButtons() {
        Rect monitored = DumpPanelLayout.monitoredSlot();
        for (int i = 0; i < DumpPanelLayout.INPUT_CAPACITY; i++) {
            assertFalse(DumpPanelLayout.intersects(monitored, DumpPanelLayout.inputSlot(i)));
        }
        // 父类的「连接」按钮在 x+31,y+47、「搬迁」在 x+31,y+67（16×16）
        assertFalse(DumpPanelLayout.intersects(monitored, new Rect(31, 47, 16, 16)));
        assertFalse(DumpPanelLayout.intersects(monitored, new Rect(31, 67, 16, 16)));
        // 父类的 3×3 合成按钮在 x+31,y+27，即使没被隐藏也不能压到监控格
        assertFalse(DumpPanelLayout.intersects(monitored, new Rect(31, 27, 16, 16)));
    }

    @Test
    void addressBoxSitsWhereTheParentPutsIt() {
        Rect box = DumpPanelLayout.addressBox(DumpPanelLayout.WINDOW_HEIGHT);
        assertEquals(new Rect(36, 109, 108, 10), box);
        assertTrue(DumpPanelLayout.insideBody(box));
        // 地址框不能盖到最下面一排输入格（第 6..8 格，y=68..84）
        assertFalse(DumpPanelLayout.intersects(box, DumpPanelLayout.inputSlot(6)));
        assertFalse(DumpPanelLayout.intersects(box, DumpPanelLayout.inputSlot(8)));
        // 标签在框的上方，不压住框
        assertTrue(DumpPanelLayout.ADDRESS_LABEL_Y + 9 <= box.y(),
                "地址标签画到 " + (DumpPanelLayout.ADDRESS_LABEL_Y + 9) + "，会压住 y=" + box.y() + " 的输入框");
    }

    @Test
    void footerSlotsMatchTheParentsClickAreas() {
        int h = DumpPanelLayout.WINDOW_HEIGHT;
        assertEquals(new Rect(68, h - 24, 16, 16),
                DumpPanelLayout.footerSlot(h, DumpPanelLayout.PACKAGE_SLOT_X));
        assertEquals(new Rect(9, h - 24, 16, 16),
                DumpPanelLayout.footerSlot(h, DumpPanelLayout.LINK_SLOT_X));
        assertTrue(DumpPanelLayout.insideWindow(DumpPanelLayout.footerSlot(h, DumpPanelLayout.LINK_SLOT_X)));
        assertFalse(DumpPanelLayout.insideBody(DumpPanelLayout.footerSlot(h, DumpPanelLayout.PACKAGE_SLOT_X)),
                "底栏两格本来就落在灰底栏，不该被判进主体");
    }

    @Test
    void frameBandsPartitionTheWindow() {
        assertEquals(16, DumpPanelLayout.BODY_TOP);
        assertEquals(129, DumpPanelLayout.BODY_BOTTOM);
        assertTrue(DumpPanelLayout.BODY_TOP < DumpPanelLayout.BODY_BOTTOM);
        assertTrue(DumpPanelLayout.BODY_BOTTOM < DumpPanelLayout.WINDOW_HEIGHT);
    }
}
