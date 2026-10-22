package dimblend.experience.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.RandomSource;
import net.mcreator.hardcorenvg.network.HardcoreNvgModVariables;

/**
 * hardcore_nvg 夜视仪低电量白噪点（需求 2026-10-23）：夜视开启且电量低于 50% 时，
 * 在画面上撒白色噪点，数量随电量下降线性增多——50% 起 0 个，10% 时达最大密度的
 * 80%（参考增量：剩余 10% 已严重干扰视线），0% 满密度；噪点每帧重随机，带闪烁感。
 *
 * <p>电量/开关读 hardcore_nvg 的玩家附件 {@code hardcore_nvg:player_variables}
 * （{@code PlayerVariables}，服务端每 tick 同步）：{@code Percent} = 剩余电量 0–100
 * （该 mod 电量即物品耐久，夜视生效期间每 tick 耐久 -1），{@code NightVision} =
 * 夜视开关。仅客户端渲染，不回写任何状态。</p>
 */
public final class NvgBatteryNoiseLayer {

    /** 起噪电量阈值（%）：低于该值开始出现噪点。 */
    private static final double THRESHOLD_PERCENT = 50.0;
    /** 满密度（电量 0%）时噪点数占画面逻辑像素总数的比例。 */
    private static final double MAX_DENSITY = 0.02;
    /** 噪点颜色：白色 75% 不透明度。 */
    private static final int COLOR = 0xBFFFFFFF;

    private static final RandomSource RANDOM = RandomSource.create();

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        var vars = player.getData(HardcoreNvgModVariables.PLAYER_VARIABLES);
        if (!vars.NightVision) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int dots = dotCount(vars.Percent, width, height);
        for (int i = 0; i < dots; i++) {
            int x = RANDOM.nextInt(width);
            int y = RANDOM.nextInt(height);
            graphics.fill(x, y, x + 1, y + 1, COLOR);
        }
    }

    /**
     * 噪点数量映射：电量 ≥ 阈值（50%）为 0；低于阈值后随电量线性增多，10% 时为
     * 满密度的 80%（(50-10)/50），0% 时满密度（画面逻辑像素的 2%）；负电量按 0% 计。
     */
    static int dotCount(double percent, int width, int height) {
        if (percent >= THRESHOLD_PERCENT) {
            return 0;
        }
        double t = (THRESHOLD_PERCENT - Math.max(percent, 0.0)) / THRESHOLD_PERCENT;
        return (int) (width * height * MAX_DENSITY * t);
    }

    private NvgBatteryNoiseLayer() {
    }
}
