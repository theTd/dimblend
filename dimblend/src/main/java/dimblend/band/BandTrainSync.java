package dimblend.band;

import dimblend.DimBlendRegistries;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 服务端把区域进度条白线的参照位置（{@link BandTrainLocator} 的候选 + {@link BandTrainMath}
 * 的筛选）推给旋转维度里的玩家，只在位置变化时下发。
 *
 * <p>为什么需要同步：见 {@link BandTrainLocator}——票据是服务端的，320 格网络跟踪又让客户端
 * 看不到远处的列车。客户端收到后缓存（{@link dimblend.client.ClientBandTrain}），收不到新值
 * 就继续用上一次（需求 #6：白线保留最后一次，不再消失）。</p>
 *
 * <p>每 20 tick 扫一次（1 秒）：sable 的物理位置本身每 tick 在变，但 HUD 白线是 81px 的进度条
 * 映射，1 秒的粒度足够，且避免了每 tick 遍历本维度所有 force-loaded sublevel。候选表整维度
 * 只收一次，每个玩家再按自己的 320 格窗口选“最前一辆”——窗口是玩家相对的，同一个候选表对
 * 不同玩家会得出不同结果。</p>
 *
 * <p>用 {@code @EventBusSubscriber} 而不是在 {@link dimblend.DimBlend} 里注册：本类的方法签名
 * 不引用任何 sable 类型，FML 启动扫描可以安全加载它；sable 缺席由 locator 的 isLoaded 守卫
 * 处理。去重表按玩家记最后一次下发的 x；退出或换维度时清掉，回到旋转维度后一定重发一次。</p>
 */
@EventBusSubscriber(modid = "dimblend")
public final class BandTrainSync {
    /** 扫描节奏：20 tick = 1 秒。 */
    public static final int SCAN_INTERVAL_TICKS = 20;

    private static final Map<UUID, Double> SENT = new HashMap<>();

    private BandTrainSync() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return;
        }
        if (level.getGameTime() % SCAN_INTERVAL_TICKS != 0L) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }
        var candidates = BandTrainLocator.candidates(level);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!player.serverLevel().dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
                // 离开该维度就丢掉去重记录：回来时（客户端已清缓存）必须重发一次。
                SENT.remove(player.getUUID());
                continue;
            }
            OptionalDouble located = BandTrainMath.selectFrontmostX(candidates, player.getX(), player.getZ());
            if (located.isEmpty()) {
                // 玩家 320 格内暂时没有合格 sable：客户端继续用上一次的位置（需求 #6），不发清空。
                continue;
            }
            double centerX = located.getAsDouble();
            Double previous = SENT.put(player.getUUID(), centerX);
            if (previous != null && previous.doubleValue() == centerX) {
                continue;
            }
            player.connection.send(new ClientboundCustomPayloadPacket(new BandTrainPayload(centerX)));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // 换维度后客户端缓存已清（ClientBandTrain 按关卡身份清空），去重记录要一起丢，
        // 回到旋转维度时才一定重发一次，不必等下一次位置变化。
        SENT.remove(event.getEntity().getUUID());
    }
}
