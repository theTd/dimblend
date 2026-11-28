package dimblend.mirage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server → client: the lane pair of one seam, sent immediately when a mirage request is
 * accepted. Three fields on purpose — the client needs the far sky/fog/light identity before the
 * first (much larger) {@link MirageChunkPayload} arrives.
 *
 * @param slabX    seam block X ({@code k * bandSize})
 * @param westLane {@link dimblend.worldgen.BandLayout#laneName} of the band west of the seam
 * @param eastLane same for the east band
 */
public record BandSeamInfoPayload(int slabX, String westLane, String eastLane)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BandSeamInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "band_seam_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BandSeamInfoPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BandSeamInfoPayload::slabX,
                    ByteBufCodecs.STRING_UTF8, BandSeamInfoPayload::westLane,
                    ByteBufCodecs.STRING_UTF8, BandSeamInfoPayload::eastLane,
                    BandSeamInfoPayload::new);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, BandSeamInfoPayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<BandSeamInfoPayload> type() {
        return TYPE;
    }

    private static void handle(BandSeamInfoPayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        BandSeamInfoClientReceiver.accept(payload);
    }
}
