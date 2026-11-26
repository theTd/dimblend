package dimblend.blocks.compat.deployer;

/**
 * 卸料仪表屏的纯几何。
 *
 * <p>所有数字都是从 Create 6.0.10 的 {@code assets/create/textures/gui/factory_gauge.png}
 * 逐像素量出来的（贴图切片的 u/v 取自 {@code AllGuiTextures}：
 * {@code FACTORY_GAUGE_RECIPE = factory_gauge, 32, 0, 192×96}、
 * {@code FACTORY_GAUGE_BOTTOM = factory_gauge, 32, 176, 200×64}），
 * 并逐条对照 {@code FactoryPanelScreen#init/mouseClicked/renderWindow} 的硬编码坐标。</p>
 *
 * <p>关键实测结论（窗口局部坐标，原点 = {@code guiLeft/guiTop}）：</p>
 * <ul>
 *   <li>主体图 192×96：蓝色标题条 y 0..15，深色边框 y 15；左侧内壁 x=2，右侧内壁 x=189；</li>
 *   <li>3×3 输入区外框 x 56..135、y 23..86；每个格子的<b>内芯</b>正好是
 *       {@code (68,28) + (20,20)·(列,行)} 的 16×16（外框的 2px 描边已经烘在贴图里）；</li>
 *   <li>箭头 → 在 x 138..152、y 48..64，指向右侧的"产出格"内芯 x 160..175、y 48..63；</li>
 *   <li>左侧唯一一枚烘好的按钮框在 x 30..46、y 67..84（父类把两个 IconButton 建在 y+47 与 y+67）；</li>
 *   <li>底栏 200×64（y 96..160）：羊皮纸地址槽 y 105..121，灰条 y 129..158 且底色恒为 {@code #C6C6C6}；
 *       父类把地址框放 y+109、链接槽 y+136、承诺滑条 (97,136)、删除/确认按钮 y+135；</li>
 *   <li>灰条里烘死的控件：时钟 x 57..67、承诺包裹槽内芯 x 68..83、承诺滑条值框与箭头 x 93..135。</li>
 * </ul>
 *
 * <p>该贴图不像 Deployer 的 {@code generic_gauge} 那样是九宫格：它是固定尺寸的整体图，
 * 所以本屏不做任何平铺/拉伸，只按上面这些坐标落位。</p>
 */
public final class DumpPanelLayout {

    public static final int WINDOW_WIDTH = 200;
    public static final int WINDOW_HEIGHT = 160;

    /** 主体贴图（{@code factory_gauge.png} 的 recipe 切片）尺寸。 */
    public static final int BODY_WIDTH = 192;
    public static final int BODY_HEIGHT = 96;

    /** 蓝色标题条之后、灰底栏之前的内容区：y ∈ [16, 96)。 */
    public static final int BODY_TOP = 16;
    public static final int BODY_BOTTOM = BODY_HEIGHT;

    /** 标题：{@code x + 97 - width/2, y + 4}（图宽 192，但父类按 97 居中）。 */
    public static final int TITLE_CENTER_X = 97;
    public static final int TITLE_Y = 4;
    public static final int TITLE_COLOR = 0x3D3C48;

    public static final int SLOT_SIZE = 16;

    public static final int INPUT_GRID_X = 68;
    public static final int INPUT_GRID_Y = 28;
    public static final int INPUT_PITCH = 20;
    public static final int INPUT_COLUMNS = 3;
    public static final int INPUT_ROWS = 3;
    public static final int INPUT_CAPACITY = INPUT_COLUMNS * INPUT_ROWS;
    /** 3×3 外框（贴图里烘的那一圈 2px 描边之外）。 */
    public static final int INPUT_FRAME_X = 56;
    public static final int INPUT_FRAME_Y = 23;
    public static final int INPUT_FRAME_WIDTH = 80;
    public static final int INPUT_FRAME_HEIGHT = 64;

    /** 卸料物槽：借用 Create 画「期望产出」的那一格（箭头正好指过来，语义就是「信号 → 卸到这个物品」）。 */
    public static final int MONITORED_SLOT_X = 160;
    public static final int MONITORED_SLOT_Y = 48;
    /** 槽上方的标签基线（槽顶 48，字高 9 → 37 正好留 1px）。 */
    public static final int MONITORED_LABEL_Y = 37;
    /** 槽下方的目标文案（两行：标签 + 数值）。 */
    public static final int TARGET_LABEL_Y = 68;
    public static final int TARGET_VALUE_Y = 79;
    /** 右侧文字都右对齐到这一列，避免顶出 189 的内壁。 */
    public static final int RIGHT_TEXT_EDGE = 187;

