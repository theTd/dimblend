package dimblend.experience.compat.simurail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 刹车持续鸣响的 S2C 发包（服务端写口，2026-11-25 LOCKED #4）。
 *
 * <p>沿触发时 {@link TrainBrakeBroadcast#collectMembers} 给出同列车架的 plot 坐标，
 * 这里把它发给半径 {@link #PLAYER_RADIUS} 内的玩家：{@code start = true} 起播/刷新，
 * {@code start = false} 瞬停。</p>
 *
 * <p><b>去重</b>：整列施闸时多架同 tick 各发一次「同一张车架表」，所以同维度、同开始/停止、
 * 同坐标集合、<b>同音调</b>的包在 {@link #DUPLICATE_WINDOW_TICKS} 内只发一次。按<b>坐标集合</b>
 * 而不是「沿位置」去重，是为了不把tick内相邻的另一列车误吞掉（集合不同即视为不同事件）。</p>
 *
 * <p><b>音调必须参与签名</b>（2026-11-27 Slice 10）：列车正轻刹（{@code pitch = 1.0}）时穿进
 * 末影龙龙息，同一 tick 内车架会立刻改发 {@code pitch = 0.5} 的急刹开始包；若签名里没有音调，
 * 它会被 2 tick 去重窗当成「同一张车架表的重复」直接吞掉，客户端就一直用 1.0 音调鸣响。</p>
 */
public final class TrainBrakeSoundSync {

    /** 发包半径（格）：超过这个距离的玩家既听不见（LINEAR 衰减 32 格），也不必为他占客户端声道。 */
    private static final double PLAYER_RADIUS = 256.0D;
    /** 同集合包的合并窗（tick）。 */
    private static final long DUPLICATE_WINDOW_TICKS = 2L;

    /** 去重签名。{@code pitchBits} = {@code Float.floatToIntBits(pitch)}，见类注释。 */
    record Signature(ResourceLocation dimension, boolean start, int positionsHash, int pitchBits) {
    }

    private static Signature lastSignature;
    private static long lastSignatureTick = Long.MIN_VALUE;

    /** 由 {@code TrainDeadmanNetwork#onRegisterPayloads} 在拿到 registrar 后调用一行即可。 */
    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToClient(
                TrainBrakeSoundPayload.TYPE,
                TrainBrakeSoundPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> TrainBrakeSoundPayload.applyOnClient(payload)));
    }

    /** 施闸：给附近玩家下发「这些车架开始持续鸣响」。幂等（客户端已在播只刷新）。 */
    public static void sendStart(ServerLevel level, Vec3 edgeWorldPos, List<BlockPos> plotPositions,
                                 float volume, float pitch) {
        send(level, edgeWorldPos, plotPositions, true, volume, pitch);
    }

    /** 松闸 / 车架移除：立刻收掉这些车架的持续鸣响。 */
    public static void sendStop(ServerLevel level, Vec3 edgeWorldPos, List<BlockPos> plotPositions) {
        send(level, edgeWorldPos, plotPositions, false, 0.0F, 1.0F);
    }

    private static void send(ServerLevel level, Vec3 edgeWorldPos, List<BlockPos> plotPositions,
                             boolean start, float volume, float pitch) {
        if (plotPositions == null || plotPositions.isEmpty()) {
            return;
        }
        List<BlockPos> positions = sortedCopy(plotPositions);
        ResourceLocation dimension = level.dimension().location();
        long now = level.getGameTime();
        Signature signature = signatureOf(dimension, start, positions, pitch);
        synchronized (TrainBrakeSoundSync.class) {
            if (signature.equals(lastSignature) && now - lastSignatureTick <= DUPLICATE_WINDOW_TICKS) {
                return;
            }
            lastSignature = signature;
            lastSignatureTick = now;
        }
        TrainBrakeSoundPayload payload =
                new TrainBrakeSoundPayload(dimension, positions, start, volume, pitch);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            // 玩家坐在 Sable 座椅上时 position() 是 plot 坐标（~1.6×10^7），直接与边沿世界坐标
            // 比距离会恒超 256 格，把全部持续刹车包丢掉。必须先投影到世界坐标再量。
            if (SableWorldPosition.project(level, player.position()).distanceToSqr(edgeWorldPos)
                    > PLAYER_RADIUS * PLAYER_RADIUS) {
                continue;
            }
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * 去重签名（纯函数，单测钉住「音调参与去重」与「坐标集合顺序无关」两条不变量）。
     *
     * <p>内部按 {@link #sortedCopy} 排序后取 {@code List#hashCode()}，所以同一张车架表从任何
     * 一架触发、以任何顺序传入都得到同一个签名。</p>
     *
     * @param pitch 本包的音调；停止包传 {@code 1.0}（{@code sendStop} 亦然）
     */
    static Signature signatureOf(ResourceLocation dimension, boolean start, List<BlockPos> positions,
                                 float pitch) {
        return new Signature(dimension, start, sortedCopy(positions).hashCode(),
                Float.floatToIntBits(pitch));
    }

    /** 稳定排序：同一张车架表从任何一架触发都得到同一个 hashCode（去重与网络字节都稳定）。 */
    private static List<BlockPos> sortedCopy(List<BlockPos> positions) {
        List<BlockPos> copy = new ArrayList<>(positions);
        copy.sort(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(copy);
    }

    private TrainBrakeSoundSync() {
    }
}
