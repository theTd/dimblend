package dimblend.experience.compat.create;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 列车汽笛 payload 注册（客户端落点见 {@code dimblend.experience.client.TrainHornClient}）。 */
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TrainHornNetwork {

    private TrainHornNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(TrainHornPayload.TYPE, TrainHornPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> dimblend.experience.client.TrainHornClient.apply(payload)));
    }
}
