package dimblend.radio.client;

import dimblend.radio.RadioSignals;
import dimblend.radio.net.ClientRadioState;
import dimblend.radio.net.RadioHelloPayload;
import dimblend.radio.net.RadioStatePayload;
import dimblend.radio.mixin.client.SoundEngineAccessor;
import dimblend.radio.mixin.client.SoundManagerAccessor;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import dimblend.radio.DimBlendRadio;

/**
 * Server state selects the track; one local monotonic clock drives resume offsets and goggles.
 * That clock survives mute/device restarts and is discarded only when the play-through changes.
 * Every channel gets a unique PCM feed, even when several radios play the same file.
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID, value = Dist.CLIENT)
public final class RadioController {
    /** 同一曲 PCM 常驻内存上限：5 首 × ~50MB = 250MB 封顶，LRU 淘汰。 */
    private static final int PCM_CACHE_MAX = 5;

    private record LiveKey(String dimension, BlockPos pos) {
    }

    private record Live(RadioInstance instance, RadioPlayback playback, RadioPcmFeed.Handle feed,
            float gain, float boost) {
    }

    private record CachedPcm(javax.sound.sampled.AudioFormat format, byte[] pcm, float boost) {
    }

    private static final Map<LiveKey, Live> LIVE = new HashMap<>();
    private static final Map<LiveKey, RadioPlayback> PLAYBACKS = new HashMap<>();
    // Held until the main-thread completion runs, not merely until IO finishes.
    private static final Map<LiveKey, Object> REQUESTS = new HashMap<>();
    private static final java.util.LinkedHashMap<String, CachedPcm> PCM_CACHE =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, CachedPcm> eldest) {
                    return size() > PCM_CACHE_MAX;
                }
            };

    /** 正在解码的 hash（IO 池内去重：同一曲只解一次，并发信号风暴不叠解码任务）。 */
    private static final java.util.Set<String> DECODING =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static boolean helloSent;
    private static final Map<String, Boolean> MISSING_WARNED = new HashMap<>();

    public static void onStateChanged() {
        // 下一个客户端 tick 对账（S2C 在网络线程到达，这里只标记）
    }

    /** A device/resource reload is an interruption, even if OpenAL had buffered the final seconds. */
    public static void onSoundReload() {
        LIVE.values().forEach(live -> live.feed().release());
        LIVE.clear();
        REQUESTS.clear();
        // PLAYBACKS survives so the replacement channels resume without consulting gameTime.
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopAll();
            PLAYBACKS.clear();
            REQUESTS.clear();
            // 退到标题/换服：离线期间的删除收不到，旧状态留着会在重进后重播已拆的电台；
            // 进服时服务端补推全量（PlayerLoggedIn），这里清空不丢真实状态
            ClientRadioState.clear();
            // 退到标题/换服后重发 hello：否则服务端曲库时长永为 -1 兜底（180s），切歌时钟错乱
            helloSent = false;
            return;
        }
        if (!helloSent) {
            helloSent = true;
            sendHello();
        }
        // 只留当前维度：别的维度的删除广播收不到（见 ClientRadioState.retainDimension）
        ClientRadioState.retainDimension(mc.level.dimension().location().toString());
        long now = System.nanoTime();
        PLAYBACKS.values().forEach(playback -> playback.setPaused(mc.isPaused(), now));
        if (!mc.isPaused()) {
            reconcile(mc);
        }
    }

    private static void sendHello() {
        Util.ioPool().execute(() -> {
            try {
                RadioLibrary.scanNow();
                var catalog = RadioLibrary.catalogView();
                PacketDistributor.sendToServer(new RadioHelloPayload(catalog));
                int tracks = catalog.values().stream().mapToInt(java.util.Map::size).sum();
                DimBlendRadio.LOGGER.info("[radio] hello sent: {} stations, {} tracks", catalog.size(), tracks);
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] hello failed", e);
            }
        });
    }

    private static boolean matches(RadioPlayback playback, RadioStatePayload state) {
        return state != null && state.playing()
                && RadioAudibilityRules.validSignal(state.station(), state.side())
                && playback.matches(state.nonce(), state.trackHash(), state.startMillis());
    }

    private static RadioStatePayload stateOf(LiveKey key) {
        return ClientRadioState.view().get(new ClientRadioState.Key(key.dimension(), key.pos()));
    }

    private static void reconcile(Minecraft mc) {
        PLAYBACKS.entrySet().removeIf(e -> !matches(e.getValue(), stateOf(e.getKey())));
        if (RadioAudibility.categoryMuted(mc)) {
            stopAll(); // Keep the local clock: mute must not rewind a lagging server's track.
            return;
        }
        String dim = mc.level.dimension().location().toString();
        var it = LIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            LiveKey key = entry.getKey();
            Live live = entry.getValue();
            var state = stateOf(key);
            boolean active = mc.getSoundManager().isActive(live.instance());
            if (!matches(live.playback(), state) || !key.dimension().equals(dim)
                    || live.instance().isStopped() || !active) {
                // Read-ahead alone is NOT completion. The channel must have ended too.
                if (!active) {
                    live.playback().channelEnded(live.feed().exhausted());
                    if (live.feed().exhausted() && live.playback().finished(System.nanoTime())
                            && matches(live.playback(), state)) {
                        // 本地播完且服务端仍指当前曲：进入静默等待，下一首起播见 [radio] playing
                        DimBlendRadio.LOGGER.info(
                                "[radio] local finished, waiting for server advance: pos={} hash={} nonce={} start={}",
                                key.pos(), shortHash(state.trackHash()), state.nonce(), state.startMillis());
                    }
                }
                mc.getSoundManager().stop(live.instance());
                live.feed().release();
                it.remove();
                continue;
            }
            float gain = PcmHeadroom.channelGain(RadioSignals.volumePercent(state.side()), live.boost());
            if (gain != live.gain()) {
                live.instance().setVolume(gain);
                entry.setValue(new Live(live.instance(), live.playback(), live.feed(), gain, live.boost()));
            }
        }
        for (var stateEntry : ClientRadioState.view().entrySet()) {
            var key = stateEntry.getKey();
            var state = stateEntry.getValue();
            if (!key.dimension().equals(dim) || !state.playing()
                    || !RadioAudibilityRules.validSignal(state.station(), state.side())) {
                continue;
            }
            LiveKey liveKey = new LiveKey(key.dimension(), key.pos());
            if (!LIVE.containsKey(liveKey)) {
                startInstance(mc, liveKey, state);
            }
        }
    }

    /** Local decoded duration and playhead used by goggles. Null until local playback is prepared. */
    public record Progress(double elapsedSeconds, double totalSeconds, boolean finished) {
    }

    public static Progress progress(RadioStatePayload state) {
        LiveKey key = new LiveKey(state.dimension().toString(), state.pos());
        RadioPlayback playback = PLAYBACKS.get(key);
        if (playback == null || !matches(playback, state)) {
            return null;
        }
        long now = System.nanoTime();
        playback.setPaused(Minecraft.getInstance().isPaused(), now);
        return new Progress(playback.position(now), playback.duration(), playback.finished(now));
    }

    /** Zero for missing, pending, finished or stopped playback, even if the server says playing. */
    public static float playingGain(RadioStatePayload state) {
        Live live = LIVE.get(new LiveKey(state.dimension().toString(), state.pos()));
        if (live == null || !matches(live.playback(), state) || live.instance().isStopped()
                || live.playback().finished(System.nanoTime())) {
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        var engine = (SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager())
                .dimblend$radioSoundEngine();
        var channel = engine.dimblend$radioChannels().get(live.instance());
        if (channel == null || channel.isStopped()) {
            live.instance().setChannelPlaying(false);
            return 0;
        }
        // AL state must be queried on the sound executor, never directly on the client thread.
        // Sampling once per music tick adds at most a normal tick of takeover/release latency.
        channel.execute(source -> live.instance().setChannelPlaying(source.playing()));
        if (!live.instance().channelPlaying()) {
            return 0;
        }
        return engine.dimblend$radioVolume(live.instance())
                * mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER);
    }

    private static void startInstance(Minecraft mc, LiveKey key, RadioStatePayload state) {
        RadioPlayback playback = PLAYBACKS.get(key);
        if (REQUESTS.containsKey(key) || (playback != null && playback.finished(System.nanoTime()))) {
            return;
        }
        Path file = RadioLibrary.fileOf(state.trackHash());
        if (file == null) {
            warnMissing(state);
            return;
        }
        if (!DECODING.add(state.trackHash())) {
            return;
        }
        Object request = new Object();
        REQUESTS.put(key, request);
        var requestedLevel = mc.level;
        long prepNanos = System.nanoTime();
        double prepSec = trackSecondsNow(mc, state);
        Util.ioPool().execute(() -> {
            CachedPcm decodedPcm = null;
            try {
                synchronized (PCM_CACHE) {
                    decodedPcm = PCM_CACHE.get(state.trackHash());
                }
                if (decodedPcm == null) {
                    if (java.nio.file.Files.size(file) > 64L << 20) {
                        throw new IllegalArgumentException("Encoded track exceeds 64 MiB");
                    }
                    RadioLibrary.Pcm decoded = RadioLibrary.decode(file);
                    if (decoded.data().length > 256L << 20) {
                        throw new IllegalArgumentException("Decoded track exceeds 256 MiB");
                    }
                    float boost = PcmHeadroom.applyMaxBoost(decoded.data(),
                            RadioSignals.MAX_VOLUME_PERCENT / 100.0f);
                    decodedPcm = new CachedPcm(decoded.format(), decoded.data(), boost);
                    synchronized (PCM_CACHE) {
                        PCM_CACHE.put(state.trackHash(), decodedPcm);
                    }
                }
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] decode failed {}", file, e);
            } finally {
                DECODING.remove(state.trackHash());
            }
            CachedPcm pcm = decodedPcm;
            mc.execute(() -> {
                if (REQUESTS.get(key) != request) {
                    return; // disconnected/reconnected while decoding
                }
                try {
                    var current = stateOf(key);
                    if (pcm == null || mc.level != requestedLevel || current == null || !current.playing()
                            || !RadioAudibilityRules.validSignal(current.station(), current.side())
                            || current.nonce() != state.nonce() || current.startMillis() != state.startMillis()
                            || !current.trackHash().equals(state.trackHash())
                            || LIVE.containsKey(key) || mc.isPaused() || RadioAudibility.categoryMuted(mc)) {
                        return;
                    }
                    long now = System.nanoTime();
                    RadioPlayback local = PLAYBACKS.get(key);
                    boolean first = local == null || !matches(local, current);
                    if (first) {
                        double seconds = (double) pcm.pcm().length / pcm.format().getFrameSize()
                                / pcm.format().getSampleRate();
                        double offset = RadioStartOffset.offsetSec(prepSec,
                                prepSec + (now - prepNanos) / 1e9, null);
                        local = new RadioPlayback(state.nonce(), state.trackHash(), state.startMillis(),
                                seconds, offset, now);
                        PLAYBACKS.put(key, local);
                    }
                    // Re-check on completion: a decode queued before EOF may complete after it.
                    if (local.finished(now)) {
                        return;
                    }
                    double offset = local.position(now);
                    RadioPcmFeed.Handle feed = RadioPcmFeed.register(pcm.format(), pcm.pcm(), offset);
                    float gain = PcmHeadroom.channelGain(RadioSignals.volumePercent(current.side()), pcm.boost());
                    RadioInstance instance = new RadioInstance(mc.level, key.pos(), feed.id(), gain);
                    try {
                        mc.getSoundManager().play(instance);
                    } catch (RuntimeException e) {
                        feed.release();
                        throw e;
                    }
                    LIVE.put(key, new Live(instance, local, feed, gain, pcm.boost()));
                    DimBlendRadio.LOGGER.info("[radio] playing station={} pos={} hash={} nonce={} start={} offset={} duration={}",
                            current.station(), key.pos(), shortHash(state.trackHash()), state.nonce(), state.startMillis(), offset, local.duration());
                    if (first) {
                        announceTrack(mc, current.station(), state.trackHash());
                    }
                } finally {
                    REQUESTS.remove(key, request);
                }
            });
        });
    }

    /**
     * 此刻服务钟（RadioClock 毫秒）的曲内进度（秒）：serverNow（发包瞬间）+ 收包后本地真实流逝
     * 外推（{@link ClientRadioState#estimateServerNow}），掉刻不走慢、暂停双端同步冻结。
     */
    private static double trackSecondsNow(Minecraft mc, RadioStatePayload state) {
        var key = new ClientRadioState.Key(state.dimension().toString(), state.pos());
        long now = ClientRadioState.estimateServerNow(key, state);
        return Math.max(0.0, (now - state.startMillis()) / 1000.0);
    }

    /**
     * 缺文件只记日志（同 hash 只记一次）：提示位在护目镜弹窗（看唱片机即 RED 警示），
     * 不再刷聊天栏。
     */
    private static void warnMissing(RadioStatePayload state) {
        if (MISSING_WARNED.putIfAbsent(state.trackHash(), Boolean.TRUE) == null) {
            DimBlendRadio.LOGGER.warn(
                    "[radio] missing track {} for station {} — put the file in dimblend_radio/{}/",
                    state.trackHash(), state.station(), state.station());
        }
    }

    /**
     * 切曲 actionbar：曲名取元数据标题（无则文件名去扩展名），与护目镜弹窗同口径。
     * 缺文件时 displayName 为 null，不弹 actionbar（看唱片机时护目镜会 RED 提示）。
     */
    private static void announceTrack(Minecraft mc, int station, String trackHash) {
        if (mc.player == null) {
            return;
        }
        String name = RadioLibrary.displayName(trackHash);
        if (name == null) {
            return;
        }
        mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("[电台 " + station + "台] " + name), true);
    }

    private static String shortHash(String hash) {
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }

    private static void stopAll() {
        Minecraft mc = Minecraft.getInstance();
        for (Live live : LIVE.values()) {
            try {
                if (!mc.getSoundManager().isActive(live.instance())) {
                    live.playback().channelEnded(live.feed().exhausted());
                }
                mc.getSoundManager().stop(live.instance());
            } finally {
                live.feed().release();
            }
        }
        LIVE.clear();
    }

    private RadioController() {
    }
}
