package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.spyglass.test.ChunkNbtFixture;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the production read path ({@link ReadOnlyRegionScanner#scan}) end to end: region file
 * in, outlines out, files untouched.
 */
class ReadOnlyRegionScannerTest {
    /**
     * Target data version used by these tests: only chunks saved with exactly this version are read.
     * Passing it in keeps the read path free of {@code SharedConstants}.
     */
    private static final int DATA_VERSION = 4000;

    private static final ResourceLocation HOUSE = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final ResourceLocation OTHER = ResourceLocation.fromNamespaceAndPath("test", "other");
    private static final ResourceLocation MISSING = ResourceLocation.fromNamespaceAndPath("test", "missing");

    @Test
    void readsInlineChunkAndLeavesRegionUntouched(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        Path region = writeChunk(regionDirectory, 0, 0, 2, false, chunkTag(0, 0, HOUSE, OTHER));
        byte[] beforeBytes = Files.readAllBytes(region);
        FileTime beforeMtime = Files.getLastModifiedTime(region);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertEquals(0, result.errors().total());
        assertEquals(1, result.outlines().size());
        StructureOutline outline = result.outlines().get(0);
        assertEquals(HOUSE, outline.id());
        assertEquals(2, outline.pieces().size());
        assertEquals(new OutlineBox(-10, 0, -20, 10, 20, 30), outline.pieces().get(0));
        assertEquals(new OutlineBox(-10, 0, -20, 60, 21, 70), outline.bounds());
        assertArrayEquals(beforeBytes, Files.readAllBytes(region));
        assertEquals(beforeMtime, Files.getLastModifiedTime(region));
    }

    @Test
    void readsExternalChunkAndLeavesBothFilesUntouched(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        Path region = writeChunk(regionDirectory, -33, 5, 2, true, chunkTag(-33, 5, HOUSE, OTHER));
        Path external = regionDirectory.resolve("c.-33.5.mcc");
        byte[] beforeRegionBytes = Files.readAllBytes(region);
        byte[] beforeExternalBytes = Files.readAllBytes(external);
        FileTime beforeRegionMtime = Files.getLastModifiedTime(region);
        FileTime beforeExternalMtime = Files.getLastModifiedTime(external);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, -33, 5));

        assertEquals(0, result.errors().total());
        assertEquals(1, result.outlines().size());
        assertEquals(HOUSE, result.outlines().get(0).id());
        assertArrayEquals(beforeRegionBytes, Files.readAllBytes(region));
        assertArrayEquals(beforeExternalBytes, Files.readAllBytes(external));
        assertEquals(beforeRegionMtime, Files.getLastModifiedTime(region));
        assertEquals(beforeExternalMtime, Files.getLastModifiedTime(external));
    }

    @Test
    void filtersToTheRequestedStructureIds(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        writeChunk(regionDirectory, 0, 0, 2, false, chunkTag(0, 0, HOUSE, OTHER));

        RegionScanResult onlyOther = scan(regionDirectory, new StructureCandidate(OTHER, 0, 0));
        assertEquals(1, onlyOther.outlines().size());
        assertEquals(OTHER, onlyOther.outlines().get(0).id());

        RegionScanResult missing = scan(regionDirectory, new StructureCandidate(MISSING, 0, 0));
        assertTrue(missing.outlines().isEmpty());
        assertEquals(0, missing.errors().total());
    }

    @Test
    void rejectsChunkWhoseCoordinatesDoNotMatchTheRequest(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        writeChunk(regionDirectory, 0, 0, 2, false, chunkTag(7, 0, HOUSE, OTHER));

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertTrue(result.outlines().isEmpty());
        assertEquals(1, result.errors().identityFailures());
        assertTrue(result.errors().hasAny());
        assertNotNull(result.errors().firstFailure());
    }

    @Test
    void countsOlderDataVersionAsUnsupported(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        CompoundTag older = chunkTag(0, 0, HOUSE);
        older.putInt("DataVersion", DATA_VERSION - 1);
        writeChunk(regionDirectory, 0, 0, 2, false, older);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertTrue(result.outlines().isEmpty());
        assertEquals(1, result.errors().unsupportedDataVersionSkips());
        assertEquals(0, result.errors().identityFailures());
        assertEquals(0, result.errors().parseFailures());
        assertNull(result.errors().firstFailure());
    }

    @Test
    void countsNewerDataVersionAsUnsupported(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        CompoundTag newer = chunkTag(0, 0, HOUSE);
        newer.putInt("DataVersion", DATA_VERSION + 1);
        writeChunk(regionDirectory, 0, 0, 2, false, newer);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertTrue(result.outlines().isEmpty());
        assertEquals(1, result.errors().unsupportedDataVersionSkips());
        assertEquals(0, result.errors().identityFailures());
        assertEquals(0, result.errors().parseFailures());
        assertNull(result.errors().firstFailure());
    }

    @Test
    void missingRegionDoesNotCreateDirectoriesOrFiles(@TempDir Path tempDir) {
        Path world = tempDir.resolve("missing-world");
        Path regionDirectory = world.resolve("region");

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 15, -15));

        assertTrue(result.outlines().isEmpty());
        assertEquals(0, result.errors().total());
        assertFalse(Files.exists(regionDirectory));
        assertFalse(Files.exists(world));
    }

    @Test
    void countsUnreadableChunkWithoutFailingTheScan(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        // Uncompressed region payload that is not NBT: the NBT parser must fail, not the scan.
        writeRawPayload(regionDirectory, 0, 0, "not-nbt!".getBytes(StandardCharsets.US_ASCII), 3);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertTrue(result.outlines().isEmpty());
        assertEquals(1, result.errors().parseFailures());
        assertNotNull(result.errors().firstFailure());
    }

    @Test
    void countsUndecodableChunkEntriesInsteadOfSkippingThemSilently(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        // 127 is vanilla's custom stream version, which a plain reader cannot decode.
        writeRawPayload(regionDirectory, 0, 0, "payload".getBytes(StandardCharsets.US_ASCII), 127);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertTrue(result.outlines().isEmpty());
        assertEquals(1, result.errors().chunkReadFailures());
        assertNotNull(result.errors().firstFailure());
    }

    private static RegionScanResult scan(Path regionDirectory, StructureCandidate... candidates) {
        return ReadOnlyRegionScanner.scan(
                new RegionReadContext(
                        Level.OVERWORLD,
                        DATA_VERSION,
                        regionDirectory,
                        ChunkNbtFixture.MIN_BUILD_HEIGHT,
                        ChunkNbtFixture.LEVEL_HEIGHT),
                List.of(candidates));
    }

    private static CompoundTag chunkTag(int chunkX, int chunkZ, ResourceLocation... ids) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);
        root.putInt("xPos", chunkX);
        root.putInt("zPos", chunkZ);
        root.putString("ignored_payload", "not collected");
        CompoundTag structures = new CompoundTag();
        CompoundTag starts = new CompoundTag();
        for (ResourceLocation id : ids) {
            CompoundTag start = new CompoundTag();
            start.putString("id", id.toString());
            ListTag children = new ListTag();
            children.add(piece(new int[] {-10, 0, -20, 10, 20, 30}));
            children.add(piece(new int[] {40, 1, 50, 60, 21, 70}));
            start.put("Children", children);
            starts.put(id.toString(), start);
        }
        structures.put("starts", starts);
        root.put("structures", structures);
        return root;
    }

    private static CompoundTag piece(int[] bounds) {
        CompoundTag piece = new CompoundTag();
        piece.putIntArray("BB", bounds);
        return piece;
    }

    private static Path writeChunk(
            Path regionDirectory,
            int chunkX,
            int chunkZ,
            int compressionId,
            boolean external,
            CompoundTag tag) throws IOException {
        Files.createDirectories(regionDirectory);
        byte[] nbt = nbtBytes(tag);
        RegionFileVersion version = RegionFileVersion.fromId(compressionId);
        assertNotNull(version);
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (OutputStream output = version.wrap(compressedBytes)) {
            output.write(nbt);
        }
        byte[] compressed = compressedBytes.toByteArray();

        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        Path region = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        if (external) {
            Files.write(regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc"), compressed);
        }
        int payloadLength = external ? 0 : compressed.length;
        int sectorCount = external ? 1 : (5 + payloadLength + 4095) / 4096;
        byte[] regionBytes = new byte[8192 + sectorCount * 4096];
        ByteBuffer buffer = ByteBuffer.wrap(regionBytes);
        int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
        buffer.putInt(headerIndex, (2 << 8) | sectorCount);
        buffer.position(8192);
        buffer.putInt(payloadLength + 1);
        buffer.put((byte) (compressionId | (external ? 128 : 0)));
        if (!external) {
            buffer.put(compressed);
        }
        Files.write(region, regionBytes);
        return region;
    }

    private static byte[] nbtBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.toByteArray();
    }

    /** Writes a region file whose chunk payload is exactly {@code payload}, no compression wrap. */
    private static void writeRawPayload(
            Path regionDirectory, int chunkX, int chunkZ, byte[] payload, int compressionId)
            throws IOException {
        Files.createDirectories(regionDirectory);
        int sectorCount = (5 + payload.length + 4095) / 4096;
        byte[] regionBytes = new byte[8192 + sectorCount * 4096];
        ByteBuffer buffer = ByteBuffer.wrap(regionBytes);
        int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
        buffer.putInt(headerIndex, (2 << 8) | sectorCount);
        buffer.position(8192);
        buffer.putInt(payload.length + 1);
        buffer.put((byte) compressionId);
        buffer.put(payload);
        Files.write(regionDirectory.resolve("r."
                + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca"), regionBytes);
    }
}
