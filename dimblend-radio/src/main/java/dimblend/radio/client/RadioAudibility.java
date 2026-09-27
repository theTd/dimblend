package dimblend.radio.client;

import java.util.Map;

import dimblend.radio.net.ClientRadioState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 电台可听性：任一 S2C 状态在玩家真零点半径内且 playing。
 *
 * <p>真零点 = LINEAR_DISTANCE 下 gain 归零的距离（= RANGE_BLOCKS，原版唱片机同为 64）。
 * 32 格是半音参考点（gain ≈ 0.5），不是截止点：32~64 格之间电台仍可闻，
 * vanilla 音乐必须等到真零点之外才允许播放。讲 decay 曲线，不讲开关。</p>
 */
public final class RadioAudibility {
    public static boolean anyAudible() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        String dim = mc.level.dimension().location().toString();
        Vec3 listener = mc.player.position();
        for (Map.Entry<ClientRadioState.Key, dimblend.radio.net.RadioStatePayload> entry : ClientRadioState.view()
                .entrySet()) {
            var key = entry.getKey();
            if (!key.dimension().equals(dim) || !entry.getValue().playing()) {
                continue;
            }
            if (listener.distanceTo(Vec3.atCenterOf(key.pos())) <= RadioInjector.RANGE_BLOCKS) {
                return true;
            }
        }
        return false;
    }

    private RadioAudibility() {
    }
}
