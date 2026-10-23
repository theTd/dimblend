package dimblend.experience.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.mcreator.hardcorenvg.network.HardcoreNvgModVariables;

/**
 * hardcore_nvg 夜视仪低电量渐褪（需求 2026-10-23 修订，取代白噪点版——噪点每帧
 * 数千次单像素 fill 拖 FPS，且用户反馈看久了伤眼）：夜视开启且电量低于 50% 时，
 * 在世界画面上叠一层随电量线性加深的黑色遮罩，模拟夜视亮度逐渐褪去——50% 起透明，
 * 0% 时遮罩不透明度 85%（10% 时约 68%，达到"严重干扰视线"的参考增量）。整屏一次
 * fill，无逐像素绘制，开销可忽略。
 *
 * <p>电量/开关读 hardcore_nvg 的玩家附件 {@code hardcore_nvg:player_variables}
 * （{@code PlayerVariables}，服务端每 tick 同步）：{@code Percent} = 剩余电量 0–100
 * （该 mod 电量即物品耐久，夜视生效期间每 tick 耐久 -1），{@code NightVision} =
 * 夜视开关。仅客户端渲染，不回写任何状态。</p>
 *
 * <p>图层经 {@code RegisterGuiLayersEvent#registerBelowAll} 挂在全部 HUD 之下：
 * 只压暗世界画面，准星与快捷栏等 HUD 保持原亮度。</p>
 */
public final class NvgBatteryFadeLayer {

    /** 起褪电量阈值（%）：低于该值开始压暗。 */
    private static final double THRESHOLD_PERCENT = 50.0;
    /** 电量 0% 时的遮罩不透明度（0-255）：85%。 */
    private static final int MAX_ALPHA = 0xD9;

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
     * 遮罩不透明度映射：电量 ≥ 阈值（50%）为 0（不遮罩）；低于阈值后随电量线性加深，
     * 10% 时为上限的 80%（(50-10)/50，对齐噪点版的增量参考），0% 时达
     * {@link #MAX_ALPHA}；负电量按 0% 计。
     */
    static int dimAlpha(double percent) {
        if (percent >= THRESHOLD_PERCENT) {
            return 0;
        }
        double t = (THRESHOLD_PERCENT - Math.max(percent, 0.0)) / THRESHOLD_PERCENT;
        return (int) (MAX_ALPHA * t);
    }

    private NvgBatteryFadeLayer() {
    }
}
