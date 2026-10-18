package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import dimblend.api.CorridorRails;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The server owns the selected kind and five-second deadline; packets cannot skip either. */
public final class ChassisSelection {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final long WAIT_NANOS = 5_000_000_000L;
    private static final class Session {
        final UUID token = UUID.randomUUID();
        final ServerLevel level;
        final BlockPos pos;
        int selected = -1;
        long readyAt;
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

    public static void open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!ChassisEvents.inDimension(level) || !level.getBlockState(pos).is(AllBlocks.TRAIN_CONTROLS.get())
                || !CorridorRails.isGeneratedTrack(level, pos.below()) || player.distanceToSqr(pos.getCenter()) > 64) return;
        Session session = new Session(level, pos);
        SESSIONS.put(player.getUUID(), session);
        reply(player, session, -1, 0);
    }

    public static void choose(ServerPlayer player, ChassisChoicePayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(payload.token())) return;
        if (payload.kind() == -1) { SESSIONS.remove(player.getUUID()); return; }
        if (!valid(player, session) || payload.kind() < 0 || payload.kind() >= ChassisBlueprint.values().length) {
            close(player, session);
            return;
        }
        if (!payload.confirm()) {
            // A choice is locked until the screen is closed and a fresh session is opened.
            if (session.selected != -1) return;
            session.selected = payload.kind();
            session.readyAt = System.nanoTime() + WAIT_NANOS;
            reply(player, session, session.selected, 5000);
            return;
        }
        if (session.selected != payload.kind()) return;
        long remaining = session.readyAt - System.nanoTime();
        if (remaining > 0) {
            reply(player, session, session.selected, (int) ((remaining + 999_999) / 1_000_000));
            return;
        }
        if (!ChassisConstruction.start(session.level, session.pos, ChassisBlueprint.values()[session.selected])) {
            ChassisConstruction.broadcast(session.level, "unsafe");
        }
        close(player, session);
    }

    public static void tick(MinecraftServer server) {
        for (var entry : Map.copyOf(SESSIONS).entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) SESSIONS.remove(entry.getKey());
            else if (!valid(player, entry.getValue())) close(player, entry.getValue());
        }
    }

    private static boolean valid(ServerPlayer player, Session session) {
        return player.isAlive() && !player.isSpectator() && player.serverLevel() == session.level
                && player.distanceToSqr(session.pos.getCenter()) <= 64
                && session.level.getBlockState(session.pos).is(AllBlocks.TRAIN_CONTROLS.get());
    }
    private static void close(ServerPlayer player, Session session) {
        SESSIONS.remove(player.getUUID());
        reply(player, session, -2, 0);
    }
    private static void reply(ServerPlayer player, Session session, int kind, int waitMillis) {
        PacketDistributor.sendToPlayer(player, new ChassisScreenPayload(session.token, kind, waitMillis));
    }
    public static void clear() { SESSIONS.clear(); }
}
