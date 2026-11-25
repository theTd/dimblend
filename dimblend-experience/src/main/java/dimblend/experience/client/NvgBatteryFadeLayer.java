package dimblend.experience.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.mcreator.hardcorenvg.network.HardcoreNvgModVariables;

/**
 * hardcore_nvg 夜视仪低电量渐褪：夜视开启时在世界画面上叠一层随电量加深的黑色遮罩，
 * 模拟夜视亮度逐渐褪去。整屏一次 {@code fill}，无逐像素绘制，开销可忽略。
 *
 * <p><b>映射（Audit Default #10）：</b>电量 ≥ 50% 不遮罩（0.0f）；
 * 从 50% 起每完整下降 5% 耐久更新一档（向下取整，共 10 档，每档 +10% 不透明度）：
 * 50% → 0%、45% → 10%、40% → 20%、…、5% → 90%、0% → 100%。即
 * {@code steps = min(10, floor((50 - percent + 1e-9) / 5))}，
 * {@code opacity = steps / 10.0f}。</p>
 *
 * <p>采用向下取整（每跌满 5% 才跳档），避免 {@code (0%, 5%]} 最后 90 秒电量时
 * 提前 100% 全黑；{@code percent <= 0} 时为 1.0f（255），{@code NaN} 保守返回 0.0f。</p>
 *
 * <p>图层经 {@code RegisterGuiLayersEvent#registerBelowAll} 挂在全部 HUD 之下：
 * 只压暗世界画面，准星与快捷栏等 HUD 保持原亮度。</p>
 */
public final class NvgBatteryFadeLayer {

    /** 起褪电量（%）：不低于此电量完全不遮罩。 */
    static final double FADE_START_PERCENT = 50.0;

    /** 每档电量跨度（百分点）。 */
    static final double STEP_PERCENT = 5.0;

    /** 档数上限：50% → 0% 共 10 档，每档 +10% 不透明度。 */
    static final int TOTAL_STEPS = 10;

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        var vars = player.getData(HardcoreNvgModVariables.PLAYER_VARIABLES);
        if (!vars.NightVision) {
            return;
        }
        int alpha = computeAlpha(vars.Percent);
        if (alpha > 0) {
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
        }
    }

    /**
     * 遮罩不透明度（0.0f–1.0f）：电量 ≥ {@link #FADE_START_PERCENT} 或 {@code NaN} 为 0.0f；
     * 电量 ≤ 0 为 1.0f；其余按每完整下降 {@link #STEP_PERCENT} 百分点向上走一档
     * （{@code 45% → 0.1f, 40% → 0.2f, ..., 5% → 0.9f}），加 {@code 1e-9} 消除浮点边界误差。
     */
    static float computeOpacity(double percent) {
        if (Double.isNaN(percent) || percent >= FADE_START_PERCENT) {
            return 0.0f;
        }
        if (percent <= 0.0) {
            return 1.0f;
        }
        int steps = Math.min(TOTAL_STEPS, (int) Math.floor((FADE_START_PERCENT - percent + 1.0e-9) / STEP_PERCENT));
        return steps / (float) TOTAL_STEPS;
    }

    /** 不透明度（0.0f–1.0f）→ 0–255 的 alpha，四舍五入。 */
    static int computeAlpha(double percent) {
        return Math.round(computeOpacity(percent) * 255.0f);
    }

    private NvgBatteryFadeLayer() {
    }
}
