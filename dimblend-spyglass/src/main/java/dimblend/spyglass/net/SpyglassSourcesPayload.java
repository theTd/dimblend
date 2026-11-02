package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.api.SourceCatalog;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client source catalog. Wire format: source count, then per row the namespace, display
 * name, enabled and mod-loaded booleans, then the editable flag and the status key.
 *
 * <p>Bounds are enforced on both ends: a peer cannot make the client allocate an unbounded list or
 * install an empty or over-long namespace.
 */
public record SpyglassSourcesPayload(List<SourceCatalog.Source> sources, boolean editable, String message)
        implements CustomPacketPayload {
    public static final int MAX_SOURCES = 256;
    public static final int MAX_NAMESPACE_LENGTH = SourceCatalog.Source.MAX_NAMESPACE_LENGTH;
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;
    public static final int MAX_MESSAGE_LENGTH = 32;

    public static final Type<SpyglassSourcesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "sources"));

    public static final StreamCodec<ByteBuf, SpyglassSourcesPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpyglassSourcesPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            int count = friendly.readVarInt();
            if (count < 0 || count > MAX_SOURCES) {
                throw new DecoderException("Invalid source count: " + count);
            }
            List<SourceCatalog.Source> sources = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String namespace = friendly.readUtf(MAX_NAMESPACE_LENGTH);
                String displayName = friendly.readUtf(MAX_DISPLAY_NAME_LENGTH);
                boolean enabled = friendly.readBoolean();
                boolean modLoaded = friendly.readBoolean();
                try {
                    sources.add(new SourceCatalog.Source(namespace, displayName, enabled, modLoaded));
                } catch (IllegalArgumentException invalid) {
                    throw new DecoderException("Invalid source row: " + invalid.getMessage(), invalid);
                }
            }
            boolean editable = friendly.readBoolean();
            String message = friendly.readUtf(MAX_MESSAGE_LENGTH);
            try {
                return new SpyglassSourcesPayload(sources, editable, message);
            } catch (IllegalArgumentException invalid) {
                throw new DecoderException("Invalid source payload: " + invalid.getMessage(), invalid);
            }
        }

        @Override
        public void encode(ByteBuf buf, SpyglassSourcesPayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeVarInt(payload.sources().size());
            for (SourceCatalog.Source source : payload.sources()) {
                friendly.writeUtf(source.namespace(), MAX_NAMESPACE_LENGTH);
                friendly.writeUtf(source.displayName(), MAX_DISPLAY_NAME_LENGTH);
                friendly.writeBoolean(source.enabled());
                friendly.writeBoolean(source.correspondingModLoaded());
            }
            friendly.writeBoolean(payload.editable());
            friendly.writeUtf(payload.message(), MAX_MESSAGE_LENGTH);
        }
    };

    public SpyglassSourcesPayload {
        sources = List.copyOf(sources);
        message = message == null ? "" : message;
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("Too many sources: " + sources.size());
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Message is longer than " + MAX_MESSAGE_LENGTH);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
