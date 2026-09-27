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
            // 删除也只在真有状态时记一行：100 tick 补推的重复 stop 不刷屏
            if (STATES.remove(key) != null) {
                dimblend.radio.DimBlendRadio.LOGGER.info("[radio] stopped {} (server)", key.pos());
            }
        } else {
            RadioStatePayload prev = STATES.put(key, payload);
            // 同 nonce 的重复推送（补推/调音量）不记：只记开播与切歌
            if (prev == null || prev.nonce() != payload.nonce()) {
                String hash = payload.trackHash();
                dimblend.radio.DimBlendRadio.LOGGER.info("[radio] state station={} side={} pos={} hash={}",
                        payload.station(), payload.side(), key.pos(),
                        hash.length() <= 12 ? hash : hash.substring(0, 12));
            }
        }
        dimblend.radio.client.RadioController.onStateChanged();
    }

    public static Map<Key, RadioStatePayload> view() {
        return STATES;
    }

    public static void clear() {
        STATES.clear();
    }

    /**
     * 只保留当前维度的状态。服务端只向电台所在维度的玩家广播：人在别的维度时那边电台被拆，
     * 删除收不到，旧状态留着会在回来后按服务钟把已拆电台的余下曲目再播一遍。
     * 回到该维度时服务端立即补推全量（换维度/重生事件），真实状态不丢。
     */
    public static void retainDimension(String dimension) {
        STATES.keySet().removeIf(key -> !key.dimension().equals(dimension));
    }

    private ClientRadioState() {
    }
}
