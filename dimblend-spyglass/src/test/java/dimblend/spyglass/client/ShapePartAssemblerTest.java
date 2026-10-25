package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShapeCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Reassembles streamed shape parts and checks the shape payload wire format.
 *
 * <p>The bodies are opaque to the assembler, so a synthetic body exercises the part splitting and the
 * wire round trip without needing a 262,144-block shape; the real body format is covered by
 * {@code StructureShapeCodecTest}.
 */
class ShapePartAssemblerTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("test", "world");
    private static final StructureKey KEY = new StructureKey(
            ResourceLocation.fromNamespaceAndPath("test", "house"), 12, -4);

    @Test
    void reassemblesPartsArrivingOutOfOrder() {
        byte[] body = body(600 * 1024);
        List<SpyglassShapePayload> parts = split(KEY, 5, body);
        assertTrue(parts.size() > 2);

        ShapePartAssembler assembler = new ShapePartAssembler();
        ShapePartAssembler.AssembledBody assembled = null;
        // Last part first, then the rest: the body only completes when every part is present.
        assertNull(assembler.accept(parts.get(parts.size() - 1)));
        for (int i = 0; i < parts.size() - 1; i++) {
            ShapePartAssembler.AssembledBody result = assembler.accept(parts.get(i));
            if (i < parts.size() - 2) {
                assertNull(result);
            } else {
                assembled = result;
            }
        }

        assertEquals(DIMENSION, assembled.dimension());
        assertEquals(KEY, assembled.key());
        assertEquals(5, assembled.revision());
        assertArrayEquals(body, assembled.body());
        assertEquals(0, assembler.inFlight());
    }

    @Test
    void payloadPartsRoundTripThroughTheStreamCodec() {
        byte[] body = body(600 * 1024);
        List<SpyglassShapePayload> parts = split(KEY, 9, body);
        List<SpyglassShapePayload> decodedParts = new ArrayList<>();
        for (SpyglassShapePayload part : parts) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                SpyglassShapePayload.STREAM_CODEC.encode(buffer, part);
                decodedParts.add(SpyglassShapePayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        assertEquals(parts, decodedParts);

        ShapePartAssembler assembler = new ShapePartAssembler();
        ShapePartAssembler.AssembledBody assembled = null;
        for (SpyglassShapePayload part : decodedParts) {
            assembled = assembler.accept(part);
        }
        assertArrayEquals(body, assembled.body());
    }

    @Test
    void newestRevisionReplacesAnOlderInFlightBody() {
        byte[] revisionFive = body(PART_BYTES + 100);
        byte[] revisionSeven = body(PART_BYTES + 50);
        List<SpyglassShapePayload> old = split(KEY, 5, revisionFive);
        List<SpyglassShapePayload> fresh = split(KEY, 7, revisionSeven);

        ShapePartAssembler assembler = new ShapePartAssembler();
        assertNull(assembler.accept(old.get(0)));
        assertNull(assembler.accept(fresh.get(0)));
        ShapePartAssembler.AssembledBody assembled = assembler.accept(fresh.get(1));
        assertEquals(7, assembled.revision());
        assertArrayEquals(revisionSeven, assembled.body());

        // The remaining part of the replaced revision must not complete the newer body.
        assertNull(assembler.accept(old.get(1)));
    }

    @Test
    void ignoresPartsOfTheWrongDimensionAndKeepsInFlightMemoryBounded() {
        ShapePartAssembler assembler = new ShapePartAssembler();
        byte[] fullPart = new byte[PART_BYTES];
        SpyglassShapePayload otherDimension = new SpyglassShapePayload(
                ResourceLocation.fromNamespaceAndPath("test", "elsewhere"),
                KEY,
                1,
                0,
                2,
                fullPart);
        SpyglassShapePayload firstPart = new SpyglassShapePayload(DIMENSION, KEY, 1, 0, 2, fullPart);
        assembler.accept(firstPart);
        assertNull(assembler.accept(otherDimension));

        // A partial body per structure key: the assembler must evict instead of buffering them all.
        for (int i = 0; i < ShapePartAssembler.MAX_IN_FLIGHT + 32; i++) {
            StructureKey key = new StructureKey(KEY.id(), i, 0);
            assembler.accept(new SpyglassShapePayload(DIMENSION, key, 1, 0, 2, fullPart));
        }
        assertTrue(assembler.inFlight() <= ShapePartAssembler.MAX_IN_FLIGHT);
        assertTrue(assembler.inFlightBytes() <= ShapePartAssembler.MAX_IN_FLIGHT_BYTES);
        assertTrue(assembler.inFlightBytes() > 0);
    }

    @Test
    void rejectsInvalidPartCountsAndSizesAtDecode() {
        // partCount above the cap.
        assertThrows(DecoderException.class, () -> decode(0, SpyglassShapePayload.MAX_PART_COUNT + 1, new byte[10]));
        // partIndex outside [0, partCount).
        assertThrows(DecoderException.class, () -> decode(2, 2, new byte[10]));
        // An empty part.
        assertThrows(DecoderException.class, () -> decode(0, 1, new byte[0]));
        // A non-final part that is not full size.
        assertThrows(DecoderException.class, () -> decode(0, 2, new byte[10]));
        // A negative revision.
        assertThrows(DecoderException.class, () -> decodeRevision(-1));
    }

    @Test
    void acceptsTheLargestAllowedBody() {
        byte[] body = body(StructureShapeCodec.MAX_BODY_BYTES);
        List<SpyglassShapePayload> parts = split(KEY, 1, body);
        assertEquals(SpyglassShapePayload.MAX_PART_COUNT, parts.size());
        ShapePartAssembler assembler = new ShapePartAssembler();
        ShapePartAssembler.AssembledBody assembled = null;
        for (int i = parts.size() - 1; i >= 0; i--) {
            assembled = assembler.accept(parts.get(i));
        }
        assertArrayEquals(body, assembled.body());
    }

    private static byte[] body(int length) {
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        return body;
    }

    private static List<SpyglassShapePayload> split(StructureKey key, int revision, byte[] body) {
        int partCount = (body.length + PART_BYTES - 1) / PART_BYTES;
        List<SpyglassShapePayload> parts = new ArrayList<>(partCount);
        for (int index = 0; index < partCount; index++) {
            int offset = index * PART_BYTES;
            int length = Math.min(PART_BYTES, body.length - offset);
            byte[] data = new byte[length];
            System.arraycopy(body, offset, data, 0, length);
            parts.add(new SpyglassShapePayload(DIMENSION, key, revision, index, partCount, data));
        }
        return parts;
    }

    private static SpyglassShapePayload decode(int partIndex, int partCount, byte[] data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION);
            buffer.writeResourceLocation(KEY.id());
            buffer.writeInt(KEY.startChunkX());
            buffer.writeInt(KEY.startChunkZ());
            buffer.writeVarInt(1);
            buffer.writeVarInt(partIndex);
            buffer.writeVarInt(partCount);
            buffer.writeBytes(data);
            return SpyglassShapePayload.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static SpyglassShapePayload decodeRevision(int revision) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION);
            buffer.writeResourceLocation(KEY.id());
            buffer.writeInt(KEY.startChunkX());
            buffer.writeInt(KEY.startChunkZ());
            buffer.writeVarInt(revision);
            buffer.writeVarInt(0);
            buffer.writeVarInt(1);
            buffer.writeBytes(new byte[4]);
            return SpyglassShapePayload.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    /** Local alias so the payload constants read naturally in this test. */
    private static final int PART_BYTES = SpyglassShapePayload.MAX_PART_BYTES;
}
