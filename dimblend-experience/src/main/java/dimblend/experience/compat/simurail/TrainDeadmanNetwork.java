package dimblend.experience.compat.simurail;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * E7' 无人值守刹车网络注册（MOD 总线）。客户端应用 lambda 只在物理客户端执行。
 *
 * <p>2026-11-27 Slice 10 起这里还登记全图 {@code train_brake} 播报
 * （{@link GlobalTrainBrakeSoundPayload}）：末影刹车/常规施闸都是服务端一次性事件，
 * 由客户端各自建无衰减实例，故走同一条 S2C 通道。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TrainDeadmanNetwork {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToClient(DeadmanLatchPayload.TYPE, DeadmanLatchPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> DeadmanLatchPayload.applyOnClient(payload)));
        MinimumSpeedSync.registerPayloads(event, registrar);
        TrainBrakeSoundSync.registerPayloads(registrar);
        // 2026-11-27 Slice 10：全图一次性刹车播报（末影刹车 / 常规施闸）。
        // 注册在同一个 registrar 版本下：这是新增 payload，两端由同一个 jar 提供，
        // 抬版本号只会把「只差这一条新包」的对端整条通道拒掉，不解决问题。
        registrar.playToClient(GlobalTrainBrakeSoundPayload.TYPE, GlobalTrainBrakeSoundPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> GlobalTrainBrakeSoundPayload.applyOnClient(payload)));
    }

    private TrainDeadmanNetwork() {
    }
}
