package dimblend.experience.compat.simurail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * "低于最低速牵引"集合的服务端采集与同步（2026-11-25 LOCKED #2）。
 *
 * <p>数据源是核心模组的 {@code MinimumSpeedState.activePushingSubLevels}（经
 * {@link MinimumSpeedBridge} 桥接，核心缺席时恒为空集）。每 tick 对比快照，
 * <b>只在变化时</b>发一次 S2C，避免每 tick 广播整张表。登出/换档不清服务端快照是
 * 无害的（键是维度，值只在变化时重发）；客户端缓存由 {@code DimBlendClient} 清理。</p>
 */
public final class MinimumSpeedSync {

    private static final Map<ResourceLocation, Set<UUID>> LAST_SENT = new HashMap<>();

    /** 由 {@code DimBlend} 初始化时挂到 mod 事件总线（自包含，无需额外接线）。 */
    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MinimumSpeedSync::onLevelTick);
    }

    /** 由 {@code TrainDeadmanNetwork#register} 的 registrar 调用。 */
    public static void registerPayloads(RegisterPayloadHandlersEvent event, PayloadRegistrar registrar) {
        registrar.playToClient(
                MinimumSpeedActivePayload.TYPE,
                MinimumSpeedActivePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> MinimumSpeedActivePayload.applyOnClient(payload)));
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Set<UUID> current = MinimumSpeedBridge.activePushingSubLevels(level);
        ResourceLocation dimension = level.dimension().location();
        Set<UUID> previous = LAST_SENT.get(dimension);
        if (previous != null && previous.equals(current)) {
            return;
        }
        LAST_SENT.put(dimension, Set.copyOf(current));
        MinimumSpeedActivePayload payload =
                new MinimumSpeedActivePayload(dimension, List.copyOf(current));
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private MinimumSpeedSync() {
    }
}
