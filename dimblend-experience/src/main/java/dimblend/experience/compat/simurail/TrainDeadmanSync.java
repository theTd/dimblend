package dimblend.experience.compat.simurail;

import dimblend.experience.DimBlend;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * E7' 锁存状态补发（游戏总线）：锁存变化只广播给维度内玩家，进服/换维度的玩家
 * 可能错过，故在这两个时点向其补发当前维度的锁存状态（客户端粒子以之为准）。
 * 只触达原版类型，无第三方引用。
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class TrainDeadmanSync {

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendCurrent(player);
        }
    }

    @SubscribeEvent
    public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendCurrent(player);
        }
    }

    private static void sendCurrent(ServerPlayer player) {
        // simurail 缺席时不得触达 TrainDeadmanBrake（其方法体硬引用第三方类型，
        // 类加载即连锁 NoClassDefFoundError）；未锁存状态直接下发
        boolean latched = ModList.get().isLoaded("simurail")
                && TrainDeadmanBrake.isLatched(player.serverLevel().dimension());
        DeadmanLatchPayload.sendTo(player, player.serverLevel().dimension(), latched);
    }

    private TrainDeadmanSync() {
    }
}
