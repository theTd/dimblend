package dimblend.experience.nickname;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A8 网络注册（MOD 总线）。客户端应用 lambda 只在物理客户端执行，
 * 从而 ClientNicknames 类不会在 dedicated server 上被解析。
 */
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NicknameNetwork {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(NicknameSyncPayload.TYPE, NicknameSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> NicknameSyncPayload.applyOnClient(payload)));
        registrar.playToServer(NicknameEditPayload.TYPE, NicknameEditPayload.STREAM_CODEC, NicknameEditPayload::handle);
    }

    private NicknameNetwork() {
    }
}
