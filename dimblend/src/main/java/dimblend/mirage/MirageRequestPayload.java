package dimblend.mirage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client → server: generate the mirage rectangle for one seam and side.
 *
 * <p>Region shape: {@code depthX} chunks outward from the seam in X and
 * {@code 2 * halfWidthZ + 1} chunks centred on {@code centerChunkZ}. {@link MirageChunkKey}
 * defines which band generates it and on which side the chunks are placed. The server clamps
 * {@code depthX} to {@code 1..8} and {@code halfWidthZ} to {@code 0..6}, and answers with
 * {@link BandSeamInfoPayload} immediately (the lane pair is known without generating).</p>
 *
 * <p>Sent only by the client; the handler validates the player, the seam column and the
 * distance before any work is queued ({@link MirageServerManager}).</p>
 */
public record MirageRequestPayload(int slabX, byte side, int centerChunkZ, int depthX, int halfWidthZ)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MirageRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "mirage_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MirageRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MirageRequestPayload::slabX,
                    ByteBufCodecs.BYTE, MirageRequestPayload::side,
                    ByteBufCodecs.VAR_INT, MirageRequestPayload::centerChunkZ,
                    ByteBufCodecs.VAR_INT, MirageRequestPayload::depthX,
                    ByteBufCodecs.VAR_INT, MirageRequestPayload::halfWidthZ,
                    MirageRequestPayload::new);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, STREAM_CODEC, MirageRequestPayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<MirageRequestPayload> type() {
        return TYPE;
    }

    private static void handle(MirageRequestPayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        if (context.player() instanceof ServerPlayer player) {
            MirageServerManager.get().onRequest(player, payload);
        }
    }
}
