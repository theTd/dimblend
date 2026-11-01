package dimblend.spyglass.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SpyglassSnapshotPayloadTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("test", "world");
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final StructureKey KEY = new StructureKey(ID, 3, -7);

    @Test
    void roundTripsDuplicateStructureTypesBoundsAndPieces() {
        List<StructureOutline> structures = List.of(
                StructureOutline.of(KEY, List.of(new OutlineBox(-10, -20, -30, 40, 50, 60))),
                StructureOutline.of(KEY, List.of(
                        new OutlineBox(100, 0, 100, 109, 10, 109),
                        new OutlineBox(120, 0, 100, 129, 10, 109))));
        SpyglassSnapshotPayload original = new SpyglassSnapshotPayload(DIMENSION, 42, structures);
        ByteBuf buffer = Unpooled.buffer();
        try {
            SpyglassSnapshotPayload.STREAM_CODEC.encode(buffer, original);
            SpyglassSnapshotPayload decoded = SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
            assertEquals(KEY, decoded.structures().get(0).key());
            assertEquals(ID, decoded.structures().get(0).id());
            assertEquals(structures.get(1).bounds(), decoded.structures().get(1).bounds());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedSnapshotsAtConstruction() {
        StructureOutline outline = StructureOutline.of(KEY, List.of(new OutlineBox(0, 0, 0, 1, 1, 1)));
        List<StructureOutline> structures = Collections.nCopies(
                SpyglassSnapshotPayload.MAX_STRUCTURES + 1, outline);

        assertThrows(IllegalArgumentException.class,
                () -> new SpyglassSnapshotPayload(DIMENSION, 1, structures));
    }

    @Test
    void rejectsExcessiveDecodedStructureCount() {
        FriendlyByteBuf buffer = header(SpyglassSnapshotPayload.MAX_STRUCTURES + 1);
        try {
            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsEmptyPieceListInDecode() {
        FriendlyByteBuf buffer = header(1);
        try {
            writeKey(buffer);
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeVarInt(0);

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsExcessiveDecodedPieceCountPerStructure() {
        FriendlyByteBuf buffer = header(1);
        try {
            writeKey(buffer);
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeVarInt(SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE + 1);

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsInvertedPieceBox() {
        FriendlyByteBuf buffer = header(1);
        try {
            writeKey(buffer);
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeVarInt(1);
            writeBox(buffer, 5, 0, 0, 1, 1, 1);

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOverflowingPieceCoordinates() {
        FriendlyByteBuf buffer = header(1);
        try {
            writeKey(buffer);
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeVarInt(1);
            writeBox(buffer, 0, 0, 0, Integer.MAX_VALUE, 1, 1);

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsBoundsThatDoNotEncloseThePieces() {
        FriendlyByteBuf buffer = header(1);
        try {
            writeKey(buffer);
            // Bounds deliberately smaller than the transmitted piece.
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeVarInt(1);
            writeBox(buffer, 0, 0, 0, 10, 1, 1);
            writeFlags(buffer, false);

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsUnknownFlagsInsteadOfIgnoringThem() {
        // An unknown bit means the sender speaks a newer protocol. Ignoring it would silently draw a
        // predicted outline as a real building, so the decode must fail.
        FriendlyByteBuf buffer = header(1);
        try {
            writeKey(buffer);
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeVarInt(1);
            writeBox(buffer, 0, 0, 0, 1, 1, 1);
            buffer.writeByte(0x02);

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void keepsThePredictedFlagThroughAJsonFreeRoundTrip() {
        StructureOutline real = StructureOutline.of(KEY, List.of(new OutlineBox(0, 0, 0, 1, 1, 1)));
        StructureOutline predicted = StructureOutline.predicted(
                new StructureKey(ID, KEY.startChunkX() + 1, KEY.startChunkZ()),
                List.of(new OutlineBox(4, 0, 4, 9, 8, 9)));
        SpyglassSnapshotPayload payload =
                new SpyglassSnapshotPayload(DIMENSION, 7, List.of(real, predicted));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SpyglassSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            SpyglassSnapshotPayload decoded = SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer);

            assertEquals(2, decoded.structures().size());
            assertFalse(decoded.structures().get(0).predicted());
            assertTrue(decoded.structures().get(1).predicted());
            assertEquals(real, decoded.structures().get(0));
            assertEquals(predicted, decoded.structures().get(1));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsTotalPieceCountOverTheCap() {
        FriendlyByteBuf buffer = header(5);
        try {
            for (int structure = 0; structure < 5; structure++) {
                writeKey(buffer);
                writeBox(buffer, 0, 0, 0, 1, 1, 1);
                buffer.writeVarInt(SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE);
                for (int piece = 0; piece < SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE; piece++) {
                    writeBox(buffer, 0, 0, 0, 1, 1, 1);
                }
            }

            assertThrows(DecoderException.class, () -> SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void keepsTruncatedOutlinesWithTheirExactBounds() {
        List<OutlineBox> pieces = new ArrayList<>();
        for (int i = 0; i < SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE; i++) {
            pieces.add(new OutlineBox(i, 0, 0, i, 0, 0));
        }
        // The exact union bounds cover a piece that is not transmitted.
        StructureOutline outline = new StructureOutline(
                KEY, new OutlineBox(-50, 0, 0, 100_000, 0, 0), pieces);
        SpyglassSnapshotPayload payload = new SpyglassSnapshotPayload(DIMENSION, 3, List.of(outline));

        ByteBuf buffer = Unpooled.buffer();
        try {
            SpyglassSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            SpyglassSnapshotPayload decoded = SpyglassSnapshotPayload.STREAM_CODEC.decode(buffer);
            assertEquals(outline.bounds(), decoded.structures().get(0).bounds());
            assertEquals(outline, decoded.structures().get(0));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf header(int structureCount) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeResourceLocation(DIMENSION);
        buffer.writeLong(1L);
        buffer.writeVarInt(structureCount);
        return buffer;
    }

    private static void writeKey(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(ID);
        buffer.writeInt(KEY.startChunkX());
        buffer.writeInt(KEY.startChunkZ());
    }

    /** The trailing per-structure flags byte; a hand-built structure must always end with one. */
    private static void writeFlags(FriendlyByteBuf buffer, boolean predicted) {
        buffer.writeByte(predicted ? FLAG_PREDICTED : 0);
    }

    private static final int FLAG_PREDICTED = 0x01;

    private static void writeBox(
            FriendlyByteBuf buffer, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        buffer.writeInt(minX);
        buffer.writeInt(minY);
        buffer.writeInt(minZ);
        buffer.writeInt(maxX);
        buffer.writeInt(maxY);
        buffer.writeInt(maxZ);
    }
}
