package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server reveal distance, in blocks: the start of the client's terrain fog (see
 * {@code SpyglassRevealState}). The server uses it to decide which structures are still worth
 * streaming; it is not authoritative for drawing, the client re-filters every frame with its live
 * value.
 *
 * <p>The value is quantized to 8 blocks by the sender and clamped to the display range by the
 * receiver. Sent only when the quantized value changed (at most once per 20 client ticks).
 */
public record SpyglassRevealPayload(int blocks) implements CustomPacketPayload {
    public static final Type<SpyglassRevealPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "reveal_distance"));

    public static final StreamCodec<FriendlyByteBuf, SpyglassRevealPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeVarInt(payload.blocks),
            buffer -> new SpyglassRevealPayload(buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
