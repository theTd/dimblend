package dimblend.experience.nickname;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A8 登录同步：玩家进服时推送当前昵称表。
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class NicknameSync {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && Config.NICKNAME.get()) {
            PacketDistributor.sendToPlayer(player,
                    new NicknameSyncPayload(NicknameStore.get(player.server).view()));
        }
    }

    private NicknameSync() {
    }
}
