package dimblend.blocks.compat.rail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 列车加压管道的网络管理：分量 BFS、跨车厢连接的生命周期、共用液池读写、S2C 同步。
 *
 * <p>几条硬规则（与 {@link TrainTubeNetworkMath} 的单测一一对应）：</p>
 * <ul>
 *   <li>整条分量（含跨车厢另一端）共用一个 <b>100 mB</b> 池；</li>
 *   <li><b>任何</b>拓扑变更（加/拆方块、建/断连接）都把受影响分量的池清空；</li>
 *   <li>端点资格 = 本地连接数恰好 1；跨车厢只允许端点对端点，且两端所在子层级必须直接挂接、
 *       世界距离 ≤ 16 格；</li>
 *   <li>所有方块读取都走 {@code getChunkSource().getChunkNow(...)}（{@code dimblend-dev} §8.28），
 *       绝不在 tick 路径上阻塞加载区块。</li>
 * </ul>
 */
public final class TrainTubeNetworkManager {

    /** 单次 BFS 的节点上限：再长就按"截断"处理，不清池以免误合并/误清空。 */
    private static final int MAX_NODES = 1024;

    /** 每个维度每 tick 最多处理的拓扑变更数，剩下的排到下一 tick。 */
    private static final int PENDING_BUDGET = 32;

    /**
     * 每 tick 复核时的<b>断开</b>阈值，比建链阈值（{@code TrainTubeNetworkMath#MAX_LINK_DISTANCE} = 16）
     * 宽 2 格：车钩物理抖动会让两端世界距离轻微变化，用同一个 16 会让已建连接反复掉线又重连。
     */
    private static final double LINK_DROP_DISTANCE = 18.0;

    private static final Map<ResourceKey<Level>, Set<BlockPos>> PENDING = new HashMap<>();

    private static final Map<Long, Component> CACHE = new HashMap<>();
    private static long cacheGameTime = Long.MIN_VALUE;
    private static ServerLevel cacheLevel;

    private TrainTubeNetworkManager() {
    }

    // ------------------------------------------------------------------ 结构类型

    /** 分量里的一个节点：位置 + 所在的 Sable 子层级。 */
    public record Node(BlockPos pos, UUID subLevel) {
    }

    /** 一次 BFS 的结果。{@code key} 是分量内字典序最小的节点键，充当液池的规范锚点。 */
    public record Component(String key, List<Node> nodes, Set<TrainTubeLink> links, boolean truncated) {
    }

