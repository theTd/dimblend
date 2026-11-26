package dimblend.blocks.compat.deployer;

/**
 * 卸料仪表屏的纯几何（200×160 窗口，对应 Deployer {@code generic_gauge.png} 九宫格）。
 *
 * <p>这些数字必须与 {@code FactoryPanelScreen} 建控件时用的常量一致，否则「画出来的格子」和
 * 「点了才有反应的格子」会错位——这正是原屏只能靠父类隐藏控件、自己又按另一套坐标重画导致的问题。
 * 父类 6.0.10 的硬编码（{@code FactoryPanelScreen#init/mouseClicked}）：</p>
 * <ul>
 *   <li>输入格：{@code x + 68 + i%3*20, y + 28 + i/3*20}，命中框 16×16；</li>
 *   <li>地址框：{@code x + 36, y + windowHeight - 51}，108×10；</li>
 *   <li>底部两格（清承诺 / 红石重置）：{@code y + windowHeight - 24}，x 分别 +68 与 +9；</li>
 *   <li>删除/确认按钮：{@code x + sizeX - 55 / -33, y + sizeY - 25}。</li>
 * </ul>
 *
 * <p>画在「命中框原点 - 1」的 18×18 描边（{@code AllGuiTextures.FROGPORT_SLOT}）刚好把 16×16 的物品框住，
 * 与 Create 自己在红石链接槽上的画法一致。</p>
 */
public final class DumpPanelLayout {

    public static final int WINDOW_WIDTH = 200;
    public static final int WINDOW_HEIGHT = 160;
    /** 蓝色标题条之后、灰色底栏之前的棕色主体：y ∈ [16, 129)。 */
    public static final int BODY_TOP = 16;
    public static final int BODY_BOTTOM = 129;

    public static final int SLOT_SIZE = 16;
    public static final int SLOT_FRAME_SIZE = SLOT_SIZE + 2;

    public static final int INPUT_GRID_X = 68;
    public static final int INPUT_GRID_Y = 28;
    public static final int INPUT_PITCH = 20;
    public static final int INPUT_COLUMNS = 3;
    public static final int INPUT_ROWS = 3;
    public static final int INPUT_CAPACITY = INPUT_COLUMNS * INPUT_ROWS;

    /** 左列「监控物品」：落在连接按钮（x+31,y+47）左边，互不遮挡。 */
    public static final int MONITORED_SLOT_X = 8;
    public static final int MONITORED_SLOT_Y = 33;
    public static final int MONITORED_LABEL_X = 8;
    public static final int MONITORED_LABEL_Y = 22;

    public static final int ADDRESS_LABEL_X = 36;
    public static final int ADDRESS_LABEL_Y = 95;
    public static final int ADDRESS_BOX_X = 36;
    public static final int ADDRESS_BOX_WIDTH = 108;
    public static final int ADDRESS_BOX_HEIGHT = 10;
    /** 父类：{@code y + windowHeight - 51}。 */
    public static final int ADDRESS_BOX_Y_OFFSET = 51;

    public static final int FOOTER_SLOT_Y_OFFSET = 24;
    public static final int PACKAGE_SLOT_X = 68;
    public static final int LINK_SLOT_X = 9;

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
    }

    public static Rect inputSlot(int index) {
        int column = index % INPUT_COLUMNS;
        int row = index / INPUT_COLUMNS;
        return new Rect(INPUT_GRID_X + column * INPUT_PITCH, INPUT_GRID_Y + row * INPUT_PITCH,
                SLOT_SIZE, SLOT_SIZE);
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

    /** 两个矩形是否相交（半开区间，边贴边不算）。 */
    public static boolean intersects(Rect a, Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    /** 矩形是否完整落在 200×160 窗口内。 */
    public static boolean insideWindow(Rect rect) {
        return rect.x() >= 0 && rect.y() >= 0 && rect.right() <= WINDOW_WIDTH && rect.bottom() <= WINDOW_HEIGHT;
    }

    /** 矩形是否完整落在棕色主体内（标题条与灰底栏都不会盖到它）。 */
    public static boolean insideBody(Rect rect) {
        return rect.y() >= BODY_TOP && rect.bottom() <= BODY_BOTTOM;
    }
}
