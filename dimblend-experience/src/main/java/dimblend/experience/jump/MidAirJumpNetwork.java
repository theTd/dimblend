package dimblend.experience.jump;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 空中二段跳的服务端侧：注册空包体 C2S、每滞空一次性标记的附件，以及滞空重置。
 * 客户端位移是权威的；这里只清零 {@code fallDistance} 并广播 cloud。
 */
public final class MidAirJumpNetwork {

    public static void register(IEventBus modEventBus) {
        MidAirJumpAttachments.ATTACHMENTS.register(modEventBus);
        modEventBus.addListener(MidAirJumpNetwork::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(MidAirJumpNetwork::onPlayerTickPre);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(MidAirJumpPayload.TYPE, MidAirJumpPayload.STREAM_CODEC,
                MidAirJumpNetwork::handleServer);
    }

    /** 落地/入流体/攀爬/乘骑/飞行/鞘翅都重新装满一次空中跳。 */
    private static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean inFluid = player.isInWater() || player.isInLava() || player.isInFluidType();
        if (MidAirJumpRules.shouldRecharge(player.onGround(), inFluid, player.onClimbable(),
                player.isPassenger(), player.getAbilities().flying, player.isFallFlying())
                && player.hasData(MidAirJumpAttachments.USED_THIS_AIRBORNE.get())
                && Boolean.TRUE.equals(player.getData(MidAirJumpAttachments.USED_THIS_AIRBORNE.get()))) {
            player.setData(MidAirJumpAttachments.USED_THIS_AIRBORNE.get(), Boolean.FALSE);
        }
    }

    /**
     * 不做任何速度/位置修正，也不置 {@code hurtMarked}：服务端速度包会覆写客户端
     * 预测，正是旧第三方兼容「跳跃抖动/回弹」的根因。只清零下落高度并广播粒子。
     */
    private static void handleServer(MidAirJumpPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.isSpectator() || player.isPassenger() || player.getAbilities().flying
                    || player.isFallFlying() || player.isDeadOrDying()) {
                return;
            }
            if (Boolean.TRUE.equals(player.getData(MidAirJumpAttachments.USED_THIS_AIRBORNE.get()))) {
                return;
            }
            player.setData(MidAirJumpAttachments.USED_THIS_AIRBORNE.get(), Boolean.TRUE);
            player.resetFallDistance();
            MidAirJumpEffects.broadcastServerCloudToOthers(player);
        });
    }

    private MidAirJumpNetwork() {
    }
}