    // ------------------------------------------------------------------ tick 入口

    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        flushPending(level);
        validateLinks(level);
    }

    /** 拓扑可能变了（连接位翻转、方块被拆）：记下来，等本 tick 末统一清池。 */
    public static void markPending(ServerLevel level, BlockPos pos) {
        PENDING.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos.immutable());
        invalidateCache();
    }

    private static void flushPending(ServerLevel level) {
        Set<BlockPos> pending = PENDING.get(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Iterator<BlockPos> iterator = pending.iterator();
        int budget = PENDING_BUDGET;
        while (iterator.hasNext() && budget-- > 0) {
            BlockPos pos = iterator.next();
            iterator.remove();
            onTopologyChanged(level, pos);
        }
        if (pending.isEmpty()) {
            PENDING.remove(level.dimension());
        }
        pruneInvalidPools(level);
    }

    // ------------------------------------------------------------------ 拓扑变更

    /** 清空 {@code pos} 所在分量的池，并剪掉锚在它身上的失效连接。 */
    public static void onTopologyChanged(ServerLevel level, BlockPos pos) {
        invalidateCache();
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        List<TrainTubeLink> removed = data.removeLinksAtPos(pos);
        for (TrainTubeLink link : removed) {
            BlockPos other = link.posA().equals(pos) ? link.posB() : link.posA();
            if (stateOrNull(level, other) != null) {
                clearComponentPool(level, other);
            }
        }
        clearComponentPool(level, pos);
        if (!removed.isEmpty()) {
            syncLinks(level);
        }
    }

    /** 建连接：先按各自的旧拓扑清池（连接表还没变），再落库。 */
    public static void addLink(ServerLevel level, TrainTubeLink link) {
        clearComponentPool(level, link.posA());
        clearComponentPool(level, link.posB());
        invalidateCache();
        TrainTubeSavedData.get(level).addLink(link);
        syncLinks(level);
    }

    /** 断连接：先从表里摘掉（让两侧重新分裂），再分别清池。 */
    public static void dropLink(ServerLevel level, TrainTubeLink link) {
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        if (!data.removeLink(link)) {
            return;
        }
        invalidateCache();
        if (stateOrNull(level, link.posA()) != null) {
            clearComponentPool(level, link.posA());
        }
        if (stateOrNull(level, link.posB()) != null) {
            clearComponentPool(level, link.posB());
        }
        syncLinks(level);
    }

    /** 拆掉锚在某个端点上的所有连接（方块被拆时由 {@code onRemove} 直接调；此处不再清 pos 侧的池）。 */
    public static List<TrainTubeLink> removeLinksAt(ServerLevel level, UUID subLevel, BlockPos pos) {
        List<TrainTubeLink> removed = TrainTubeSavedData.get(level).removeLinksAt(subLevel, pos);
        if (removed.isEmpty()) {
            return removed;
        }
        invalidateCache();
        for (TrainTubeLink link : removed) {
            BlockPos other = link.posA().equals(pos) ? link.posB() : link.posA();
            if (stateOrNull(level, other) != null) {
                clearComponentPool(level, other);
            }
        }
        syncLinks(level);
        return removed;
    }

    // ------------------------------------------------------------------ 每 tick 复核

    private static void validateLinks(ServerLevel level) {
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        if (data.links().isEmpty()) {
            return;
        }
        List<TrainTubeLink> broken = new ArrayList<>();
        for (TrainTubeLink link : data.links()) {
            if (!isLinkStillValid(level, link)) {
                broken.add(link);
            }
        }
        if (broken.isEmpty()) {
            return;
        }
        for (TrainTubeLink link : broken) {
            data.removeLink(link);
            clearEndpoint(level, link.posA());
            clearEndpoint(level, link.posB());
        }
        invalidateCache();
        syncLinks(level);
    }

    private static boolean isLinkStillValid(ServerLevel level, TrainTubeLink link) {
        SubLevel first = TrainTubeSable.subLevel(level, link.subA());
        SubLevel second = TrainTubeSable.subLevel(level, link.subB());
        if (first == null || second == null) {
            return false; // 子层级已被移除
        }
        BlockState stateA = stateOrNull(level, link.posA());
        BlockState stateB = stateOrNull(level, link.posB());
        if (stateA == null || stateB == null) {
            return true; // 区块没加载：先留着，加载后再判
        }
        if (!TrainTubes.isTube(stateA) || !TrainTubes.isTube(stateB)) {
            return false;
        }
        if (!TrainTubeNetworkMath.isValidEndpoint(connectionCount(stateA))
                || !TrainTubeNetworkMath.isValidEndpoint(connectionCount(stateB))) {
            return false;
        }
        if (!TrainTubeSable.directlyCoupled(level, link.subA(), link.subB())) {
            return false;
        }
        Vec3 firstWorld = TrainTubeSable.toWorld(level, link.posA());
        Vec3 secondWorld = TrainTubeSable.toWorld(level, link.posB());
        double dx = firstWorld.x - secondWorld.x;
        double dy = firstWorld.y - secondWorld.y;
        double dz = firstWorld.z - secondWorld.z;
        return dx * dx + dy * dy + dz * dz <= LINK_DROP_DISTANCE * LINK_DROP_DISTANCE;
    }

    private static void clearEndpoint(ServerLevel level, BlockPos pos) {
        if (stateOrNull(level, pos) != null) {
            clearComponentPool(level, pos);
        }
    }

    /** 池锚点已不是管道的条目一律丢掉（方块拆掉后旧池的兜底清理）。 */
    private static void pruneInvalidPools(ServerLevel level) {
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        List<String> stale = new ArrayList<>();
        for (String key : data.poolKeys()) {
            BlockPos anchor = parseNodePos(key);
            if (anchor == null) {
                stale.add(key);
                continue;
            }
            BlockState state = stateOrNull(level, anchor);
            if (state == null) {
                continue; // 未加载：保留
            }
            if (!TrainTubes.isTube(state)) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            data.removePool(key);
            invalidateCache();
        }
    }

    // ------------------------------------------------------------------ 液池

    public static FluidStack pool(ServerLevel level, BlockPos pos) {
        Component component = trace(level, pos);
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        FluidStack stack = data.pool(component.key());
        if (stack != null) {
            return stack;
        }
        // 收养孤儿池：部分加载导致锚点漂移到同分量的另一个节点时，把旧池搬过来
        for (Node node : component.nodes()) {
            String nodeKey = nodeKey(node.pos(), node.subLevel());
            FluidStack orphan = data.pool(nodeKey);
            if (orphan != null) {
                data.removePool(nodeKey);
                data.putPool(component.key(), orphan);
                return orphan;
            }
        }
        return FluidStack.EMPTY;
    }

    public static void storePool(ServerLevel level, BlockPos pos, FluidStack stack) {
        Component component = trace(level, pos);
        TrainTubeSavedData.get(level).putPool(component.key(), stack);
    }

    /** 清空 {@code pos} 所在分量的池（拓扑变更的统一动作）。 */
    public static void clearComponentPool(ServerLevel level, BlockPos pos) {
        if (stateOrNull(level, pos) == null) {
            return;
        }
        Component component = trace(level, pos);
        TrainTubeSavedData.get(level).putPool(component.key(), FluidStack.EMPTY);
        invalidateCache();
    }

    // ------------------------------------------------------------------ 分量 BFS

    public static Component trace(ServerLevel level, BlockPos start) {
        long gameTime = level.getGameTime();
        if (level != cacheLevel || gameTime != cacheGameTime) {
            CACHE.clear();
            cacheLevel = level;
            cacheGameTime = gameTime;
        }
        return CACHE.computeIfAbsent(start.asLong(), key -> traceUncached(level, start));
    }

    public static void invalidateCache() {
        CACHE.clear();
        cacheGameTime = Long.MIN_VALUE;
        cacheLevel = null;
    }

    private static Component traceUncached(ServerLevel level, BlockPos start) {
        UUID startSubLevel = TrainTubeSable.subLevelId(level, start);
        if (startSubLevel == null) {
            // 不在列车结构上：自成孤点
            Node lone = new Node(start.immutable(), null);
            return new Component(nodeKey(start, null), List.of(lone), Set.of(), true);
        }

        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        Set<Node> nodes = new LinkedHashSet<>();
        Set<TrainTubeLink> usedLinks = new LinkedHashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        boolean truncated = false;

        Node first = new Node(start.immutable(), startSubLevel);
        nodes.add(first);
        queue.add(first);

        while (!queue.isEmpty()) {
            if (nodes.size() >= MAX_NODES) {
                truncated = true;
                break;
            }
            Node node = queue.poll();
            BlockState nodeState = stateOrNull(level, node.pos());
            if (nodeState == null || !TrainTubes.isTube(nodeState)) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                if (!TrainPressuredTubeBlock.isConnected(nodeState, direction)) {
                    continue;
                }
                BlockPos neighbor = node.pos().relative(direction);
                BlockState neighborState = stateOrNull(level, neighbor);
                if (neighborState == null) {
                    truncated = true;
                    continue;
                }
                if (!TrainTubes.isTube(neighborState)) {
                    continue;
                }
                Node next = new Node(neighbor.immutable(), TrainTubeSable.subLevelId(level, neighbor));
                if (next.subLevel() != null && nodes.add(next)) {
                    queue.add(next);
                }
            }

            if (!TrainTubeNetworkMath.isValidEndpoint(connectionCount(nodeState))) {
                continue;
            }
            for (TrainTubeLink link : data.links()) {
                if (usedLinks.contains(link) || !link.anchoredAt(node.subLevel(), node.pos())) {
                    continue;
                }
                UUID otherSubLevel = link.otherSubLevel(node.subLevel(), node.pos());
                BlockPos otherPos = link.otherPos(node.subLevel(), node.pos());
                Node partner = new Node(otherPos.immutable(), otherSubLevel);
                if (nodes.add(partner)) {
                    queue.add(partner);
                }
                usedLinks.add(link);
            }
        }

        String key = null;
        for (Node node : nodes) {
            String candidate = nodeKey(node.pos(), node.subLevel());
            if (key == null || candidate.compareTo(key) < 0) {
                key = candidate;
            }
        }
        if (key == null) {
            key = nodeKey(start, startSubLevel);
        }
        return new Component(key, List.copyOf(nodes), Set.copyOf(usedLinks), truncated);
    }

    // ------------------------------------------------------------------ 同步

    public static void syncLinks(ServerLevel level) {
        // 每个玩家的快照都必须带上"他自己的挂起选择"，所以逐人构造，不再共用一份包
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, payloadFor(level, player));
        }
    }

    public static void syncTo(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payloadFor(level, player));
    }

    private static TrainTubeLinksPayload payloadFor(ServerLevel level, ServerPlayer player) {
        return TrainTubeLinksPayload.of(level.dimension(), TrainTubeSavedData.get(level).links(),
                TrainTubeLinkInteraction.selectionOf(player));
    }

    // ------------------------------------------------------------------ 方块读取

    /** §8.28：只读已加载的区块，绝不触发同步加载。 */
    @Nullable
    public static BlockState stateOrNull(@Nullable Level level, BlockPos pos) {
        if (level == null) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    public static int connectionCount(BlockState state) {
        int count = 0;
        for (Direction direction : Direction.values()) {
            if (TrainPressuredTubeBlock.isConnected(state, direction)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 键

    public static String nodeKey(BlockPos pos, @Nullable UUID subLevel) {
        return (subLevel == null ? "world" : subLevel.toString()) + '|'
                + pos.getX() + ',' + pos.getY() + ',' + pos.getZ();
    }

    @Nullable
    public static BlockPos parseNodePos(String key) {
        int bar = key.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String[] parts = key.substring(bar + 1).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
