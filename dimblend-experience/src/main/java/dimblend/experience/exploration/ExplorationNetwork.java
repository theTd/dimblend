package dimblend.experience.exploration;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 探索限制板块网络注册（MOD 总线）。客户端应用 lambda 只在物理客户端执行。
 */
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ExplorationNetwork {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(FarCurseTierPayload.TYPE, FarCurseTierPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> FarCurseTierPayload.applyOnClient(payload)));
    }

    private ExplorationNetwork() {
    }
}
