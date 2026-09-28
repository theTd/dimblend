package dimblend.radio;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import dimblend.radio.server.RadioClock;
import dimblend.radio.server.RadioEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class RadioState {
    /** 曲间间隔：5 秒（tick 口径 = 100 tick = 5000 ms，切歌判定走 {@link RadioClock} 毫秒钟）。 */
    public static final int GAP_TICKS = 100;

    private static final Map<StationKey, Entry> STATES = new ConcurrentHashMap<>();

    public record StationKey(String dimension, BlockPos pos) {
    }

    /** @param startMillis 定曲瞬间的 {@link RadioClock} 读数（毫秒）；切歌时钟不用 gameTime（掉刻被拉长） */
    public record Entry(int station, int side, String trackHash, long startMillis, int nonce, boolean playing) {
        public boolean audible() {
            return playing && station >= RadioSignals.STATION_MIN && station <= RadioSignals.STATION_MAX && side > 0;
        }
    }

    public static Optional<Entry> get(String dimension, BlockPos pos) {
        return Optional.ofNullable(STATES.get(new StationKey(dimension, pos.immutable())));
    }

    public static java.util.List<BlockPos> keys(String dimension) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (StationKey key : STATES.keySet()) {
            if (key.dimension().equals(dimension)) {
                out.add(key.pos());
            }
        }
        return out;
    }

    public static void remove(ServerLevel level, BlockPos pos) {
        if (STATES.remove(new StationKey(level.dimension().location().toString(), pos.immutable())) != null) {
            RadioEvents.changed(level, pos);
        }
    }

    /**
     * 红石重算入口（服务端线程）：读 top/side，按真值表更新或删除状态。
     *
     * @param trackPicker 切歌时选下一曲 hash（去重当前曲）；同曲续播/仅调音量时不调用
     */
    public static void recompute(ServerLevel level, BlockPos pos, TrackPicker trackPicker) {
        String dim = level.dimension().location().toString();
        StationKey key = new StationKey(dim, pos.immutable());
        int top = RadioSignals.readTop(level, pos);
        int side = RadioSignals.readSide(level, pos);
        Entry prev = STATES.get(key);

        if (!RadioControl.isEmpty(level, pos) || !RadioSignals.isStation(top) || side <= 0) {
            // 旁路/有盘/静音：删状态（变化才广播）
            if (prev != null) {
                STATES.remove(key);
                dimblend.radio.server.RadioEvents.changed(level, pos);
            }
            return;
        }

        if (prev != null && prev.station() == top && prev.playing()
                && prev.trackHash() != null && !RadioControl.shouldAdvance(prev)) {
            // 同站：side 变化只跟量（updateSide 不动 nonce/不动钟），曲终才换
            if (prev.side() != side) {
                updateSide(level, pos);
            }
            return;
        }

        String track = prev != null && prev.station() == top && prev.trackHash() != null
                && !RadioControl.shouldAdvance(prev)
                        ? prev.trackHash()
                        : trackPicker.pickNext(top, prev == null ? null : prev.trackHash());
        int nonce = prev == null ? 0 : prev.nonce() + 1;
        Entry next = new Entry(top, side, track, RadioClock.now(), nonce, true);
        STATES.put(key, next);
        logSchedule("recompute", pos, next);
        RadioEvents.changed(level, pos);
    }

    /** 切歌推进：服务端 tick 发现曲终（startMillis+时长+GAP 到期）时调用。 */
    public static void advance(ServerLevel level, BlockPos pos, TrackPicker trackPicker) {
        StationKey key = new StationKey(level.dimension().location().toString(), pos.immutable());
        Entry prev = STATES.get(key);
        if (prev == null || !prev.playing()) {
            return;
        }
        String next = trackPicker.pickNext(prev.station(), prev.trackHash());
        Entry entry = new Entry(prev.station(), prev.side(), next, RadioClock.now(), prev.nonce() + 1, true);
        STATES.put(key, entry);
        logSchedule("advance", pos, entry);
        RadioEvents.changed(level, pos);
    }

    /** 立刻切台：top 变化时调用（不等曲终），新站洗牌、时钟重置。 */
    public static void advanceNow(ServerLevel level, BlockPos pos, int station, int side,
            TrackPicker trackPicker) {
        StationKey key = new StationKey(level.dimension().location().toString(), pos.immutable());
        Entry prev = STATES.get(key);
        String next = trackPicker.pickNext(station, null);
        int nonce = prev == null ? 0 : prev.nonce() + 1;
        Entry entry = new Entry(station, side, next, RadioClock.now(), nonce, true);
        STATES.put(key, entry);
        logSchedule("advanceNow", pos, entry);
        RadioEvents.changed(level, pos);
    }

    /** 纯音量/电平更新（不切歌、不动钟、不动 nonce）：side 变化时调用。 */
    public static void updateSide(ServerLevel level, BlockPos pos) {
        StationKey key = new StationKey(level.dimension().location().toString(), pos.immutable());
        Entry prev = STATES.get(key);
        if (prev == null) {
            return;
        }
        int side = RadioSignals.readSide(level, pos);
        if (side == prev.side()) {
            return;
        }
        if (side <= 0) {
            STATES.remove(key);
        } else {
            // nonce 保持：客户端 stale 判据含 nonce，不变即不重建实例、不重解码
            STATES.put(key, new Entry(prev.station(), side, prev.trackHash(), prev.startMillis(), prev.nonce(), true));
        }
        RadioEvents.changed(level, pos);
    }

    /**
     * 诊断日志：每次定曲打一行计划（曲长毫秒、预计切歌钟点）。due 与下一条 schedule 的 now
     * 对比即得“曲终后多久才切”：超出部分 = GAP + 轮询周期 + 时长高估。
     */
    private static void logSchedule(String reason, BlockPos pos, Entry entry) {
        if (entry.trackHash() == null) {
            return;
        }
        long lengthMillis = RadioCatalog.lengthTicks(entry.station(), entry.trackHash()) * 50L;
        DimBlendRadio.LOGGER.info(
                "[radio] schedule reason={} pos={} station={} hash={} nonce={} start={} length={}ms due={} now={}",
                reason, pos, entry.station(), RadioCatalog.shortHash(entry.trackHash()), entry.nonce(),
                entry.startMillis(), lengthMillis, entry.startMillis() + lengthMillis + GAP_TICKS * 50L,
                RadioClock.now());
    }

    public interface TrackPicker {
        /** 选下一曲 hash（排除 avoidHash，同站唯一曲时允许返回相同）。 */
        String pickNext(int station, String avoidHash);
    }

    private RadioState() {
    }
}