    public static final int ADDRESS_BOX_X = 36;
    public static final int ADDRESS_BOX_WIDTH = 108;
    public static final int ADDRESS_BOX_HEIGHT = 10;
    /** 父类：{@code y + windowHeight - 51}。 */
    public static final int ADDRESS_BOX_Y_OFFSET = 51;

    /** 底栏灰条上沿（贴图的 y=129 是亮边，128 是分隔暗线）。 */
    public static final int FOOTER_TOP = 129;
    /** 父类所有底栏热区的 y：{@code y + windowHeight - 24}。 */
    public static final int FOOTER_SLOT_Y_OFFSET = 24;
    public static final int PACKAGE_SLOT_X = 68;
    public static final int LINK_SLOT_X = 9;

    /** 要抹平并改写成「周期」的烘死控件区：时钟 + 承诺包裹槽 + 承诺滑条（x 55..141、y 130..157）。 */
    public static final int PERIOD_MASK_X = 55;
    public static final int PERIOD_MASK_Y = 130;
    public static final int PERIOD_MASK_WIDTH = 87;
    public static final int PERIOD_MASK_HEIGHT = 28;
    /** 灰条底色（实测常值，用来无缝覆盖烘死的控件）。 */
    public static final int FOOTER_GRAY = 0xFFC6C6C6;
    /** 灰条上的文字（深色，与贴图上的浅灰底对比足够）。 */
    public static final int FOOTER_TEXT_COLOR = 0x3D3C48;

    /** 3D 仪表预览（与 Create 同位）。 */
    public static final int GAUGE_PREVIEW_X = 195;
    public static final int GAUGE_PREVIEW_Y = 55;
    public static final int GAUGE_PREVIEW_ITEM_X = 214;
    public static final int GAUGE_PREVIEW_ITEM_Y = 68;

    /** Create {@code FactoryPanelScreen#init} 中 3×3 合成开关按钮的固定偏移（javap 核实：guiLeft+31, guiTop+27）。 */
    public static final int CRAFTING_BUTTON_X = 31;
    public static final int CRAFTING_BUTTON_Y = 27;

    private DumpPanelLayout() {
    }

    /** 命中框矩形（16×16，原点即物品绘制原点）。 */
    public record Rect(int x, int y, int width, int height) {

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }

    public static Rect inputSlot(int index) {
        int column = index % INPUT_COLUMNS;
        int row = index / INPUT_COLUMNS;
        return new Rect(INPUT_GRID_X + column * INPUT_PITCH, INPUT_GRID_Y + row * INPUT_PITCH, SLOT_SIZE, SLOT_SIZE);
    }

    /** 整张 3×3 输入区（没有任何输入时把提示挂在这一整块上，与 Create 同法）。 */
    public static Rect inputArea() {
        return new Rect(INPUT_FRAME_X, INPUT_FRAME_Y, INPUT_FRAME_WIDTH, INPUT_FRAME_HEIGHT);
    }

    public static Rect monitoredSlot() {
        return new Rect(MONITORED_SLOT_X, MONITORED_SLOT_Y, SLOT_SIZE, SLOT_SIZE);
    }

    public static Rect addressBox(int windowHeight) {
        return new Rect(ADDRESS_BOX_X, windowHeight - ADDRESS_BOX_Y_OFFSET, ADDRESS_BOX_WIDTH, ADDRESS_BOX_HEIGHT);
    }

    public static Rect footerSlot(int windowHeight, int x) {
        return new Rect(x, windowHeight - FOOTER_SLOT_Y_OFFSET, SLOT_SIZE, SLOT_SIZE);
    }

    /** 承诺包裹槽的位置：本屏不使用承诺，点这里一律吞掉（父类会在这一格发 {@code clearPromises}）。 */
    public static Rect packageSlot(int windowHeight) {
        return footerSlot(windowHeight, PACKAGE_SLOT_X);
    }

    /** 被抹平改写的烘死控件区。 */
    public static Rect periodMask() {
        return new Rect(PERIOD_MASK_X, PERIOD_MASK_Y, PERIOD_MASK_WIDTH, PERIOD_MASK_HEIGHT);
    }

    /** 两个矩形是否相交（半开区间，边贴边不算）。 */
    public static boolean intersects(Rect a, Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    /** 矩形是否完整落在 200×160 窗口内。 */
    public static boolean insideWindow(Rect rect) {
        return rect.x() >= 0 && rect.y() >= 0 && rect.right() <= WINDOW_WIDTH && rect.bottom() <= WINDOW_HEIGHT;
    }

    /** 矩形是否完整落在主体图里（标题条与灰底栏都不会盖到它）。 */
    public static boolean insideBody(Rect rect) {
        return rect.y() >= BODY_TOP && rect.bottom() <= BODY_BOTTOM;
    }
}
