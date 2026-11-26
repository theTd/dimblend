package dimblend.blocks.compat.rail;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllItems;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.WrenchItem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 扳手把两段车厢管道接起来：第一次点选端点 → 第二次点在直接挂接的另一车厢端点 → 建连接。
 *
 * <p>规则（锁定判定 #2）：</p>
 * <ul>
 *   <li>只有 {@code single_end}（本地连接数恰好 1）的端点才能当锚点；</li>
 *   <li>两端必须分属<b>两个不同的、且直接挂接</b>（车钩）的子层级；</li>
 *   <li>两端世界距离 ≤ 16 格；</li>
 *   <li>任一端点已经有一条连接就拒绝（不静默顶替）；</li>
 *   <li>同一端点再点一次或潜行右键 = 取消选择；</li>
 *   <li><b>已连接的端点，右键直接断开</b>（不关心此刻在不在连接模式里）；</li>
 *   <li><b>扳手一旦离开手持</b>（主手与副手都没有）就立刻退出连接模式。</li>
 * </ul>
 *
 * <p>分支判定全在 {@link TrainTubeWrenchRules}（纯函数、有单测），本类只落地副作用 + 发提示 + 推快照。</p>
 *
 * <p>选择态按玩家保存（{@code Map<UUID, Selection>}），登出即清；另有"切走扳手"与 2 分钟超时两条兜底。</p>
 */
public final class TrainTubeLinkInteraction {

    private static final long SELECTION_TIMEOUT_TICKS = 2400L;

    private record Selection(ResourceKey<Level> dimension, UUID subLevel, BlockPos pos, long gameTime) {
    }

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    private TrainTubeLinkInteraction() {
    }

    // ------------------------------------------------------------------ 扳手右键

    public static void onWrench(ServerLevel level, BlockPos pos, @Nullable Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        BlockState state = TrainTubeNetworkManager.stateOrNull(level, pos);
        if (!TrainTubes.isTube(state)) {
            return;
        }
        long now = level.getGameTime();
        Selection selection = SELECTIONS.get(serverPlayer.getUUID());
        if (selection != null && (selection.dimension() != level.dimension()
                || now - selection.gameTime() > SELECTION_TIMEOUT_TICKS)) {
            SELECTIONS.remove(serverPlayer.getUUID());
            selection = null;
            syncSelection(serverPlayer);
        }

        UUID subLevel = TrainTubeSable.subLevelId(level, pos);
        boolean linked = linkAt(level, subLevel, pos) != null;
        TrainTubeWrenchRules.Action action = TrainTubeWrenchRules.decide(
                serverPlayer.isShiftKeyDown(),
                linked,
                TrainTubeNetworkMath.isValidEndpoint(TrainTubeNetworkManager.connectionCount(state)),
                subLevel != null,
                selection != null,
                selection != null && selection.pos().equals(pos));

        switch (action) {
            case IGNORE -> {
                // Create 的 WrenchItem 已把潜行拆除交给 IWrenchable#onSneakWrenched，这里不留选择副本
            }
            case CANCEL -> {
                SELECTIONS.remove(serverPlayer.getUUID());
                hint(serverPlayer, "cancelled", ChatFormatting.GRAY);
                syncSelection(serverPlayer);
            }
            case DISCONNECT -> disconnect(level, pos, subLevel, serverPlayer);
            case NOT_ENDPOINT -> hint(serverPlayer, "not_endpoint", ChatFormatting.YELLOW);
            case NOT_ON_TRAIN -> hint(serverPlayer, "only_on_train", ChatFormatting.YELLOW);
            case SELECT -> {
                SELECTIONS.put(serverPlayer.getUUID(),
                        new Selection(level.dimension(), subLevel, pos.immutable(), now));
                hint(serverPlayer, "selected", ChatFormatting.GOLD);
                syncSelection(serverPlayer);
            }
            case LINK -> link(level, selection, subLevel, pos, serverPlayer);
        }
    }

    private static void link(ServerLevel level, Selection selection, UUID subLevel, BlockPos pos,
            ServerPlayer player) {
        String rejection = validate(level, selection, subLevel, pos);
        if (rejection != null) {
            hint(player, rejection, ChatFormatting.RED);
            return;
        }
        SELECTIONS.remove(player.getUUID());
        // addLink → syncLinks：每个玩家的快照里都带上"他自己的选择已清空"
        TrainTubeNetworkManager.addLink(level,
                new TrainTubeLink(selection.subLevel(), selection.pos(), subLevel, pos));
        hint(player, "linked", ChatFormatting.GREEN);
    }

