package dimblend.spyglass.test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Writes read-only region files for the scanner/shape tests, including the second header table
 * (chunk modification timestamps) that the shape revision is derived from.
 *
 * <p>Format mirrors vanilla {@code RegionFile}: 8 KiB header, first 4 KiB is one 4-byte
 * {@code (sector << 8) | sectorCount} location per chunk, second 4 KiB one 4-byte epoch-second
 * timestamp per chunk, both indexed by {@code (x & 31) + (z & 31) * 32}; sectors start at 2 and each
 * chunk is {@code int length, byte compression, payload} padded to 4 KiB. Writing a chunk rewrites
 * its whole region file, so tests can add chunks in any order.
 */
public final class RegionFileFixture {
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int COMPRESSION_ID = 2;

    private final Path regionDirectory;
    private final Map<Long, StoredChunk> chunks = new HashMap<>();

    public RegionFileFixture(Path regionDirectory) {
        this.regionDirectory = regionDirectory;
    }

    public Path regionDirectory() {
        return this.regionDirectory;
    }

    public void writeChunk(int chunkX, int chunkZ, CompoundTag tag) throws IOException {
        writeChunk(chunkX, chunkZ, tag, 0);
    }

    public void writeChunk(int chunkX, int chunkZ, CompoundTag tag, int timestamp) throws IOException {
        byte[] payload = compress(tag);
        this.chunks.put(chunkKey(chunkX, chunkZ), new StoredChunk(chunkX, chunkZ, payload, timestamp));
        writeRegion(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
    }

    private void writeRegion(int regionX, int regionZ) throws IOException {
        List<StoredChunk> regionChunks = new ArrayList<>();
        for (StoredChunk chunk : this.chunks.values()) {
            if (Math.floorDiv(chunk.chunkX, 32) == regionX && Math.floorDiv(chunk.chunkZ, 32) == regionZ) {
                regionChunks.add(chunk);
            }
        }
        regionChunks.sort(Comparator.comparingLong(chunk -> chunkKey(chunk.chunkX, chunk.chunkZ)));

        int totalSectors = 0;
        for (StoredChunk chunk : regionChunks) {
            chunk.sector = 2 + totalSectors;
            chunk.sectorCount = (CHUNK_HEADER_BYTES + chunk.payload.length + SECTOR_BYTES - 1) / SECTOR_BYTES;
            totalSectors += chunk.sectorCount;
        }
        byte[] bytes = new byte[HEADER_BYTES + totalSectors * SECTOR_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (StoredChunk chunk : regionChunks) {
            int index = (chunk.chunkX & 31) + (chunk.chunkZ & 31) * 32;
            buffer.putInt(index * 4, (chunk.sector << 8) | chunk.sectorCount);
            buffer.putInt(SECTOR_BYTES + index * 4, chunk.timestamp);
            int position = chunk.sector * SECTOR_BYTES;
            buffer.putInt(position, chunk.payload.length + 1);
            buffer.put(position + 4, (byte) COMPRESSION_ID);
            buffer.position(position + CHUNK_HEADER_BYTES);
            buffer.put(chunk.payload);
        }
        Files.createDirectories(this.regionDirectory);
        Files.write(
                this.regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca"),
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static byte[] compress(CompoundTag tag) throws IOException {
        ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(nbtBytes)) {
            NbtIo.write(tag, output);
        }
        RegionFileVersion version = RegionFileVersion.fromId(COMPRESSION_ID);
        if (version == null) {
            throw new IllegalStateException("Missing zlib region version");
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = version.wrap(compressed)) {
            output.write(nbtBytes.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static final class StoredChunk {
        private final int chunkX;
        private final int chunkZ;
        private final byte[] payload;
        private final int timestamp;
        private int sector;
        private int sectorCount;

        private StoredChunk(int chunkX, int chunkZ, byte[] payload, int timestamp) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.payload = payload;
            this.timestamp = timestamp;
        }
    }
}
