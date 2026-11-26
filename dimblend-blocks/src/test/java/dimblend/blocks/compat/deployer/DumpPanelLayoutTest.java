package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dimblend.blocks.compat.deployer.DumpPanelLayout.Rect;

/**
 * 卸料仪表屏几何。
 *
 * <p>这些数字不是随手定的，是对着两处取证来的：</p>
 * <ol>
 *   <li>Create 6.0.10 的 {@code FactoryPanelScreen} 源码（输入格 {@code x+68+i%3*20, y+28+i/3*20} 16×16、
 *       地址框 {@code x+36, y+windowHeight-51} 108×10、底栏热区 {@code y+windowHeight-24}、
 *       产出格 {@code x+160, y+48}）；</li>
 *   <li>{@code assets/create/textures/gui/factory_gauge.png} 的逐像素实测（主体 192×96 切片的蓝色标题条
 *       y 0..15、3×3 外框 x 56..135 / y 23..86、格芯 68/28 步长 20、产出格芯 x 160..175 / y 48..63、
 *       底栏 200×64 且灰条 y 129..158 底色恒 {@code #C6C6C6}、羊皮纸地址槽 x 27..169 / y 105..121、
       按钮框 x 145..161 与 167..183）。</li>
 * </ol>
 *
 * <p>换句话说：这里断言的是「自绘的东西会不会落到贴图烘好的槽里、会不会压到父类的控件」。</p>
 */
class DumpPanelLayoutTest {

    /** 贴图里烘死的 3×3 外框（含 2px 描边）。 */
    private static final Rect BAKED_INPUT_FRAME = new Rect(56, 23, 80, 64);
    /** 贴图里烘死的羊皮纸地址槽。 */
    private static final Rect BAKED_PARCHMENT = new Rect(27, 105, 143, 17);
    /** 父类的删除/确认按钮（x+sizeX-55 / -33，y+sizeY-25，19×19）。 */
    private static final Rect PARENT_DELETE_BUTTON = new Rect(145, 135, 19, 19);
    private static final Rect PARENT_CONFIRM_BUTTON = new Rect(167, 135, 19, 19);
    /** 父类左侧「连接输入源」/「搬迁」按钮（x+31, y+47 / y+67）。 */
    private static final Rect PARENT_ADD_BUTTON = new Rect(31, 47, 16, 16);
    private static final Rect PARENT_MOVE_BUTTON = new Rect(31, 67, 16, 16);

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
    void everyInputSlotLandsInsideTheBakedFrame() {
        for (int i = 0; i < DumpPanelLayout.INPUT_CAPACITY; i++) {
            Rect slot = DumpPanelLayout.inputSlot(i);
            assertTrue(contains(BAKED_INPUT_FRAME, slot),
                    "输入格 " + i + " 掉出贴图烘好的 3×3 外框：" + slot);
            assertTrue(DumpPanelLayout.insideBody(slot), "输入格 " + i + " 压到标题条或灰底栏");
            assertTrue(DumpPanelLayout.insideWindow(slot), "输入格 " + i + " 出窗口");
        }
        // 整张输入区也等于烘死的外框，提示挂上去时才不会漏到框外
        assertEquals(BAKED_INPUT_FRAME, DumpPanelLayout.inputArea());
    }

    @Test
    void monitoredSlotIsTheBakedOutputCell() {
        // Create 的「期望产出」格：贴图内芯 x 160..175、y 48..63
        assertEquals(new Rect(160, 48, 16, 16), DumpPanelLayout.monitoredSlot());
        Rect monitored = DumpPanelLayout.monitoredSlot();
        assertTrue(DumpPanelLayout.insideBody(monitored));
        for (int i = 0; i < DumpPanelLayout.INPUT_CAPACITY; i++) {
            assertFalse(DumpPanelLayout.intersects(monitored, DumpPanelLayout.inputSlot(i)),
                    "卸料物槽压到第 " + i + " 格");
        }
        // 左侧两枚父类按钮、以及被隐藏的 3×3 按钮都不该被压到
        assertFalse(DumpPanelLayout.intersects(monitored, PARENT_ADD_BUTTON));
        assertFalse(DumpPanelLayout.intersects(monitored, PARENT_MOVE_BUTTON));
        assertEquals(31, DumpPanelLayout.CRAFTING_BUTTON_X);
        assertEquals(27, DumpPanelLayout.CRAFTING_BUTTON_Y);
        assertFalse(DumpPanelLayout.intersects(monitored,
                new Rect(DumpPanelLayout.CRAFTING_BUTTON_X, DumpPanelLayout.CRAFTING_BUTTON_Y, 16, 16)));
    }

    @Test
    void rightColumnTextStaysBetweenTheOutputCellAndTheInnerWall() {
        // 标签在槽上方、数值在槽下方，两条都右对齐到 RIGHT_TEXT_EDGE
        assertTrue(DumpPanelLayout.MONITORED_LABEL_Y + 9 <= DumpPanelLayout.MONITORED_SLOT_Y,
                "卸料物标签会压住槽顶");
        assertTrue(DumpPanelLayout.TARGET_LABEL_Y >= DumpPanelLayout.MONITORED_SLOT_Y + 18,
                "卸料目标标签会压住槽底");
        assertTrue(DumpPanelLayout.TARGET_LABEL_Y < DumpPanelLayout.TARGET_VALUE_Y);
        // 主体右侧内壁在 x=189（贴图），文字右边界必须留出 2px
        assertTrue(DumpPanelLayout.RIGHT_TEXT_EDGE <= 189 - 2);
        // 数值那行不能掉进灰底栏
        assertTrue(DumpPanelLayout.TARGET_VALUE_Y + 9 <= DumpPanelLayout.BODY_BOTTOM);
    }

