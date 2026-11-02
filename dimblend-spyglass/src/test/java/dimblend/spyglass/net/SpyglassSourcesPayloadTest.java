package dimblend.spyglass.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.api.SourceCatalog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class SpyglassSourcesPayloadTest {
    private static final SourceCatalog.Source TERRALITH =
            new SourceCatalog.Source("terralith", "Terralith", true, true);
    private static final SourceCatalog.Source AETHER =
            new SourceCatalog.Source("aether", "aether", false, false);

    @Test
    void roundTripsTheCatalogPayload() {
        SpyglassSourcesPayload original =
                new SpyglassSourcesPayload(List.of(TERRALITH, AETHER), true, "saved");
        ByteBuf buffer = Unpooled.buffer();
        try {
            SpyglassSourcesPayload.STREAM_CODEC.encode(buffer, original);
            SpyglassSourcesPayload decoded = SpyglassSourcesPayload.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
            assertEquals(TERRALITH, decoded.sources().get(0));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsTheEditPayload() {
        SpyglassSourceEditPayload original = new SpyglassSourceEditPayload("terralith", false, false);
        ByteBuf buffer = Unpooled.buffer();
        try {
            SpyglassSourceEditPayload.STREAM_CODEC.encode(buffer, original);
            assertEquals(original, SpyglassSourceEditPayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsAnEmptyNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceCatalog.Source("", "x", true, true));

        FriendlyByteBuf buffer = payloadHeader(1);
        try {
            buffer.writeUtf("", SpyglassSourcesPayload.MAX_NAMESPACE_LENGTH);
            writeSourceRemainder(buffer);
            assertThrows(DecoderException.class, () -> SpyglassSourcesPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsANamespaceLongerThanTheLimit() {
        String tooLong = "a".repeat(SpyglassSourcesPayload.MAX_NAMESPACE_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new SpyglassSourcesPayload(
                List.of(new SourceCatalog.Source(tooLong, "x", true, true)), true, ""));

        FriendlyByteBuf buffer = payloadHeader(1);
        try {
            // A malicious peer can put a longer string on the wire; the decode must reject it.
            buffer.writeUtf(tooLong, 127);
            writeSourceRemainder(buffer);
            assertThrows(DecoderException.class, () -> SpyglassSourcesPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsMoreSourcesThanTheMaximum() {
        List<SourceCatalog.Source> tooMany = Collections.nCopies(
                SpyglassSourcesPayload.MAX_SOURCES + 1, TERRALITH);
        assertThrows(IllegalArgumentException.class,
                () -> new SpyglassSourcesPayload(tooMany, true, ""));

        FriendlyByteBuf buffer = payloadHeader(SpyglassSourcesPayload.MAX_SOURCES + 1);
        try {
            assertThrows(DecoderException.class, () -> SpyglassSourcesPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void emptyCatalogIsNotEditableAndHasNoSources() {
        SourceCatalog catalog = SourceCatalog.empty("no_permission");
        assertFalse(catalog.editable());
        assertTrue(catalog.sources().isEmpty());
        assertEquals("no_permission", catalog.message());
    }

    private static FriendlyByteBuf payloadHeader(int sourceCount) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(sourceCount);
        return buffer;
    }

    private static void writeSourceRemainder(FriendlyByteBuf buffer) {
        buffer.writeUtf("x", SpyglassSourcesPayload.MAX_DISPLAY_NAME_LENGTH);
        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
    }
}
