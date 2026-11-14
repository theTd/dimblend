package dimblend.band;

import dimblend.client.ClientBandTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 服务端 → 客户端：区域进度条白线要参照的 sable 质心 x，以及是否画成警戒红。
 *
 * <p>白线的参照位置必须在服务端算：force-load 票据只在 {@code ServerSubLevelContainer} 里，
 * 客户端接口（{@code SubLevelAccess}）没有任何票据信息；而且 sable 网络跟踪超过 320 格就把
 * 整个 sublevel 从客户端删掉。服务端只在“本维度存在有票据且 |z| ≤ 7 的 sable”且位置变化时
 * 下发，客户端缓存最近一次值（见 {@link ClientBandTrain}），因此玩家跑远后白线停在最后位置，
 * 不再整条消失（需求 #6）。</p>
 *
 * <p>{@code alert} 只有 {@link dimblend.api.BandTrainMarker#override()} 生效时才为 true：那时
 * 目标由模组指定（龙巢），不受 320 格候选窗口限制，白线按需求 §3.3 画成红色。旧版只有一个
 * double，客户端与旧服务端不兼容，但这条通道随本模组一起发版，不存在跨版本混用。</p>
 */
public record BandTrainPayload(double centerX, boolean alert) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BandTrainPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "band_train"));

    public static final StreamCodec<FriendlyByteBuf, BandTrainPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, BandTrainPayload::centerX,
            ByteBufCodecs.BOOL, BandTrainPayload::alert,
            BandTrainPayload::new);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, BandTrainPayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<BandTrainPayload> type() {
        return TYPE;
    }

    private static void handle(BandTrainPayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        // ClientBandTrain 只在客户端解析，专用服务端不会加载它（JVM 惰性解析）。
        ClientBandTrain.apply(payload.centerX(), payload.alert());
    }
}
