package dimblend.spyglass.server;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Read-only view of one region file, shared by the structure-start scan and the shape extraction.
 *
 * <p>Every file is opened with {@link StandardOpenOption#READ} only: no directory or file is ever
 * created and the vanilla {@code RegionFile} is not used (it opens read-write, may repair the header
 * and keeps the file locked). The 8 KiB header is snapshotted at open time; sectors are read later.
 *
 * <p>Header layout (matches vanilla {@code RegionFile}): the first 4 KiB table holds one 4-byte
 * location per chunk, the second 4 KiB table one 4-byte modification timestamp per chunk
 * ({@code RegionFile.java:60-63}). Both tables are indexed by
 * {@code (chunkX & 31) + (chunkZ & 31) * 32} ({@code RegionFile.java:352-354}); a timestamp is
 * written on every chunk save in epoch seconds ({@code RegionFile.java:156-158,312}). The header is
 * snapshotted once, so a timestamp and the chunk payload read from the same reader belong to the
 * same observed file state.
 *
 * <p>A concurrent server write can still free and reuse a sector between the header snapshot and the
 * sector read. Callers therefore verify the decoded chunk's own {@code xPos}/{@code zPos}.
 */
final class ReadOnlyRegionFile implements AutoCloseable {
    static final int TIMESTAMP_TABLE_OFFSET = 4096;
    private static final int SECTOR_BYTES = 4096;
    private static final int REGION_HEADER_BYTES = 2 * SECTOR_BYTES;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int EXTERNAL_STREAM_FLAG = 128;

    private final Path regionPath;
    private final FileChannel channel;
    private final ByteBuffer header;

    private ReadOnlyRegionFile(Path regionPath, FileChannel channel, ByteBuffer header) {
        this.regionPath = regionPath;
        this.channel = channel;
        this.header = header;
    }

    @Nullable
    static ReadOnlyRegionFile open(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        boolean opened = false;
        try {
            if (channel.size() < REGION_HEADER_BYTES) {
                return null;
            }
            ByteBuffer header = ByteBuffer.allocate(REGION_HEADER_BYTES);
            readFullyAt(channel, header, 0L);
            header.flip();
            ReadOnlyRegionFile region = new ReadOnlyRegionFile(path, channel, header);
            opened = true;
            return region;
        } finally {
            if (!opened) {
                channel.close();
            }
        }
    }

    /** True when the snapshotted header has a sector for this chunk. */
    boolean hasChunk(int chunkX, int chunkZ) {
        return offsetEntry(chunkX, chunkZ) != 0;
    }

    /** Saved modification time of this chunk in epoch seconds; 0 when the chunk was never saved. */
    int timestamp(int chunkX, int chunkZ) {
        return this.header.getInt(TIMESTAMP_TABLE_OFFSET + headerIndex(chunkX, chunkZ) * 4);
    }

    /**
     * Opens the chunk payload, or {@code null} when the header has no entry for this chunk.
     *
     * @throws IOException if the entry is invalid, the stream is unsupported, or the payload lies
     *                     outside the file
     */
    @Nullable
    InputStream openChunkData(int chunkX, int chunkZ) throws IOException {
        int offset = offsetEntry(chunkX, chunkZ);
        if (offset == 0) {
            return null;
        }
        int sector = (offset >> 8) & 0xFFFFFF;
        int sectorCount = offset & 0xFF;
        if (sector < 2 || sectorCount == 0) {
            throw new IOException("Invalid region header entry for chunk " + chunkX + "," + chunkZ
                    + ": sector " + sector + ", count " + sectorCount);
        }
        long sectorOffset = (long) sector * SECTOR_BYTES;
        long allocatedBytes = (long) sectorCount * SECTOR_BYTES;
        if (sectorOffset + CHUNK_HEADER_BYTES > this.channel.size()) {
            throw new IOException("Chunk " + chunkX + "," + chunkZ + " points past the end of the region file");
        }

        ByteBuffer chunkHeader = ByteBuffer.allocate(CHUNK_HEADER_BYTES);
        readFullyAt(this.channel, chunkHeader, sectorOffset);
        chunkHeader.flip();
        int declaredLength = chunkHeader.getInt();
        int encodedCompression = chunkHeader.get() & 0xFF;
        boolean external = (encodedCompression & EXTERNAL_STREAM_FLAG) != 0;
        int compressionId = external
                ? encodedCompression & ~EXTERNAL_STREAM_FLAG
                : encodedCompression;
        if (declaredLength <= 0) {
            throw new IOException("Chunk " + chunkX + "," + chunkZ + " has declared length " + declaredLength);
        }
        // 1..4 are the stream versions vanilla can decode; 127 (custom) and anything else cannot.
        RegionFileVersion version = compressionId >= 1 && compressionId <= 4
                ? RegionFileVersion.fromId(compressionId)
                : null;
        if (version == null) {
            throw new IOException("Chunk " + chunkX + "," + chunkZ
                    + " uses unsupported compression id " + compressionId);
        }
        if (external) {
            if (declaredLength != 1) {
                throw new IOException("External chunk " + chunkX + "," + chunkZ
                        + " has an inline payload of length " + declaredLength);
            }
            return openExternalChunk(version, chunkX, chunkZ);
        }

        int payloadLength = declaredLength - 1;
        long dataOffset = sectorOffset + CHUNK_HEADER_BYTES;
        if (payloadLength <= 0
                || payloadLength > allocatedBytes - CHUNK_HEADER_BYTES
                || dataOffset + payloadLength > this.channel.size()) {
            throw new IOException("Chunk " + chunkX + "," + chunkZ + " has payload length " + payloadLength
                    + " outside its " + allocatedBytes + "-byte allocation");
        }
        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        readFullyAt(this.channel, payload, dataOffset);
        payload.flip();
        byte[] bytes = new byte[payloadLength];
        payload.get(bytes);
        return version.wrap(new ByteArrayInputStream(bytes));
    }

    private InputStream openExternalChunk(
            RegionFileVersion version, int chunkX, int chunkZ) throws IOException {
        Path externalPath = this.regionPath.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
        if (!Files.isRegularFile(externalPath)) {
            throw new IOException("External chunk file " + externalPath.getFileName() + " is missing");
        }
        FileChannel external = FileChannel.open(externalPath, StandardOpenOption.READ);
        boolean opened = false;
        try {
            InputStream stream = version.wrap(Channels.newInputStream(external));
            opened = true;
            return stream;
        } finally {
            if (!opened) {
                external.close();
            }
        }
    }

    private int offsetEntry(int chunkX, int chunkZ) {
        return this.header.getInt(headerIndex(chunkX, chunkZ) * 4);
    }

    private static int headerIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    private static void readFullyAt(FileChannel channel, ByteBuffer target, long position) throws IOException {
        long offset = position;
        while (target.hasRemaining()) {
            int read = channel.read(target, offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of region data");
            }
            offset += read;
        }
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
