package dimblend.mirage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * The mirage wire format is the one thing both sides must agree on byte-for-byte. These tests
 * stay registry-free: {@link MirageSectionCodec#encodeRaw}/decodeRaw only move ints and shorts,
 * and the payload round-trip uses an empty {@link RegistryAccess} because no block-state lookup
 * happens on the wire.
 */
class MirageSectionCodecTest {
    private static final int BAND = 4096;

    @Test
    void roundTripsAnEmptySectionList() {
        byte[] encoded = MirageSectionCodec.encodeRaw(-4, 24, List.of());
        MirageSectionCodec.ChunkData decoded = MirageSectionCodec.decodeRaw(encoded);
        assertEquals(-4, decoded.minSectionY());
        assertEquals(24, decoded.sectionCount());
        assertTrue(decoded.sections().isEmpty());
        assertTrue(encoded.length < 128, "an all-air chunk must be tiny, got " + encoded.length);
    }

    @Test
    void roundTripsOneBlockSection() {
        short[] indices = new short[MirageSectionCodec.SECTION_BLOCKS];
        indices[(7 << 8) | (9 << 4) | 3] = 1; // x=3, y=7, z=9
        MirageSectionCodec.Section section =
                new MirageSectionCodec.Section(5, new int[] {0, 4242}, indices);
        byte[] encoded = MirageSectionCodec.encodeRaw(0, 16, List.of(section));
        MirageSectionCodec.ChunkData decoded = MirageSectionCodec.decodeRaw(encoded);
        assertEquals(0, decoded.minSectionY());
        assertEquals(16, decoded.sectionCount());
        assertSectionEquals(section, decoded.sections().get(0));
    }

    @Test
    void roundTripsMixedPaletteWithRuns() {
        short[] first = new short[MirageSectionCodec.SECTION_BLOCKS];
        // 0..2047 air, 2048..3071 stone, 3072..4095 glass.
        for (int i = 2048; i < 3072; i++) {
            first[i] = 1;
        }
        for (int i = 3072; i < 4096; i++) {
            first[i] = 2;
        }
        short[] second = new short[MirageSectionCodec.SECTION_BLOCKS];
        for (int i = 0; i < second.length; i++) {
            second[i] = (short) (i & 3);
        }
        List<MirageSectionCodec.Section> sections = List.of(
                new MirageSectionCodec.Section(2, new int[] {0, 17, 9001}, first),
                new MirageSectionCodec.Section(9, new int[] {10, 11, 12, 13}, second));
        byte[] encoded = MirageSectionCodec.encodeRaw(-4, 24, sections);
        MirageSectionCodec.ChunkData decoded = MirageSectionCodec.decodeRaw(encoded);
        assertEquals(2, decoded.sections().size());
        assertSectionEquals(sections.get(0), decoded.sections().get(0));
        assertSectionEquals(sections.get(1), decoded.sections().get(1));
        assertTrue(encoded.length < MirageSectionCodec.SECTION_BLOCKS * 2,
                "run-length + deflate must beat raw 16-bit indices, got " + encoded.length);
    }

    @Test
    void rejectsMalformedSections() {
        assertThrows(IllegalArgumentException.class,
                () -> new MirageSectionCodec.Section(0, new int[0], new short[4096]));
        assertThrows(IllegalArgumentException.class,
                () -> new MirageSectionCodec.Section(0, new int[] {1}, new short[16]));
        short[] badIndex = new short[4096];
        badIndex[0] = 2;
        assertThrows(IllegalArgumentException.class,
                () -> new MirageSectionCodec.Section(0, new int[] {1, 2}, badIndex));
        assertThrows(IllegalArgumentException.class,
                () -> MirageSectionCodec.decodeRaw(new byte[] {1, 2, 3}));
    }

    @Test
    void payloadRoundTripsKeyHeightmapAndEmissive() {
        short[] heightmap = new short[MirageSectionCodec.HEIGHTMAP_ENTRIES];
        for (int i = 0; i < heightmap.length; i++) {
            heightmap[i] = (short) (-64 + i);
        }
        int[] emissive = {
                (12 << 12) | (3 << 8) | (9 << 4) | 14,
                (300 << 12) | (15 << 8) | (1 << 4) | 7,
        };
        MirageChunkKey key = new MirageChunkKey(BAND, MirageChunkKey.WEST_BAND_INTO_EAST, BAND >> 4, -3);
        MirageChunkPayload original = new MirageChunkPayload(
                key, 4, "twilight", "nether", -4, 24,
                MirageSectionCodec.encodeRaw(-4, 24, List.of()), heightmap, emissive);

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MirageChunkPayload.STREAM_CODEC.encode(buffer, original);
            MirageChunkPayload decoded = MirageChunkPayload.STREAM_CODEC.decode(buffer);
            assertEquals(original.key(), decoded.key());
            assertEquals(original.delegateIndex(), decoded.delegateIndex());
            assertEquals(original.laneName(), decoded.laneName());
            assertEquals(original.otherLaneName(), decoded.otherLaneName());
            assertEquals(original.minSectionY(), decoded.minSectionY());
            assertEquals(original.sectionCount(), decoded.sectionCount());
            assertArrayEquals(original.sections(), decoded.sections());
            assertArrayEquals(original.heightmap(), decoded.heightmap());
            assertArrayEquals(original.emissive(), decoded.emissive());
            assertFalse(buffer.isReadable(), "codec must consume exactly one payload");
        } finally {
            buffer.release();
        }
    }

    @Test
    void requestPayloadKeepsTheRectangleShape() {
        MirageRequestPayload original = new MirageRequestPayload(BAND, (byte) -1, -7, 5, 4);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MirageRequestPayload.STREAM_CODEC.encode(buffer, original);
            MirageRequestPayload decoded = MirageRequestPayload.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void chunkKeyRejectsNonSeamSides() {
        assertThrows(IllegalArgumentException.class,
                () -> new MirageChunkKey(BAND, (byte) 0, 0, 0));
        MirageChunkKey west = new MirageChunkKey(BAND, MirageChunkKey.WEST_BAND_INTO_EAST,
                BAND >> 4, 0);
        assertTrue(west.isOnTargetSide());
        MirageChunkKey east = new MirageChunkKey(BAND, MirageChunkKey.EAST_BAND_INTO_WEST,
                (BAND >> 4) - 1, 0);
        assertTrue(east.isOnTargetSide());
        assertEquals(-1, east.side());
    }

    // ---- quart biomes (v2) ---------------------------------------------------------------------

    @Test
    void roundTripsQuartBiomes() {
        int sectionCount = 24;
        int[] palette = {1, 6, 42, 1200};
        short[] indices = new short[sectionCount * MirageSectionCodec.BIOME_CELLS_PER_SECTION];
        // Long runs plus a scattered tail so both RLE paths are exercised.
        for (int i = 0; i < 512; i++) {
            indices[i] = 0;
        }
        for (int i = 512; i < 1400; i++) {
            indices[i] = 2;
        }
        for (int i = 1400; i < indices.length; i++) {
            indices[i] = (short) (i % palette.length);
        }
        byte[] encoded = MirageSectionCodec.encodeRaw(-4, sectionCount, List.of(), palette, indices);
        MirageSectionCodec.ChunkData decoded = MirageSectionCodec.decodeRaw(encoded);
        assertArrayEquals(palette, decoded.biomePalette());
        assertArrayEquals(indices, decoded.biomeIndices());
        assertTrue(decoded.hasBiomes(), "a full quart palette is a usable tint source");
        assertTrue(decoded.sections().isEmpty());
        assertTrue(encoded.length < 2048, "24 sections of quart biomes must stay tiny, got " + encoded.length);
    }

    @Test
    void aPaletteWithoutIndicesIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MirageSectionCodec.encodeRaw(-4, 24, List.of(), new int[] {1},
                        new short[7]));
        // An out-of-range palette index must not survive to the wire either.
        short[] outOfRange = new short[24 * MirageSectionCodec.BIOME_CELLS_PER_SECTION];
        outOfRange[0] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> MirageSectionCodec.encodeRaw(-4, 24, List.of(), new int[] {1}, outOfRange));
    }

    @Test
    void anEmptyBiomePaletteDecodesAsNoBiomes() {
        byte[] encoded = MirageSectionCodec.encodeRaw(-4, 24, List.of(), new int[0], new short[0]);
        MirageSectionCodec.ChunkData decoded = MirageSectionCodec.decodeRaw(encoded);
        assertFalse(decoded.hasBiomes(), "empty palette means fall back to the level tint");
        assertArrayEquals(new int[0], decoded.biomePalette());
        assertArrayEquals(new short[0], decoded.biomeIndices());
    }

    /**
     * v1 blobs (pre-07 payloads, no biome block at all) must still decode: the client falls back
     * to the level tint instead of dropping the chunk.
     */
    @Test
    void decodesLegacyV1BlobsWithoutBiomes() {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.write(0x4D);
        raw.write(0x49);
        raw.write(0x52);
        raw.write(0x53); // "MIRS"
        raw.write(1); // v1
        writeVarInt(raw, ((-4) << 1) ^ ((-4) >> 31)); // zigzag(-4)
        writeVarInt(raw, 24); // sectionCount
        writeVarInt(raw, 0); // sectionEntries
        MirageSectionCodec.ChunkData decoded = MirageSectionCodec.decodeRaw(deflate(raw.toByteArray()));
        assertEquals(-4, decoded.minSectionY());
        assertEquals(24, decoded.sectionCount());
        assertTrue(decoded.sections().isEmpty());
        assertFalse(decoded.hasBiomes(), "v1 carried no quart biomes");
        assertTrue(decoded.biomePalette().length == 0 && decoded.biomeIndices().length == 0);
    }

    private static void writeVarInt(java.io.ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static byte[] deflate(byte[] raw) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void assertSectionEquals(
            MirageSectionCodec.Section expected, MirageSectionCodec.Section actual) {
        assertEquals(expected.sectionIndex(), actual.sectionIndex());
        assertArrayEquals(expected.palette(), actual.palette());
        assertArrayEquals(expected.indices(), actual.indices());
    }
}
