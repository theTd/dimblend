package dimblend.band;

import dimblend.client.ClientBandProgress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server → client push carrying the rotating dimension's band size so the
 * client can derive band index and within-band progress from its own X
 * coordinate for the HUD (see dimblend.client.BandProgressHud). Registered on
 * both sides via {@link #register(PayloadRegistrar)}; the handler only ever
 * runs on the client, so the {@link ClientBandProgress} reference is resolved
 * lazily and is safe on a dedicated server.
 */
public record BandInfoPayload(int bandSize) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BandInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "band_info"));

    public static final StreamCodec<FriendlyByteBuf, BandInfoPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, BandInfoPayload::bandSize,
            BandInfoPayload::new);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, BandInfoPayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<BandInfoPayload> type() {
        return TYPE;
    }

    private static void handle(BandInfoPayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        ClientBandProgress.apply(payload.bandSize());
    }
}
