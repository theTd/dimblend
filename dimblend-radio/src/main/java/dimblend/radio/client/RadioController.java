package dimblend.radio.client;

import dimblend.radio.RadioSignals;
import dimblend.radio.SubLevelProjection;
import dimblend.radio.net.ClientRadioState;
import dimblend.radio.net.RadioHelloPayload;
import dimblend.radio.net.RadioStatePayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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
 * 客户端播放控制器：S2C 状态 → 本地实例。单 tick 全量对账（电台数极少）。
 *
 * <p>流程：状态表每项 → 玩家距离 >真零点(64) 不建实例；零点内按 (nonce,trackHash) 建/换实例：
 * 起播 offset 见 {@link RadioStartOffset}（在场开播从头播、允许落后服务钟 ≤4s；中途走近对齐服务钟；
 * 同曲重建沿用首次落后量）。服务钟进度 = max(本端同步的 level gameTime, 包内 serverNow) − startTick。
 * 实例播完（SoundEngine 通道 stopped + deleteTime 到期 → isActive false）→ 删本地记录，
 * 等服务端 advance 广播下一曲。5s 间隔由服务端 startTick 体现，客户端不另计时。</p>
 *
 * <p>缺文件 hash → 静默跳过 + 首次 warn（各客户端独立，用户自己补文件）。</p>
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID, value = Dist.CLIENT)
public final class RadioController {
    /** 建实例/保活半径 = 真零点（RANGE_BLOCKS=64）：衰减到零之前实例一直在，音乐压制同源。 */
    private static final double BUILD_RANGE = dimblend.radio.client.RadioInjector.RANGE_BLOCKS;
    /** 同一曲 PCM 常驻内存上限：5 首 × ~50MB = 250MB 封顶，LRU 淘汰。 */
    private static final int PCM_CACHE_MAX = 5;

    private record LiveKey(String dimension, BlockPos pos) {
    }

    private record Live(RadioInstance instance, String trackHash, int nonce, double seconds, float gain,
            float boost) {
    }

    /**
     * 按本曲余量线性预放大后的整曲 PCM（只读，各实例共用）与实际倍率（见 {@link PcmHeadroom}）。
     */
    private record CachedPcm(javax.sound.sampled.AudioFormat format, byte[] pcm, float boost) {
    }

    /** 本端某曲起播时落后服务钟的秒数（同曲重建沿用，接着播不重头）。 */
    private record StartLag(int nonce, String trackHash, double lagSec) {
    }

    private static final Map<LiveKey, Live> LIVE = new HashMap<>();
    /** 仅主线程读写：startInstance 提交任务前读，起播时写，reconcile 清理。 */
    private static final Map<LiveKey, StartLag> START_LAG = new HashMap<>();
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

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopAll();
            START_LAG.clear();
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
        reconcile(mc);
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

