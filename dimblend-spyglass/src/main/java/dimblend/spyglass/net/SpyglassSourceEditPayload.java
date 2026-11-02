package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server source panel action. {@code open} asks for the catalog; otherwise the packet
 * turns one namespace on or off. The server validates permission and the namespace; this record
 * never throws on a malformed peer value, it just normalizes {@code null} to an empty string and
 * lets the handler reject it.
 */
public record SpyglassSourceEditPayload(String namespace, boolean enabled, boolean open)
        implements CustomPacketPayload {
    public static final int MAX_NAMESPACE_LENGTH = 64;

    public static final Type<SpyglassSourceEditPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "source_edit"));

    public static final StreamCodec<ByteBuf, SpyglassSourceEditPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                friendly.writeUtf(payload.namespace(), MAX_NAMESPACE_LENGTH);
                friendly.writeBoolean(payload.enabled());
                friendly.writeBoolean(payload.open());
            },
            buf -> {
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                return new SpyglassSourceEditPayload(
                        friendly.readUtf(MAX_NAMESPACE_LENGTH), friendly.readBoolean(), friendly.readBoolean());
            });

    public SpyglassSourceEditPayload {
        namespace = namespace == null ? "" : namespace;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
