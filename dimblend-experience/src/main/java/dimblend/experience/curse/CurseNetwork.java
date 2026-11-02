package dimblend.experience.curse;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 诅咒网络注册（MOD 总线）：只有一条 C2S「点击了」信号，供消失诅咒在服务端掷骰。
 * 与昵称同步同款写法，协议版本 "1"。
 */
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CurseNetwork {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(CurseClickPayload.TYPE, CurseClickPayload.STREAM_CODEC, CurseClickPayload::handle);
    }

    private CurseNetwork() {
    }
}