    private static void reconcile(Minecraft mc) {
        if (RadioAudibility.categoryMuted(mc)) {
            // 唱片机/音符盒或主音量为 0：不建实例；恢复音量后下一 tick 按服务钟 offset 续上
            stopAll();
            return;
        }
        String dim = mc.level.dimension().location().toString();
        Vec3 listener = mc.player.position();

        // 落后量只对同一 (nonce, track) 有效：状态消失或换曲即丢
        START_LAG.entrySet().removeIf(e -> {
            var state = ClientRadioState.view()
                    .get(new ClientRadioState.Key(e.getKey().dimension(), e.getKey().pos()));
            return state == null || state.nonce() != e.getValue().nonce()
                    || !state.trackHash().equals(e.getValue().trackHash());
        });

        // 清理：维度不符 / 状态消失 / 超距 / nonce 变化 / 播完 → 停实例。
        // 同 nonce 下 side 变化只跟通道增益，不重建、不重播。
        var it = LIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            LiveKey key = entry.getKey();
            Live live = entry.getValue();
            var state = ClientRadioState.view().get(new ClientRadioState.Key(key.dimension(), key.pos()));
            boolean stale = state == null || !state.playing() || !key.dimension().equals(dim)
                    || state.nonce() != live.nonce()
                    || !state.trackHash().equals(live.trackHash())
                    || listener.distanceTo(SubLevelProjection.worldCenter(mc.level, key.pos())) > BUILD_RANGE + 4
                    || live.instance().isStopped()
                    || !mc.getSoundManager().isActive(live.instance());
            if (stale) {
                mc.getSoundManager().stop(live.instance());
                it.remove();
                continue;
            }
            followSideGain(key, live, state);
        }
        for (var stateEntry : ClientRadioState.view().entrySet()) {
            var key = stateEntry.getKey();
            RadioStatePayload state = stateEntry.getValue();
            if (!key.dimension().equals(dim) || !state.playing()) {
                continue;
            }
            // Sable 结构上的唱片机 pos 是 plot 坐标（~2048 万格外）：按结构位姿投影后再比距离
            double dist = listener.distanceTo(SubLevelProjection.worldCenter(mc.level, key.pos()));
            if (dist > BUILD_RANGE) {
                continue;
            }
            LiveKey liveKey = new LiveKey(key.dimension(), key.pos());
            if (LIVE.containsKey(liveKey)) {
                continue; // 上面已对账（nonce/track 变化会先清）
            }
            startInstance(mc, liveKey, state);
        }
    }

    /**
     * side 跟量：PCM 解码时已按本曲余量预放大，各档（含跨 100%）只改实例通道增益，
     * 引擎下 tick 平滑生效——不换 PCM、不重建实例、不断音。
     */
    private static void followSideGain(LiveKey key, Live live, RadioStatePayload state) {
        float gain = PcmHeadroom.channelGain(RadioSignals.volumePercent(state.side()), live.boost());
        if (gain != live.gain()) {
            live.instance().setVolume(gain);
            LIVE.put(key, new Live(live.instance(), live.trackHash(), live.nonce(), live.seconds(), gain,
                    live.boost()));
        }
    }

    private static void startInstance(Minecraft mc, LiveKey key,
            dimblend.radio.net.RadioStatePayload state) {
        Path file = RadioLibrary.fileOf(state.trackHash());
        if (file == null) {
            warnMissing(state);
            return;
        }
        if (!DECODING.add(state.trackHash())) {
            return; // 同一曲正在解码，并发信号不叠任务
        }
        // 主线程取准备时刻的服务钟进度与已知落后量；IO 任务里按耗时推算起播时刻进度
        long prepNanos = System.nanoTime();
        double prepSec = trackSecondsNow(mc, state);
        StartLag lag = START_LAG.get(key);
        Double knownLag = lag != null && lag.nonce() == state.nonce() && lag.trackHash().equals(state.trackHash())
                ? lag.lagSec()
                : null;
        Util.ioPool().execute(() -> {
            try {
                CachedPcm cached;
                synchronized (PCM_CACHE) {
                    cached = PCM_CACHE.get(state.trackHash());
                }
                if (cached == null) {
                    // 单曲内存上限：>256MB 原始 PCM 拒绝解码（按 3 分钟 44.1k 立体声 ~32MB/分钟计）
                    long fileSize = java.nio.file.Files.size(file);
                    if (fileSize > 64L << 20) {
                        DimBlendRadio.LOGGER.warn("[radio] file too large, skip {}", file);
                        return;
                    }
                    RadioLibrary.Pcm decoded = RadioLibrary.decode(file);
                    if (decoded.data().length > 256L << 20) {
                        DimBlendRadio.LOGGER.warn("[radio] decoded too large, skip {}", file);
                        return;
                    }
                    // 解码时一次性按本曲余量线性预放大（最多 150%）入缓存；之后各档音量只调通道增益
                    float boost = PcmHeadroom.applyMaxBoost(decoded.data(),
                            RadioSignals.MAX_VOLUME_PERCENT / 100.0f);
                    cached = new CachedPcm(decoded.format(), decoded.data(), boost);
                    synchronized (PCM_CACHE) {
                        PCM_CACHE.put(state.trackHash(), cached);
                    }
                }
                double seconds = (double) cached.pcm().length / cached.format().getFrameSize()
                        / cached.format().getSampleRate();
                if (RadioStartOffset.offsetSec(prepSec, prepSec + secondsSince(prepNanos), knownLag) >= seconds) {
                    return; // 本端这曲已播完，服务端 advance 在路上：不做截取拷贝，等下一广播
                }
                // 起播进度 = 准备时进度 + 排队/解码耗时；offset 策略见 RadioStartOffset
                double playSec = prepSec + secondsSince(prepNanos);
                double offsetSec = RadioStartOffset.offsetSec(prepSec, playSec, knownLag);
                if (offsetSec >= seconds) {
                    return; // 服务端 advance 在路上，等下一广播
                }
                // 按 offset 截（offset=0 时与缓存共用同一数组、只读；曲中截出的是新数组，可淡入）
                byte[] play = skipPrefix(cached.format(), cached.pcm(), offsetSec);
                if (play != cached.pcm()) {
                    fadeIn(cached.format(), play);
                }
                // 音量只走通道增益（PCM 已按本曲余量预放大）；起播前按最新 side 取（见主线程块）
                float boost = cached.boost();
                // 实际落后量含截取耗时（同曲重建据此续播）
                double lagSec = prepSec + secondsSince(prepNanos) - offsetSec;
                RadioPcmFeed.put(state.trackHash(), cached.format(), play);
                mc.execute(() -> {
                    // 双重检查：对账期间状态可能已变
                    var current = ClientRadioState.view()
                            .get(new ClientRadioState.Key(key.dimension(), key.pos()));
                    if (current == null || !current.playing() || current.nonce() != state.nonce()) {
                        return;
                    }
                    if (mc.level == null || LIVE.containsKey(key) || RadioAudibility.categoryMuted(mc)) {
                        return;
                    }
                    float liveGain = PcmHeadroom.channelGain(RadioSignals.volumePercent(current.side()), boost);
                    // 主线程构造：声源要按客户端 level 里的 Sable 结构位姿投影到世界坐标
                    RadioInstance instance = new RadioInstance(mc.level, key.pos(), state.trackHash(), liveGain);
                    mc.getSoundManager().play(instance);
                    LIVE.put(key, new Live(instance, state.trackHash(), state.nonce(), seconds, liveGain, boost));
                    START_LAG.put(key, new StartLag(state.nonce(), state.trackHash(), lagSec));
                    DimBlendRadio.LOGGER.info("[radio] playing station={} gain={} pos={} hash={}", current.station(),
                            liveGain, key.pos(), shortHash(state.trackHash()));
                });
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] decode failed {}", file, e);
            } finally {
                DECODING.remove(state.trackHash());
            }
        });
    }

    /**
     * 此刻服务钟的曲内进度（秒）。本端 level gameTime 由服务端每 20 tick 同步、逐 tick 自增，
     * 与服务端同钟（各维度共用主世界 gameTime）；包内 serverNow 是发包瞬间的下界，最多 100 tick
     * 补推一次，走近时可能已旧。取两者较大者，进服首包前本端钟未同步也不会算小。
     */
    private static double trackSecondsNow(Minecraft mc, RadioStatePayload state) {
        long now = Math.max(mc.level.getGameTime(), state.serverNow());
        return Math.max(0.0, (now - state.startTick()) / 20.0);
    }

    private static double secondsSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000_000.0;
    }

    /** 曲中起播 10ms 线性淡入：从波形中段硬切起播会“啪”一声（16bit 小端，原地改写）。 */
    private static void fadeIn(javax.sound.sampled.AudioFormat format, byte[] pcm) {
        int frameSize = format.getFrameSize();
        int channels = format.getChannels();
        int frames = Math.min(pcm.length / frameSize, Math.round(format.getSampleRate() * 0.010f));
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int i = f * frameSize + c * 2;
                int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                int v = s * f / frames;
                pcm[i] = (byte) v;
                pcm[i + 1] = (byte) (v >> 8);
            }
        }
    }

    /** offset 起播：按秒换算帧数丢弃前缀（顺序跳过，无真 seek）。 */
    private static byte[] skipPrefix(javax.sound.sampled.AudioFormat format, byte[] data, double offsetSec) {
        int frameSize = format.getFrameSize();
        long skipFrames = (long) (offsetSec * format.getSampleRate());
        long skipBytes = Math.min((long) data.length, skipFrames * frameSize);
        skipBytes -= skipBytes % Math.max(1, frameSize);
        if (skipBytes <= 0) {
            return data;
        }
        // 大 offset 拷贝同样 O(n)：但只做一次（解码缓存命中后），不再每 tick 重复
        byte[] cut = new byte[(int) (data.length - skipBytes)];
        System.arraycopy(data, (int) skipBytes, cut, 0, cut.length);
        return cut;
    }

    private static void warnMissing(RadioStatePayload state) {
        if (MISSING_WARNED.putIfAbsent(state.trackHash(), Boolean.TRUE) == null) {
            DimBlendRadio.LOGGER.warn(
                    "[radio] missing track {} for station {} — put the file in dimblend_radio/{}/",
                    state.trackHash(), state.station(), state.station());
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§e[电台] 缺少曲目，请在 dimblend_radio/" + state.station() + "/ 下补文件"),
                        false);
            }
        }
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static void stopAll() {
        Minecraft mc = Minecraft.getInstance();
        for (Live live : LIVE.values()) {
            try {
                mc.getSoundManager().stop(live.instance());
            } catch (Exception ignored) {
            }
        }
        LIVE.clear();
    }

    private RadioController() {
    }
}
