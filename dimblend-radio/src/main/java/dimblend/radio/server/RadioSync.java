package dimblend.radio.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dimblend.radio.RadioCatalog;
import dimblend.radio.RadioControl;
import dimblend.radio.RadioSignals;
import dimblend.radio.RadioState;
import dimblend.radio.net.RadioHelloPayload;
import dimblend.radio.net.RadioStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import dimblend.radio.DimBlendRadio;

/**
 * 服务端同步：红石变化（BlockEvent 订阅）→ 重算 → 定向广播；登录/周期性补推；曲终推进。
 *
 * <p>广播范围：电台所在维度全体玩家（客户端按距离自行决定是否可听，32 格外不建实例）。
 * 全量推送（状态表很小：有源电台数）。</p>
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID)
public final class RadioSync {
    private record SignalReading(String dimension, BlockPos pos, int top, int side, boolean empty) {
    }

    /** 上一条已记日志的信号读数（仅服务端线程读写）。 */
    private static SignalReading lastLoggedReading;

    /**
     * 唱片机作为通知方：自身状态变化（插/取唱片翻 HAS_RECORD）、贴附元件与红石线通知其邻居时触发。
     * 相邻输入变化（贴侧红石块、落地拉杆等不以唱片机为通知方的）由
     * {@code JukeboxRedstoneInputMixin.neighborChanged} 即时覆盖；两路重叠时 recompute 幂等。
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && level.getBlockState(event.getPos()).is(Blocks.JUKEBOX)) {
            onNeighborChanged(level, event.getPos());
        }
    }

    /** 放置后初算：只处理唱片机位置（原 mixin onPlace 的事件版）。 */
    @SubscribeEvent
    public static void onJukeboxPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getPlacedBlock().is(Blocks.JUKEBOX)) {
            onNeighborChanged(level, event.getPos());
        }
    }


    /** 唱片机邻居变化/放置后重算入口（neighborChanged mixin、事件订阅与内部调用，服务端线程）。 */
    public static void onNeighborChanged(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.JUKEBOX)) {
            RadioState.remove(level, pos);
            return;
        }
        RadioCatalog.ensureServerScannedSync();
        int top = dimblend.radio.RadioSignals.readTop(level, pos);
        int side = dimblend.radio.RadioSignals.readSide(level, pos);
        boolean empty = dimblend.radio.RadioControl.isEmpty(level, pos);
        SignalReading reading = new SignalReading(level.dimension().location().toString(), pos.immutable(),
                top, side, empty);
        if (!reading.equals(lastLoggedReading)) {
            // 一次输入变化会经 neighborChanged 与 NeighborNotify 多次进来：连续同读数只记一行
            lastLoggedReading = reading;
            DimBlendRadio.LOGGER.info("[radio] signal pos={} dim={} top={} side={} empty={}", pos,
                    level.dimension().location(), top, side, empty);
        }
        RadioState.recompute(level, pos, (station, avoid) -> RadioCatalog.pickNext(station, avoid, level.random));
    }

    /** mixin 回调：唱片插入/取出后调用（setTheItem 必经点，有盘即删状态）。 */
    public static void onItemChanged(ServerLevel level, BlockPos pos) {
        if (!RadioControl.isEmpty(level, pos)) {
            RadioState.remove(level, pos);
            return;
        }
        onNeighborChanged(level, pos);
    }

    /** 定向广播某台电台状态（删除也广播 playing=false，客户端清实例）。 */
    public static void broadcast(ServerLevel level, BlockPos pos) {
        ResourceLocation dim = level.dimension().location();
        long now = level.getGameTime();
        var entry = RadioState.get(dim.toString(), pos);
        RadioStatePayload payload;
        if (entry.isEmpty()) {
            payload = new RadioStatePayload(dim, pos, 0, 0, "", 0, 0, false, now);
        } else {
            var e = entry.get();
            payload = new RadioStatePayload(dim, pos, e.station(), e.side(),
                    e.trackHash() == null ? "" : e.trackHash(), e.startTick(), e.nonce(), e.playing(), now);
        }
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** C2S hello：合并曲库并集（只增不减，时长取 max），然后全量补推一次。 */
    public static void onHello(MinecraftServer server, RadioHelloPayload hello) {
        RadioCatalog.mergeHello(hello.stations());
        for (ServerLevel level : server.getAllLevels()) {
            pushAll(level);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.serverLevel() instanceof ServerLevel level) {
            pushAll(level);
        }
    }

    /** 20 tick 看门狗（切台/调音量即时）+ 100 tick 补推与曲终推进。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            discoverLoadedJukeboxes(level);
            watchdog(level);
            if (server.getTickCount() % 100 == 0) {
                advanceFinished(level);
                pushAll(level);
            }
        }
    }

    /**
     * 进服/走近自动发现：已加载区块里的唱片机只要信号正确就应当播，
     * 不依赖 NeighborNotify 事件是否送达（chunk load、login、红石边沿丢失全兜底）。
     * 做法：每 20 tick 扫已加载区块的唱片机（maybeHas 预过滤，空 section 跳过），
     * 无状态但信号正确 → recompute 建状态；有状态的由 watchdog 跟进。
     */
    private static void discoverLoadedJukeboxes(ServerLevel level) {
        String dim = level.dimension().location().toString();
        // 玩家周围半径 72 格的唱片机：不碰 protected chunkMap，
        // 用 getChunkNow 逐区块取 ticking chunk（缺失返回 null，天然跳过未加载区）。
        final int R = 72;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (ServerPlayer player : level.players()) {
            net.minecraft.world.level.ChunkPos center =
                    new net.minecraft.world.level.ChunkPos(player.blockPosition());
            int cr = (R >> 4) + 1;
            for (int cx = center.x - cr; cx <= center.x + cr; cx++) {
                for (int cz = center.z - cr; cz <= center.z + cr; cz++) {
                    if (!seen.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) {
                        continue;
                    }
                    net.minecraft.world.level.chunk.LevelChunk chunk =
                            level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    scanChunkForJukeboxes(level, dim, chunk);
                }
            }
        }
    }

    private static void scanChunkForJukeboxes(ServerLevel level, String dim,
            net.minecraft.world.level.chunk.LevelChunk chunk) {
        net.minecraft.world.level.ChunkPos chunkPos = chunk.getPos();
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            net.minecraft.world.level.chunk.LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()
                    || !section.maybeHas(state -> state.is(Blocks.JUKEBOX))) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(si) * 16;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        if (!section.getBlockState(x, y, z).is(Blocks.JUKEBOX)) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + x, baseY + y,
                                chunkPos.getMinBlockZ() + z);
                        if (RadioState.get(dim, pos).isEmpty()
                                && RadioControl.isEmpty(level, pos)) {
                            int top = RadioSignals.readTop(level, pos);
                            int side = RadioSignals.readSide(level, pos);
                            if (RadioSignals.isStation(top) && side > 0) {
                                RadioState.recompute(level, pos,
                                        (station, avoid) -> RadioCatalog.pickNext(station, avoid,
                                                level.random));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 切台看门狗（20 tick 一次）：top 变化必须切歌，不能等曲终。
     * recompute 的“同站续播不广播”只在同站时成立，切台走 advanceNow 立刻换。
     */
    private static void watchdog(ServerLevel level) {
        for (BlockPos pos : RadioState.keys(level.dimension().location().toString())) {
            var entry = RadioState.get(level.dimension().location().toString(), pos);
            if (entry.isEmpty()) {
                continue;
            }
            var e = entry.get();
            if (!RadioControl.isEmpty(level, pos)) {
                RadioState.remove(level, pos);
                continue;
            }
            int top = dimblend.radio.RadioSignals.readTop(level, pos);
            int side = dimblend.radio.RadioSignals.readSide(level, pos);
            if (!dimblend.radio.RadioSignals.isStation(top) || side <= 0) {
                RadioState.remove(level, pos);
                continue;
            }
            if (top != e.station()) {
                RadioState.advanceNow(level, pos, top, side,
                        (station, avoid) -> RadioCatalog.pickNext(station, avoid, level.random));
            } else if (side != e.side()) {
                RadioState.updateSide(level, pos);
            }
        }
    }

    private static void advanceFinished(ServerLevel level) {
        // 推进曲终电台：遍历状态表（小表，100 tick 一次可接受）
        List<BlockPos> toAdvance = new ArrayList<>();
        // RadioState 内部遍历接口缺失时，用广播全量覆盖代替推进判断：
        // 客户端按 startTick+本地时长自行停播并等待服务端 advance 广播。
        // 此处保留推进点：逐台检查 shouldAdvance。
        for (var key : snapshotKeys(level)) {
            var entry = RadioState.get(level.dimension().location().toString(), key);
            if (entry.isPresent() && entry.get().playing()
                    && RadioControl.isEmpty(level, key)
                    && RadioControl.shouldAdvance(level, key, entry.get())) {
                toAdvance.add(key);
            }
        }
        for (BlockPos pos : toAdvance) {
            RadioState.advance(level, pos,
                    (station, avoid) -> RadioCatalog.pickNext(station, avoid, level.random));
        }
    }

    private static List<BlockPos> snapshotKeys(ServerLevel level) {
        String dim = level.dimension().location().toString();
        List<BlockPos> out = new ArrayList<>();
        // 状态表按维度过滤：RadioState 未暴露遍历，改为广播全量时客户端自清理。
        // 为推进曲终，这里需要遍历——见 RadioState.keys(dim)。
        for (BlockPos pos : RadioState.keys(dim)) {
            out.add(pos);
        }
        return out;
    }

    private static void pushAll(ServerLevel level) {
        for (BlockPos pos : snapshotKeys(level)) {
            broadcast(level, pos);
        }
    }

    private RadioSync() {
    }
}
