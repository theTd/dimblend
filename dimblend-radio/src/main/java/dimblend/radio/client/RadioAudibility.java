package dimblend.radio.client;

import java.util.Map;

import dimblend.radio.SubLevelProjection;
import dimblend.radio.net.ClientRadioState;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 电台可听性：信号有效、本端通道确实在播、有非零音量，并在声音监听器传播范围内。
 *
 * <p>真零点 = LINEAR_DISTANCE 下 gain 归零的距离（= RANGE_BLOCKS，原版唱片机同为 64）。
 * 32 格是半音参考点（gain ≈ 0.5），不是截止点：32~64 格之间电台仍可闻，
 * vanilla 音乐必须等到真零点之外才允许播放。讲 decay 曲线，不讲开关。</p>
 *
 * <p>唱片机/音符盒或主音量为 0 时电台整体不可闻（不压 vanilla 音乐、不建实例）。</p>
 */
public final class RadioAudibility {
    /**
     * 电台所在分类（{@link RadioInstance#SOURCE}）或主音量为 0。
     *
     * <p>此时引擎每 tick 掐掉电台通道（主音量 0 时根本不建通道），且被掐实例在
     * {@code SoundEngine.isActive} 里仍可能报活跃：不拦会每 tick 判 stale→重建→整曲 PCM 克隆，
     * 或把死实例留在对账表里、恢复音量后一直无声到下一曲。</p>
     */
    public static boolean categoryMuted(Minecraft mc) {
        return mc.options.getSoundSourceVolume(RadioInstance.SOURCE) <= 0.0f
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0f;
    }

    public static boolean anyAudible() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || categoryMuted(mc)) {
            return false;
        }
        String dim = mc.level.dimension().location().toString();
        Vec3 listener = mc.getSoundManager().getListenerTransform().position();
        for (Map.Entry<ClientRadioState.Key, dimblend.radio.net.RadioStatePayload> entry : ClientRadioState.view()
                .entrySet()) {
            var key = entry.getKey();
            var state = entry.getValue();
            if (!key.dimension().equals(dim) || !state.playing()
                    || !RadioAudibilityRules.validSignal(state.station(), state.side())) {
                continue;
            }
            // Sable 结构上的唱片机 pos 是 plot 坐标：投影到世界坐标再比距离
            double distance = listener.distanceTo(SubLevelProjection.worldCenter(mc.level, key.pos()));
            if (distance >= RadioInjector.RANGE_BLOCKS) {
                continue;
            }
            if (RadioAudibilityRules.shouldSuppress(state.station(), state.side(),
                    RadioController.playingGain(state), distance, RadioInjector.RANGE_BLOCKS)) {
                return true;
            }
        }
        return false;
    }

    private RadioAudibility() {
    }
}
