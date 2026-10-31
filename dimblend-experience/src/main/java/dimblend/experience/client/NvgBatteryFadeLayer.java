package dimblend.experience.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.mcreator.hardcorenvg.network.HardcoreNvgModVariables;

/**
 * hardcore_nvg 夜视仪低电量渐褪：夜视开启时，在世界画面上叠一层随电量加深的黑色遮罩，
 * 模拟夜视亮度逐渐褪去。整屏一次 {@code fill}，无逐像素绘制，开销可忽略。
 *
 * <p><b>映射（2026-10-31 用户修订，取代"50% 起线性、85% 上限"的旧口径）：</b>电量 →
 * 遮罩不透明度（百分比），锚点 60→25、50→35、40→45、30→55、20→75、10→85、0→95，
 * 锚点之间<b>分段线性插值</b>——注意 30%→20% 段更陡（55→75，斜率 2 个百分点不透明度/百分点电量，
 * 其余段为 1），不是一条直线。电量 &gt; 60% 不遮罩；电量 &lt; 0 按 0 处理（不透明度 95%）。
 * 不透明度百分比按 {@code round(255 * opacity / 100)} 换成 0–255 的 alpha，
 * 故 95% → 242（{@code 0xF2}），85% → 217（{@code 0xD9}）。</p>
 *
 * <p>电量/开关读 hardcore_nvg 的玩家附件 {@code hardcore_nvg:player_variables}
 * （{@code PlayerVariables}，服务端每 tick 同步）：{@code Percent} = 剩余电量 0–100
 * （该 mod 电量即物品耐久，夜视生效期间每 tick 耐久 -1），{@code NightVision} =
 * 夜视开关。仅客户端渲染，不回写任何状态；无功能开关，行为只由电量与夜视开关决定。</p>
 *
 * <p>图层经 {@code RegisterGuiLayersEvent#registerBelowAll} 挂在全部 HUD 之下：
 * 只压暗世界画面，准星与快捷栏等 HUD 保持原亮度。</p>
 */
public final class NvgBatteryFadeLayer {

    /**
     * 电量(%) → 遮罩不透明度(%) 锚点，按电量<b>降序</b>排列，最后一项为 0%。
     * 电量高于首项（60%）时不遮罩。
     */
    private static final double[][] OPACITY_ANCHORS = {
            {60.0, 25.0},
            {50.0, 35.0},
            {40.0, 45.0},
            {30.0, 55.0},
            {20.0, 75.0},
            {10.0, 85.0},
            {0.0, 95.0},
    };

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        var vars = player.getData(HardcoreNvgModVariables.PLAYER_VARIABLES);
        if (!vars.NightVision) {
            return;
        }
        int alpha = dimAlpha(vars.Percent);
        if (alpha > 0) {
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
        }
    }

    /**
     * 遮罩不透明度映射：电量 ≥ 60% 为 0（不遮罩）；60% 以下按
     * {@link #OPACITY_ANCHORS} 分段线性插值；负电量按 0% 计（不透明度 95%）。
     *
     * @return 0–255 的 alpha，{@code round(255 * opacity / 100)}
     */
    static int dimAlpha(double percent) {
        double opacity = opacityPercent(percent);
        return (int) Math.round(255.0 * opacity / 100.0);
    }

    /** 电量(%) → 遮罩不透明度(%)，分段线性插值；高电量不遮罩时为 0。 */
    static double opacityPercent(double percent) {
        double clamped = Math.max(percent, 0.0);
        if (clamped > OPACITY_ANCHORS[0][0]) {
            return 0.0;
        }
        for (int i = 1; i < OPACITY_ANCHORS.length; i++) {
            double highBattery = OPACITY_ANCHORS[i - 1][0];
            double lowBattery = OPACITY_ANCHORS[i][0];
            if (clamped > lowBattery) {
                double highOpacity = OPACITY_ANCHORS[i - 1][1];
                double lowOpacity = OPACITY_ANCHORS[i][1];
                double t = (highBattery - clamped) / (highBattery - lowBattery);
                return highOpacity + t * (lowOpacity - highOpacity);
            }
        }
        return OPACITY_ANCHORS[OPACITY_ANCHORS.length - 1][1];
    }

    private NvgBatteryFadeLayer() {
    }
}