    /**
     * 断开锚在该端点上的连接：摘表 → 两侧分量各自清池 → 广播快照
     * （{@link TrainTubeNetworkManager#dropLink} 已包含这三步）。
     */
    private static void disconnect(ServerLevel level, BlockPos pos, @Nullable UUID subLevel, ServerPlayer player) {
        TrainTubeLink link = linkAt(level, subLevel, pos);
        SELECTIONS.remove(player.getUUID());
        if (link == null) {
            // 表里已经没有（例如每 tick 复核刚刚摘掉）：只清掉本地挂起态
            syncSelection(player);
            return;
        }
        TrainTubeNetworkManager.dropLink(level, link);
        AllSoundEvents.WRENCH_REMOVE.playOnServer(level, pos, 0.75f, 1.15f);
        hint(player, "disconnected", ChatFormatting.GREEN);
    }

    /** 先按 (子层级, 位置) 找；子层级查不到时按位置兜底（plot 坐标在一个维度里唯一）。 */
    @Nullable
    private static TrainTubeLink linkAt(ServerLevel level, @Nullable UUID subLevel, BlockPos pos) {
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        if (subLevel != null) {
            TrainTubeLink exact = data.linkAt(subLevel, pos);
            if (exact != null) {
                return exact;
            }
        }
        for (TrainTubeLink candidate : data.links()) {
            if (candidate.posA().equals(pos) || candidate.posB().equals(pos)) {
                return candidate;
            }
        }
        return null;
    }

    /** 返回拒绝原因（语言键后缀），{@code null} 表示可以建。 */
    @Nullable
    private static String validate(ServerLevel level, Selection selection, UUID subLevel, BlockPos pos) {
        if (selection.subLevel().equals(subLevel)) {
            return "same_carriage";
        }
        BlockState anchorState = TrainTubeNetworkManager.stateOrNull(level, selection.pos());
        if (!TrainTubes.isTube(anchorState)
                || !TrainTubeNetworkMath.isValidEndpoint(TrainTubeNetworkManager.connectionCount(anchorState))) {
            return "stale_endpoint";
        }
        if (!TrainTubeSable.directlyCoupled(level, selection.subLevel(), subLevel)) {
            return "not_coupled";
        }
        Vec3 anchorWorld = TrainTubeSable.toWorld(level, selection.pos());
        Vec3 targetWorld = TrainTubeSable.toWorld(level, pos);
        if (!TrainTubeNetworkMath.withinLinkDistance(
                anchorWorld.x - targetWorld.x, anchorWorld.y - targetWorld.y, anchorWorld.z - targetWorld.z)) {
            return "too_far";
        }
        TrainTubeSavedData data = TrainTubeSavedData.get(level);
        if (data.linkAt(selection.subLevel(), selection.pos()) != null
                || data.linkAt(subLevel, pos) != null) {
            return "already_linked";
        }
        return null;
    }

    // ------------------------------------------------------------------ 每 tick 复核（切走扳手即退出连接态）

    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (SELECTIONS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, Selection>> iterator = SELECTIONS.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Selection> entry = iterator.next();
            Selection selection = entry.getValue();
            if (selection.dimension() != level.dimension()) {
                continue;
            }
            ServerPlayer player = findPlayer(level, entry.getKey());
            if (player == null || player.isRemoved()) {
                iterator.remove();
                continue;
            }
            boolean holdingWrench = holdsWrench(player);
            if (!TrainTubeWrenchRules.shouldAbandon(holdingWrench, now - selection.gameTime(),
                    SELECTION_TIMEOUT_TICKS)) {
                continue;
            }
            iterator.remove();
            if (!holdingWrench) {
                hint(player, "wrench_removed", ChatFormatting.GRAY);
            }
            TrainTubeNetworkManager.syncTo(player);
        }
    }

    @Nullable
    private static ServerPlayer findPlayer(ServerLevel level, UUID playerId) {
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    public static boolean holdsWrench(Player player) {
        return isWrench(player.getMainHandItem()) || isWrench(player.getOffhandItem());
    }

    private static boolean isWrench(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof WrenchItem || AllItems.WRENCH.isIn(stack);
    }

    // ------------------------------------------------------------------ 客户端镜像

    /** 该玩家当前的挂起选择（给载荷用；没有就 {@code null}）。 */
    @Nullable
    public static TrainTubeSelection selectionOf(ServerPlayer player) {
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || selection.dimension() != player.level().dimension()) {
            return null;
        }
        return new TrainTubeSelection(selection.subLevel(), selection.pos());
    }

    private static void syncSelection(ServerPlayer player) {
        TrainTubeNetworkManager.syncTo(player);
    }

    // ------------------------------------------------------------------ 其它

    private static void hint(Player player, String key, ChatFormatting color) {
        player.displayClientMessage(
                Component.translatable("dimblend_blocks.train_pressured_tube.link." + key).withStyle(color), true);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SELECTIONS.remove(event.getEntity().getUUID());
    }
}
