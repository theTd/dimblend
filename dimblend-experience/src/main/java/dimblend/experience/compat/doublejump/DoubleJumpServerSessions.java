package dimblend.experience.compat.doublejump;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端按玩家隔离的二段跳状态（{@code Map<UUID, ServerState>}）。
 *
 * <p>上游 {@code DoubleJumpHandler} 用全局静态 {@code used/ready/cooldownTicks}：任意玩家落地
 * 都会重置所有人的状态，且 {@code triggerDoubleJump} 根本不检查 {@code canTrigger()}，包重发即无限空跳。
 * 本类把状态按 UUID 隔离，并在服务端玩家 tick 时落地复位、定期清掉已登出的 UUID。</p>
 *
 * <p>所有访问都发生在服务端 tick / 网络处理线程（同一线程）；不做额外同步。</p>
 */
public final class DoubleJumpServerSessions {

    /** 登出清理周期（tick）：每 128 tick 与在线玩家列表对一次，代价可忽略。 */
    private static final int PRUNE_INTERVAL_TICKS = 128;

    private static final Map<UUID, DoubleJumpServerState> STATES = new HashMap<>();

    /**
     * 服务端玩家 tick：落地/入水/攀爬时复位，否则推进冷却；并周期性清理离线 UUID。
     */
    public static void onPlayerTick(ServerPlayer player) {
        DoubleJumpServerState state = state(player.getUUID());
        if (isGrounded(player)) {
            state.reset();
        } else {
            state.tickCooldown();
        }
        if ((player.tickCount & (PRUNE_INTERVAL_TICKS - 1)) == 0) {
            prune(player.getServer());
        }
    }

    /**
     * 服务端触发前校验并记账：飞行、鞘翅、乘骑一律拒绝；一次滞空只放行一次。
     *
     * <p>刻意<b>不</b>看 {@code player.onGround()}：客户端的离开地面 tick 与服务器已知位置天然
     * 相差 1 tick 左右，快速二连点到达时服务器往往仍认为玩家在地面——按地面拒绝会把本应
     * 确定触发的二段跳丢掉。服务器只依赖按 UUID 的 {@code used}（落地时复位）与冷却来挡住
     * 同一次滞空的重复包；真正的“在地面不触发”由客户端状态机保证。</p>
     *
     * @return true 表示允许本次触发（已置 {@code used} 并进入冷却）
     */
    public static boolean tryBeginJump(ServerPlayer player) {
        if (player.getAbilities().flying
                || player.isFallFlying()
                || player.isPassenger()) {
            return false;
        }
        DoubleJumpServerState state = state(player.getUUID());
        if (!state.canTrigger()) {
            return false;
        }
        state.onTriggered();
        return true;
    }

    private static boolean isGrounded(ServerPlayer player) {
        return player.onGround() || player.isInWaterOrBubble() || player.onClimbable();
    }

    private static DoubleJumpServerState state(UUID uuid) {
        return STATES.computeIfAbsent(uuid, ignored -> new DoubleJumpServerState());
    }

    private static void prune(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
        }
        STATES.keySet().retainAll(online);
    }

    private DoubleJumpServerSessions() {
    }
}
