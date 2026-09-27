package dimblend.radio.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端状态缓存（volatile 语义）：S2C 下发的电台真相，播放器只读这里。
 *
 * <p>key = dimension + pos。nonce 保证同曲重播不被去重吞掉；playing=false 或缺失 = 停播。</p>
 */
public final class ClientRadioState {
    private static final Map<Key, RadioStatePayload> STATES = new ConcurrentHashMap<>();

    public record Key(String dimension, BlockPos pos) {
    }

    public static void apply(RadioStatePayload payload) {
        Key key = new Key(payload.dimension().toString(), payload.pos().immutable());
        if (!payload.playing()) {
            STATES.remove(key);
        } else {
            STATES.put(key, payload);
        }
        dimblend.radio.client.RadioController.onStateChanged();
    }

    public static Map<Key, RadioStatePayload> view() {
        return STATES;
    }

    public static void clear() {
        STATES.clear();
    }

    private ClientRadioState() {
    }
}
