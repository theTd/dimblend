package dimblend.experience.compat.tacz;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 客户端读「当前第一人称瞄准进度」（0..1），全部走反射：{@code tacz} 不在本模块编译 classpath 上
 * （见 {@code :dimblend-experience} 的 compileOnly 配置）。
 *
 * <p>唯一用途是判断「完全未开镜」——TaCZ 的 {@code BedrockAttachmentModel#renderOcularAndDivision}
 * 里 {@code rad = 80 * scopeViewRadiusModifier * aimingProgress}，未开镜时 {@code rad == 0}，
 * 但该方法仍会提交开镜后才该出现的准星/黑遮罩几何（靠模板测试丢弃）。判据取
 * {@code IClientPlayerGunOperator.fromLocalPlayer(player)#getClientAimingProgress(partialTick)}，
 * 与 TaCZ 自己算 {@code rad} 用的是同一个来源。</p>
 *
 * <p>反射失败时保守返回 {@code 1.0f}（当作正在瞄准）：宁可保留 TaCZ 原行为（模板测试照常工作），
 * 也不能因为读不到进度就把开镜后的准星全部跳过。</p>
 */
public final class ScopeAimProgress {

    private static Method fromLocalPlayer;
    private static Method getClientAimingProgress;
    private static boolean resolved;

    public static float current() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 1.0f;
        }
        try {
            resolve();
            if (fromLocalPlayer == null || getClientAimingProgress == null) {
                return 1.0f;
            }
            Object operator = fromLocalPlayer.invoke(null, player);
            if (operator == null) {
                return 1.0f;
            }
            float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            Object progress = getClientAimingProgress.invoke(operator, partialTick);
            return progress instanceof Float value ? value : 1.0f;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 1.0f;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> operator = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
            fromLocalPlayer = operator.getMethod("fromLocalPlayer", LocalPlayer.class);
            getClientAimingProgress = operator.getMethod("getClientAimingProgress", float.class);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            fromLocalPlayer = null;
            getClientAimingProgress = null;
        }
    }

    private ScopeAimProgress() {
    }
}
