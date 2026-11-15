package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import dimblend.api.CorridorRails;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The server owns the open session and the chosen preset; packets cannot skip either.
 *
 * <p>2026-11-15: the five-second cooling-off period and the second click are gone. One click on the
 * screen sends {@link ChassisChoicePayload}, and the server spawns immediately after the same
 * checks that guard placement: the session token, the player's permission for that preset
 * ({@link ChassisPreset#visibleTo(boolean)}) and the site still being applicable. An empty preset
 * id means the player closed the page, so the session is simply dropped.
 */
public final class ChassisSelection {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final class Session {
        final UUID token = UUID.randomUUID();
        final ServerLevel level;
        final BlockPos pos;
        Session(ServerLevel level, BlockPos pos) { this.level = level; this.pos = pos.immutable(); }
    }
    private ChassisSelection() {}

    public static void validatePlacement(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(AllBlocks.TRAIN_CONTROLS.get())) return;
        String failure = !CorridorRails.isGeneratedTrack(level, pos.below()) ? "track_required"
                : ChassisConstruction.reserved(level, pos) || !ChassisSite.clear(level, pos, Map.of(), true) ? "unsafe" : null;
        if (failure != null) {
            level.destroyBlock(pos, true);
            ChassisConstruction.broadcast(level, failure);
        } else {
            ChassisConstruction.broadcast(level, "choose");
        }
    }

    /**
     * 两端共用的适用条件：驾驶台、旋转维度、天然主线、8 格距离、玩家仍可交互。
     *
     * <p>服务端用 {@link CorridorRails#isGeneratedTrack} 做 per-delegate 校验；客户端没有
     * 种子布局，但用 {@code BandLaneSync} 推送的当前 lane + band size 做同一 profile 的
     * 预测（{@link ChassisClientTrackRule}）。跨 band region、lane 未知或未同步时客户端
     * 不取消，交给服务端决定。条件不满足时调用方必须放行点击，不能先取消再发现没有菜单可开。</p>
     */
    public static boolean applicable(Level level, Player player, BlockPos pos) {
        if (!player.isAlive() || player.isSpectator()) return false;
        if (!level.dimension().location().toString().equals("dimblend:rotating")) return false;
        if (!level.getBlockState(pos).is(AllBlocks.TRAIN_CONTROLS.get())) return false;
        if (player.distanceToSqr(pos.getCenter()) > 64) return false;
        if (level instanceof ServerLevel serverLevel) {
            return CorridorRails.isGeneratedTrack(serverLevel, pos.below());
        }
        if (FMLEnvironment.dist == Dist.CLIENT) {
            return ChassisClientTrackRule.matches(pos, level.getBlockState(pos.below()), player);
        }
        return false;
    }

    /** @return 是否真的建立了会话；false 时调用方不得取消右键事件。 */
    public static boolean open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!applicable(level, player, pos)) return false;
        Session session = new Session(level, pos);
        SESSIONS.put(player.getUUID(), session);
        reply(player, session, true);
        return true;
    }

    public static void choose(ServerPlayer player, ChassisChoicePayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(payload.token())) return;
        // One choice per session: the click that spawns also ends it, and so does a close.
        SESSIONS.remove(player.getUUID());
        if (payload.preset().isEmpty()) return;
        if (!valid(player, session)) {
            reply(player, session, false);
            return;
        }
        ChassisPreset preset = ChassisPreset.byId(payload.preset()).orElse(null);
        if (preset == null || !preset.available() || !preset.permitted(player.isCreative())) {
            player.displayClientMessage(Component.translatable(
                    "message.dimblend_experience.chassis.creative_only"), true);
            reply(player, session, false);
            return;
        }
        // The spawner broadcasts its own failure reason ("unsafe" / "spawn_failed").
        ChassisPresetSpawner.spawn(session.level, session.pos, preset);
    }

    public static void tick(MinecraftServer server) {
        for (var entry : Map.copyOf(SESSIONS).entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) SESSIONS.remove(entry.getKey());
            else if (!valid(player, entry.getValue())) close(player, entry.getValue());
        }
    }

    private static boolean valid(ServerPlayer player, Session session) {
        return player.serverLevel() == session.level && applicable(session.level, player, session.pos);
    }
    private static void close(ServerPlayer player, Session session) {
        SESSIONS.remove(player.getUUID());
        reply(player, session, false);
    }
    private static void reply(ServerPlayer player, Session session, boolean open) {
        PacketDistributor.sendToPlayer(player, new ChassisScreenPayload(session.token, open));
    }
    public static void clear() { SESSIONS.clear(); }
}