    @Test
    void titleSitsInsideTheBakedBlueStrip() {
        // 贴图：蓝色标题条 y 1..14，y=15 是深色边框
        assertTrue(DumpPanelLayout.TITLE_Y >= 1);
        assertTrue(DumpPanelLayout.TITLE_Y + 9 <= 15);
        assertEquals(0x3D3C48, DumpPanelLayout.TITLE_COLOR);
    }

    @Test
    void addressBoxSitsInsideTheBakedParchment() {
        Rect box = DumpPanelLayout.addressBox(DumpPanelLayout.WINDOW_HEIGHT);
        assertEquals(new Rect(36, 109, 108, 10), box);
        assertTrue(contains(BAKED_PARCHMENT, box), "地址框掉出羊皮纸槽：" + box);
        assertTrue(DumpPanelLayout.insideWindow(box));
        // 羊皮纸槽在灰底栏之上，不该被判进主体
        assertFalse(DumpPanelLayout.insideBody(box));
        // 也不能盖到最下面一排输入格（y=68..84）
        assertFalse(DumpPanelLayout.intersects(box, DumpPanelLayout.inputSlot(6)));
        assertFalse(DumpPanelLayout.intersects(box, DumpPanelLayout.inputSlot(8)));
    }

    @Test
    void footerSlotsMatchTheParentsClickAreas() {
        int h = DumpPanelLayout.WINDOW_HEIGHT;
        assertEquals(new Rect(68, h - 24, 16, 16), DumpPanelLayout.packageSlot(h));
        assertEquals(new Rect(9, h - 24, 16, 16), DumpPanelLayout.footerSlot(h, DumpPanelLayout.LINK_SLOT_X));
        assertTrue(DumpPanelLayout.insideWindow(DumpPanelLayout.packageSlot(h)));
        assertTrue(DumpPanelLayout.insideWindow(DumpPanelLayout.footerSlot(h, DumpPanelLayout.LINK_SLOT_X)));
        assertFalse(DumpPanelLayout.insideBody(DumpPanelLayout.packageSlot(h)), "底栏两格本来就落在灰底栏");
    }

    @Test
    void periodMaskCoversTheBakedPromiseWidgetsWithoutTouchingWidgetsWeKeep() {
        Rect mask = DumpPanelLayout.periodMask();
        assertEquals(new Rect(55, 130, 87, 28), mask);

        // 贴图里烘死的三件东西（时钟 x 57..67、承诺包裹槽芯 68..83、承诺滑条值框与箭头 x 93..135）都在里面
        assertTrue(contains(mask, new Rect(57, 134, 11, 11)), "时钟没被盖住");
        assertTrue(contains(mask, DumpPanelLayout.packageSlot(DumpPanelLayout.WINDOW_HEIGHT)), "承诺包裹槽没被盖住");
        assertTrue(contains(mask, new Rect(93, 135, 43, 17)), "承诺滑条没被盖住");

        // 不能碰到保留的控件：链接槽、地址框、删除/确认按钮
        assertFalse(DumpPanelLayout.intersects(mask,
                DumpPanelLayout.footerSlot(DumpPanelLayout.WINDOW_HEIGHT, DumpPanelLayout.LINK_SLOT_X)),
                "周期读数压到红石链接槽");
        assertFalse(DumpPanelLayout.intersects(mask, DumpPanelLayout.addressBox(DumpPanelLayout.WINDOW_HEIGHT)),
                "周期读数压到地址输入框");
        assertFalse(DumpPanelLayout.intersects(mask, PARENT_DELETE_BUTTON), "周期读数压到删除按钮");
        assertFalse(DumpPanelLayout.intersects(mask, PARENT_CONFIRM_BUTTON), "周期读数压到确认按钮");

        // 也不能溢出窗口，或掉进主体图
        assertTrue(DumpPanelLayout.insideWindow(mask));
        assertFalse(DumpPanelLayout.insideBody(mask));
        assertTrue(mask.y() >= DumpPanelLayout.FOOTER_TOP, "周期读数跑到灰条上沿之外");
    }

    @Test
    void frameBandsPartitionTheWindow() {
        assertEquals(16, DumpPanelLayout.BODY_TOP);
        assertEquals(DumpPanelLayout.BODY_HEIGHT, DumpPanelLayout.BODY_BOTTOM);
        assertEquals(96, DumpPanelLayout.BODY_HEIGHT);
        assertEquals(192, DumpPanelLayout.BODY_WIDTH);
        assertEquals(200, DumpPanelLayout.WINDOW_WIDTH);
        assertEquals(160, DumpPanelLayout.WINDOW_HEIGHT);
        assertTrue(DumpPanelLayout.BODY_TOP < DumpPanelLayout.BODY_BOTTOM);
        assertTrue(DumpPanelLayout.BODY_BOTTOM < DumpPanelLayout.WINDOW_HEIGHT);
        // 底栏 = 64 高，正好是 FACTORY_GAUGE_BOTTOM 的高度
        assertEquals(64, DumpPanelLayout.WINDOW_HEIGHT - DumpPanelLayout.BODY_HEIGHT);
    }

    private static boolean contains(Rect outer, Rect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }
}
