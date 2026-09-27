package dimblend.radio.net;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.server.RadioSync;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络注册（MOD 总线）。客户端应用 lambda 只在物理客户端执行，
 * 从而 client 包的类不会在 dedicated server 上被解析。
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RadioNetwork {

    public static void register(IEventBus modEventBus) {
        // 实际注册走 MOD 总线事件，方法保留给入口显式调用、语义与 experience 的 NicknameNetwork 一致
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(RadioStatePayload.TYPE, RadioStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> RadioStatePayload.applyOnClient(payload)));
        registrar.playToServer(RadioHelloPayload.TYPE, RadioHelloPayload.STREAM_CODEC, RadioHelloPayload::handle);
    }

    private RadioNetwork() {
    }
}
