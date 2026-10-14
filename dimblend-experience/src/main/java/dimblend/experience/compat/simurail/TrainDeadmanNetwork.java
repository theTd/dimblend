package dimblend.experience.compat.simurail;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * E7' 无人值守刹车网络注册（MOD 总线）。客户端应用 lambda 只在物理客户端执行。
 */
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TrainDeadmanNetwork {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(DeadmanLatchPayload.TYPE, DeadmanLatchPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> DeadmanLatchPayload.applyOnClient(payload)));
    }

    private TrainDeadmanNetwork() {
    }
}
