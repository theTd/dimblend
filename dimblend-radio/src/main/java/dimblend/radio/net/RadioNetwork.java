package dimblend.radio.net;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.net.RadioTunePayloads;
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

    /**
     * 网络协议版本。
     *
     * <p>2026-11-03 由 "1" 升到 "2"：{@code RadioStatePayload} 末尾追加了 {@code volumePercent}
     * 字段。旧版客户端会把这一条读成它不认识的字段并错位解码，所以版本必须一起升，
     * 让不匹配的连接在握手期就被拒绝，而不是在播放时把音量读成曲名的一部分。
     */
    public static final String PROTOCOL_VERSION = "2";

    public static void register(IEventBus modEventBus) {
        // 实际注册走 MOD 总线事件，方法保留给入口显式调用、语义与 experience 的 NicknameNetwork 一致
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(RadioStatePayload.TYPE, RadioStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> RadioStatePayload.applyOnClient(payload)));
        registrar.playToServer(RadioHelloPayload.TYPE, RadioHelloPayload.STREAM_CODEC, RadioHelloPayload::handle);
        // 唱片机 tune 面板（2026-11-03 从 dbx tune 搬来）：请求打开 → 服务端校验后回快照 → 单条编辑
        registrar.playToServer(RadioTunePayloads.Request.TYPE, RadioTunePayloads.Request.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        dimblend.radio.server.RadioTuneService.open(player, payload);
                    }
                }));
        registrar.playToServer(RadioTunePayloads.Edit.TYPE, RadioTunePayloads.Edit.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        dimblend.radio.server.RadioTuneService.edit(player, payload);
                    }
                }));
        registrar.playToClient(RadioTunePayloads.Snapshot.TYPE, RadioTunePayloads.Snapshot.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dimblend.radio.client.RadioTuneClient.receive(payload)));
    }

    private RadioNetwork() {
    }
}
