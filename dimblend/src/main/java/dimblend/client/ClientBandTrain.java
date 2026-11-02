package dimblend.client;

import dimblend.band.BandTrainMath;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * 客户端缓存区域进度条白线的参照位置（服务端 {@link dimblend.band.BandTrainPayload} 下发）。
 *
 * <p>服务端只在位置变化时下发，因此“收不到新值”是常态：白线继续用上一次收到的 x（需求 #6），
 * 不再因为玩家离开列车 320 格、客户端丢掉整个 sublevel 而整条消失。切关卡/维度时清空，
 * 避免把上一局的列车位置画到新世界（离开该维度或断线即失效）。</p>
 */
public final class ClientBandTrain {
    private static volatile Level level;
    private static volatile double lastCenterX = Double.NaN;

    private ClientBandTrain() {
    }

    /** 收到服务端下发的质心 x；关卡已切换时先清掉旧值。 */
    public static void apply(double centerX) {
        Level current = Minecraft.getInstance().level;
        if (current == null) {
            return;
        }
        if (current != level) {
            level = current;
            lastCenterX = Double.NaN;
        }
        lastCenterX = centerX;
    }

    /** 当前关卡下白线要画的 x；从未收到过（或刚切关卡）→ empty，白线不画。 */
    public static OptionalDouble centerX(Level current) {
        if (current != level) {
            level = current;
            lastCenterX = Double.NaN;
        }
        // 本帧没有新的服务端结果，沿用上一次（需求 #6）。
        return BandTrainMath.keepLast(OptionalDouble.empty(), lastCenterX);
    }
}
