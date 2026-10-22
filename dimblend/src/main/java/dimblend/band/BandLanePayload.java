package dimblend.band;

import dimblend.client.ClientBandLane;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server → client push telling one client which lane (see
 * {@link dimblend.worldgen.BandLayout#laneName}) the band it currently stands
 * in belongs to, plus the scripted outward neighbor's lane ("" = random draw,
 * displayed as "未知"). Drives client-only cosmetic features: the Biome
 * Notifier "地下" suffix (see dimblend.mixin.BiomeNotifierMixin) and the
 * current/next region names above the band progress HUD (see
 * dimblend.client.BandProgressHud). Sent only on region change, by
 * {@link BandLaneSync}. Registered on both sides via
 * {@link #register(PayloadRegistrar)}; the handler only ever runs on the
 * client, so the {@link ClientBandLane} reference is resolved lazily and is
 * safe on a dedicated server.
 */
public record BandLanePayload(String lane, String nextLane) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BandLanePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "band_lane"));

    public static final StreamCodec<FriendlyByteBuf, BandLanePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BandLanePayload::lane,
            ByteBufCodecs.STRING_UTF8, BandLanePayload::nextLane,
            BandLanePayload::new);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, BandLanePayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<BandLanePayload> type() {
        return TYPE;
    }

    private static void handle(BandLanePayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        ClientBandLane.apply(payload.lane(), payload.nextLane());
    }
}
