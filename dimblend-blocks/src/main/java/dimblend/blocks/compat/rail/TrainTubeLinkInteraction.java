package dimblend.blocks.compat.rail;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 扳手把两段车厢管道接起来：第一次点选端点 → 第二次点在直接挂接的另一车厢端点 → 建连接。
 *
 * <p>规则（锁定判定 #2）：</p>
 * <ul>
 *   <li>只有 {@code single_end}（本地连接数恰好 1）的端点才能当锚点；</li>
 *   <li>两端必须分属<b>两个不同的、且直接挂接</b>（车钩）的子层级；</li>
 *   <li>两端世界距离 ≤ 16 格；</li>
 *   <li>任一端点已经有一条连接就拒绝（不静默顶替）；</li>
 *   <li>同一端点再点一次或潜行右键 = 取消选择。</li>
 * </ul>
 *
 * <p>选择态按玩家保存（{@code Map<UUID, Selection>}），登出即清，另有 2 分钟超时兜底。</p>
 */
public final class TrainTubeLinkInteraction {

    private static final long SELECTION_TIMEOUT_TICKS = 2400L;

    private record Selection(ResourceKey<Level> dimension, UUID subLevel, BlockPos pos, long gameTime) {
    }

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    private TrainTubeLinkInteraction() {
    }

    public static void onWrench(ServerLevel level, BlockPos pos, @Nullable Player player) {
        if (player == null) {
            return;
        }
        BlockState state = TrainTubeNetworkManager.stateOrNull(level, pos);
        if (!TrainTubes.isTube(state)) {
            return;
        }
        if (player.isShiftKeyDown()) {
            if (SELECTIONS.remove(player.getUUID()) != null) {
                hint(player, "cancelled", ChatFormatting.GRAY);
            }
            return;
        }
        if (!TrainTubeNetworkMath.isValidEndpoint(TrainTubeNetworkManager.connectionCount(state))) {
            hint(player, "not_endpoint", ChatFormatting.YELLOW);
            return;
        }
        UUID subLevel = TrainTubeSable.subLevelId(level, pos);
        if (subLevel == null) {
            hint(player, "only_on_train", ChatFormatting.YELLOW);
            return;
        }

        long now = level.getGameTime();
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection != null
                && (selection.dimension() != level.dimension() || now - selection.gameTime() > SELECTION_TIMEOUT_TICKS)) {
            SELECTIONS.remove(player.getUUID());
            selection = null;
        }

        if (selection == null) {
            SELECTIONS.put(player.getUUID(), new Selection(level.dimension(), subLevel, pos.immutable(), now));
            hint(player, "selected", ChatFormatting.GOLD);
            return;
        }

        if (selection.subLevel().equals(subLevel) && selection.pos().equals(pos)) {
            SELECTIONS.remove(player.getUUID());
            hint(player, "cancelled", ChatFormatting.GRAY);
            return;
        }

        String rejection = validate(level, selection, subLevel, pos);
        if (rejection != null) {
            hint(player, rejection, ChatFormatting.RED);
            return;
        }

        SELECTIONS.remove(player.getUUID());
        TrainTubeNetworkManager.addLink(level,
                new TrainTubeLink(selection.subLevel(), selection.pos(), subLevel, pos));
        hint(player, "linked", ChatFormatting.GREEN);
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

    private static void hint(Player player, String key, ChatFormatting color) {
        player.displayClientMessage(
                Component.translatable("dimblend_blocks.train_pressured_tube.link." + key).withStyle(color), true);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SELECTIONS.remove(event.getEntity().getUUID());
    }
}
