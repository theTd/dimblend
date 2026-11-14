package dimblend.band;

import dimblend.DimBlendRegistries;
import dimblend.api.BandTrainMarker;
import java.util.HashMap;
import java.util.List;
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
import net.neoforged.neoforge.network.registration.NetworkRegistry;

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
 * <p>{@link BandTrainMarker#override()} 生效时改为整维度广播它给定的 x 与警戒位，完全绕过
 * {@link BandTrainLocator} 候选与 320 格窗口（需求 §3.3：白线指向龙巢并变红，龙巢在龙巢车厢
 * 里，不需要是“常加载车体”也不必在玩家 320 格内）。override 清空后下一次扫描回到常规逻辑；
 * 去重表存整条 payload（x + alert），所以清空或颜色翻转都会重发，而重复值不发。清空时若玩家
 * 320 格内恰好没有车，{@link #resolvePayload} 会用上一次的 x 补发一条 {@code alert = false}，
 * 否则客户端的红线永远留在屏幕上（龙巢消失的位置通常不是列车所在的位置）。</p>
 *
 * <p>用 {@code @EventBusSubscriber} 而不是在 {@link dimblend.DimBlend} 里注册：本类的方法签名
 * 不引用任何 sable 类型，FML 启动扫描可以安全加载它；sable 缺席由 locator 的 isLoaded 守卫
 * 处理。去重表按玩家记最后一次下发的 payload；退出或换维度时清掉，回到旋转维度后一定重发
 * 一次。客户端没协商出 {@code dimblend:band_train} 通道时整条推送跳过，绝不发未注册的
 * modded payload。</p>
 */
@EventBusSubscriber(modid = "dimblend")
public final class BandTrainSync {
    /** 扫描节奏：20 tick = 1 秒。 */
    public static final int SCAN_INTERVAL_TICKS = 20;

    private static final Map<UUID, BandTrainPayload> SENT = new HashMap<>();

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
        BandTrainMarker.Marker marker = BandTrainMarker.override();
        List<BandTrainMath.Candidate> candidates = marker == null
                ? BandTrainLocator.candidates(level)
                : List.<BandTrainMath.Candidate>of();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!player.serverLevel().dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
                // 离开该维度就丢掉去重记录：回来时（客户端已清缓存）必须重发一次。
                SENT.remove(player.getUUID());
                continue;
            }
            OptionalDouble located = marker == null
                    ? BandTrainMath.selectFrontmostX(candidates, player.getX(), player.getZ())
                    : OptionalDouble.empty();
            BandTrainPayload previous = SENT.get(player.getUUID());
            BandTrainPayload payload = resolvePayload(marker, located, previous);
            if (payload == null) {
                // 没有 override、320 格内也没有合格 sable，且上一次不是警戒：客户端继续用上一次
                // 的位置（需求 #6），不发清空。
                continue;
            }
            // 客户端没装本模组、或没协商出这条通道时不能发原始包：NeoForge 发送侧会判定为
            // 非法 payload，接收侧对没注册的 modded payload 会直接断开客户端。判定用 listener
            // 自己的协议（服务端 play 监听器即 PLAY），与 MouseClickMixin 客户端侧同一惯例。
            // 在记去重表之前判定：没发出去的值不算“已下发”，下个 tick 才有机会补送（例如清警戒
            // 的那一条）。
            if (!NetworkRegistry.hasChannel(player.connection, BandTrainPayload.TYPE.id())) {
                continue;
            }
            SENT.put(player.getUUID(), payload);
            if (payload.equals(previous)) {
                continue;
            }
            player.connection.send(new ClientboundCustomPayloadPacket(payload));
        }
    }

    /**
     * 本 tick 要下发的 payload，{@code null} 表示不发。
     *
     * <p>override 生效时整维度广播它的 x 与警戒位。override 清空后，只有两种情况必须发：
     * 320 格内找到了车（回到常规目标），或者上一次是警戒位——后者是需求 §3.3 的收尾：龙巢消失
     * 时玩家可能正站在 320 格内没有车的区段，此时若直接 {@code continue}，客户端会永远停在
     * 红色警戒线上。清警戒时沿用上一次的 x，让白线留在原地而不是跳开。
     *
     * <p>纯函数，服务端 tick 与单测共用；{@code previous} 是该玩家最后一次成功下发的
     * payload（或 null）。
     */
    @javax.annotation.Nullable
    static BandTrainPayload resolvePayload(
            @javax.annotation.Nullable BandTrainMarker.Marker marker,
            OptionalDouble located,
            @javax.annotation.Nullable BandTrainPayload previous) {
        if (marker != null) {
            return new BandTrainPayload(marker.centerX(), marker.alert());
        }
        if (located != null && located.isPresent()) {
            return new BandTrainPayload(located.getAsDouble(), false);
        }
        if (previous != null && previous.alert()) {
            return new BandTrainPayload(previous.centerX(), false);
        }
        return null;
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
