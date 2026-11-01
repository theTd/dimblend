package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureOutline;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The absent-candidate bookkeeping: which chunks the region scan reports as "nothing saved here", so
 * the predictor knows what to replay. Only genuinely missing data counts as absent — a chunk that
 * exists but cannot be read must stay unshown, because there the file disagrees with the prediction.
 */
class ReadOnlyRegionScannerAbsenceTest {
    private static final int DATA_VERSION = 4000;
    private static final ResourceLocation HOUSE = ResourceLocation.fromNamespaceAndPath("test", "house");

    @Test
    void missingRegionFileReportsEveryCandidateAsAbsent(@TempDir Path tempDir) {
        RegionScanResult result = scan(tempDir.resolve("region"), new StructureCandidate(HOUSE, 5, 9));

        assertTrue(result.outlines().isEmpty());
        assertEquals(0, result.errors().total(), "an unwritten region is not a failure");
        assertEquals(1, result.absentCandidates().size());
        assertEquals(new StructureCandidate(HOUSE, 5, 9), result.absentCandidates().get(0));
    }

    @Test
    void chunkWithoutDataInAnExistingRegionReportsAbsent(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        // Region exists (another chunk lives in it), but this chunk's slot is empty.
        writeChunk(regionDirectory, 1, 1, chunkTag(1, 1, HOUSE));

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertTrue(result.outlines().isEmpty());
        assertEquals(0, result.errors().total());
        assertEquals(1, result.absentCandidates().size());
    }

    @Test
    void savedChunkWithoutThatStartReportsAbsentButKeepsTheOthers(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        writeChunk(regionDirectory, 0, 0, chunkTag(0, 0, HOUSE));

        // HOUSE is present: it is an outline, not an absent candidate.
        RegionScanResult found = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));
        assertEquals(1, found.outlines().size());
        assertTrue(found.absentCandidates().isEmpty());

        // A different structure in the very same chunk was generated and did not appear: absent.
        ResourceLocation absentId = ResourceLocation.fromNamespaceAndPath("test", "other");
        RegionScanResult missing = scan(regionDirectory, new StructureCandidate(absentId, 0, 0));
        assertTrue(missing.outlines().isEmpty());
        assertEquals(0, missing.errors().total());
        assertEquals(1, missing.absentCandidates().size());
        assertEquals(absentId, missing.absentCandidates().get(0).id());
    }

    @Test
    void unreadableChunkIsAFailureAndNeverAbsent(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        // Not NBT: the file disagrees with us, so a prediction here could contradict real data.
        writeRawPayload(regionDirectory, 0, 0, "not-nbt!".getBytes(StandardCharsets.US_ASCII), 3);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertEquals(1, result.errors().parseFailures());
        assertTrue(result.absentCandidates().isEmpty());
    }

    @Test
    void foreignDataVersionIsAFailureAndNeverAbsent(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        CompoundTag older = chunkTag(0, 0, HOUSE);
        older.putInt("DataVersion", DATA_VERSION - 1);
        writeChunk(regionDirectory, 0, 0, older);

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertEquals(1, result.errors().unsupportedDataVersionSkips());
        assertTrue(result.absentCandidates().isEmpty());
    }

    @Test
    void identityMismatchIsAFailureAndNeverAbsent(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        writeChunk(regionDirectory, 0, 0, chunkTag(7, 0, HOUSE));

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, 0, 0));

        assertEquals(1, result.errors().identityFailures());
        assertTrue(result.absentCandidates().isEmpty());
    }

    @Test
    void readsTheStartAndReportsNothingAbsentForThatStructure(@TempDir Path tempDir) throws IOException {
        Path regionDirectory = tempDir.resolve("region");
        writeChunk(regionDirectory, -3, 4, chunkTag(-3, 4, HOUSE));

        RegionScanResult result = scan(regionDirectory, new StructureCandidate(HOUSE, -3, 4));

        assertEquals(0, result.errors().total());
        assertEquals(1, result.outlines().size());
        StructureOutline outline = result.outlines().get(0);
        assertEquals(HOUSE, outline.id());
        assertEquals(false, outline.predicted(), "a saved start is never marked predicted");
        assertEquals(new OutlineBox(-10, 0, -20, 10, 20, 30), outline.pieces().get(0));
    }

    private static RegionScanResult scan(Path regionDirectory, StructureCandidate... candidates) {
        return ReadOnlyRegionScanner.scan(
                new RegionReadContext(
                        Level.OVERWORLD,
                        DATA_VERSION,
                        regionDirectory,
                        dimblend.spyglass.test.ChunkNbtFixture.MIN_BUILD_HEIGHT,
                        dimblend.spyglass.test.ChunkNbtFixture.LEVEL_HEIGHT),
                List.of(candidates));
    }

    private static CompoundTag chunkTag(int chunkX, int chunkZ, ResourceLocation... ids) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);
        root.putInt("xPos", chunkX);
        root.putInt("zPos", chunkZ);
        CompoundTag structures = new CompoundTag();
        CompoundTag starts = new CompoundTag();
        for (ResourceLocation id : ids) {
            CompoundTag start = new CompoundTag();
            start.putString("id", id.toString());
            ListTag children = new ListTag();
            CompoundTag piece = new CompoundTag();
            piece.putIntArray("BB", new int[] {-10, 0, -20, 10, 20, 30});
            children.add(piece);
            start.put("Children", children);
            starts.put(id.toString(), start);
        }
        structures.put("starts", starts);
        root.put("structures", structures);
        return root;
    }

    private static void writeChunk(Path regionDirectory, int chunkX, int chunkZ, CompoundTag tag)
            throws IOException {
        Files.createDirectories(regionDirectory);
        byte[] nbt;
        ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(nbtBytes)) {
            NbtIo.write(tag, output);
        }
        nbt = nbtBytes.toByteArray();
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        RegionFileVersion version = RegionFileVersion.fromId(2);
        try (var output = version.wrap(compressedBytes)) {
            output.write(nbt);
        }
        byte[] compressed = compressedBytes.toByteArray();
        int sectorCount = (5 + compressed.length + 4095) / 4096;
        byte[] regionBytes = new byte[8192 + sectorCount * 4096];
        ByteBuffer buffer = ByteBuffer.wrap(regionBytes);
        int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
        buffer.putInt(headerIndex, (2 << 8) | sectorCount);
        buffer.position(8192);
        buffer.putInt(compressed.length + 1);
        buffer.put((byte) 2);
        buffer.put(compressed);
        Files.write(region(regionDirectory, chunkX, chunkZ), regionBytes);
    }

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
        Files.write(region(regionDirectory, chunkX, chunkZ), regionBytes);
    }

    private static Path region(Path regionDirectory, int chunkX, int chunkZ) {
        return regionDirectory.resolve("r."
                + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca");
    }
}