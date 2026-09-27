package dimblend.radio.client;

import dimblend.radio.RadioSignals;
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
 * offset=(serverNow - startTick)/20 + 任务耗时起播（服务钟对齐，多端进度一致）。
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
            boolean boosted) {
    }

    private record CachedPcm(javax.sound.sampled.AudioFormat format, byte[] raw) {
    }

    private static final Map<LiveKey, Live> LIVE = new HashMap<>();
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
            return;
        }
        if (!helloSent) {
            helloSent = true;
            sendHello();
        }
        reconcile(mc);
    }

    private static void sendHello() {
        Util.ioPool().execute(() -> {
            try {
                RadioLibrary.scanNow();
                PacketDistributor.sendToServer(new RadioHelloPayload(RadioLibrary.catalogView()));
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] hello failed", e);
            }
        });
    }

    private static void reconcile(Minecraft mc) {
        String dim = mc.level.dimension().location().toString();
        Vec3 listener = mc.player.position();

        // 清理：维度不符 / 状态消失 / 超距 / nonce 变化 / 播完 → 停实例。
        // 同 nonce 下 side 变化只跟增益（同段）或重建（跨 100% 边界），不清 nonce 不重播。
        var it = LIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            LiveKey key = entry.getKey();
            Live live = entry.getValue();
            var state = ClientRadioState.view().get(new ClientRadioState.Key(key.dimension(), key.pos()));
            boolean stale = state == null || !state.playing() || !key.dimension().equals(dim)
                    || state.nonce() != live.nonce()
                    || !state.trackHash().equals(live.trackHash())
                    || listener.distanceTo(Vec3.atCenterOf(key.pos())) > BUILD_RANGE + 4
                    || live.instance().isStopped()
                    || !mc.getSoundManager().isActive(live.instance());
            if (stale) {
                mc.getSoundManager().stop(live.instance());
                it.remove();
                continue;
            }
            followSideGain(mc, key, live, state);
        }
        for (var stateEntry : ClientRadioState.view().entrySet()) {
            var key = stateEntry.getKey();
            RadioStatePayload state = stateEntry.getValue();
            if (!key.dimension().equals(dim) || !state.playing()) {
                continue;
            }
            double dist = listener.distanceTo(Vec3.atCenterOf(key.pos()));
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
     * side 跟量：同段（同为 ≤100% 或同为 >100%）只改实例 volume，实时生效不重播；
     * 跨 100% 边界（PCM 要换预放大）才停旧实例、由下一 tick 重建（nonce 同、track 同，
     * offset 按服务钟续上，不是从头播）。
     */
    private static void followSideGain(Minecraft mc, LiveKey key, Live live,
            dimblend.radio.net.RadioStatePayload state) {
        float boost = RadioSignals.pcmBoost(state.side());
        float gain = Math.min(boost, 1.0f);
        boolean boosted = boost > 1.0f;
        if (boosted == live.boosted()) {
            if (gain != live.gain()) {
                live.instance().setVolume(gain);
                LIVE.put(key, new Live(live.instance(), live.trackHash(), live.nonce(), live.seconds(),
                        gain, live.boosted()));
            }
            return;
        }
        // 跨段：停旧实例，清 LIVE，下一 tick startInstance 按新段重建（同 nonce 续进度）
        mc.getSoundManager().stop(live.instance());
        LIVE.remove(key);
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
        Util.ioPool().execute(() -> {
            // 解码计时起点：缓存命中≈0，冷解码=真实耗时，对齐服务钟用
            long taskStartNanos = System.nanoTime();
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
                    RadioLibrary.Pcm decoded = RadioLibrary.decode(file, 1.0f);
                    if (decoded.data().length > 256L << 20) {
                        DimBlendRadio.LOGGER.warn("[radio] decoded too large, skip {}", file);
                        return;
                    }
                    synchronized (PCM_CACHE) {
                        PCM_CACHE.put(state.trackHash(), new CachedPcm(decoded.format(), decoded.data()));
                    }
                    cached = new CachedPcm(decoded.format(), decoded.data());
                }
                // side 音量分两段：≤100%（side≤10）走实例 volume（实时通道增益，
                // 不重解码）；>100%（side 11~15）走 PCM 预放大（重播一次换 PCM，
                // 跨 100% 边界时才重建实例，同段内只调增益）。
                float boost = RadioSignals.pcmBoost(state.side());
                float gain = Math.min(boost, 1.0f);
                byte[] play = cached.raw().clone();
                if (boost > 1.0f) {
                    RadioLibrary.applyBoostInPlace(play, boost);
                }
                // 服务钟对齐：offset 基准是发包瞬间 serverNow + 本任务已耗（解码/排队）时间。
                // A（常驻，缓存命中≈0）和 B（刚走近，冷解码慢 5~10s）落到同一服务进度。
                double taskSec = (System.nanoTime() - taskStartNanos) / 1_000_000_000.0;
                double seconds = (double) play.length / cached.format().getFrameSize()
                        / cached.format().getSampleRate();
                double offsetSec = Math.max(0, (state.serverNow() - state.startTick()) / 20.0 + taskSec);
                if (offsetSec >= seconds) {
                    return; // 服务端 advance 在路上，等下一广播
                }
                byte[] cut = skipPrefix(cached.format(), play, offsetSec);
                RadioPcmFeed.put(state.trackHash(), cached.format(), cut);
                RadioInstance instance = new RadioInstance(key.pos(), state.trackHash(), gain);
                mc.execute(() -> {
                    // 双重检查：对账期间状态可能已变
                    var current = ClientRadioState.view()
                            .get(new ClientRadioState.Key(key.dimension(), key.pos()));
                    if (current == null || !current.playing() || current.nonce() != state.nonce()) {
                        return;
                    }
                    if (LIVE.containsKey(key)) {
                        return;
                    }
                    mc.getSoundManager().play(instance);
                    LIVE.put(key, new Live(instance, state.trackHash(), state.nonce(), seconds, gain,
                            boost > 1.0f));
                });
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] decode failed {}", file, e);
            } finally {
                DECODING.remove(state.trackHash());
            }
        });
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
