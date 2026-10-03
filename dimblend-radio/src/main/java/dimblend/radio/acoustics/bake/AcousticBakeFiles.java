package dimblend.radio.acoustics.bake;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import net.minecraft.core.BlockPos;

/**
 * Baked acoustics on disk: {@code <root>/<world>/<dimension>/<x>_<y>_<z>.pathing}. A file holds a
 * magic number, the format, the bake settings key, the radio, the column cell size, the probe
 * centres, every section the bake spans with its state, the serialized probe batch and a CRC32C of
 * all of that. A file in another format reads as none and is replaced by the next bake. Files are
 * written to a temporary file and moved into place; the least recently used beyond the disk budget
 * are deleted.
 */
public final class AcousticBakeFiles {
    private static final int MAGIC = 0x44425041;
    /** 2: probe centres stored. */
    static final int FORMAT = 2;
    private static final String PATHING = ".pathing";
    private final Path root;

    public AcousticBakeFiles(Path root) { this.root = root; }

    public Path root() { return root; }

    /** A file or directory name made of {@code [A-Za-z0-9._-]}, distinct for distinct inputs. */
    public static String sanitize(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            safe.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' ? c : '_');
        }
        // No hidden names, and nothing that reads as "." or "..".
        if (!safe.isEmpty() && safe.charAt(0) == '.') safe.setCharAt(0, '_');
        String result = safe.length() > 48 ? safe.substring(0, 48) : safe.toString();
        if (result.isEmpty() || !result.equals(name)) {
            result += "-" + String.format("%08x", name.hashCode());
        }
        return result;
    }

    public Path pathingFile(String world, String dimension, BlockPos radio) {
        return root.resolve(sanitize(world)).resolve(sanitize(dimension))
                .resolve(radio.getX() + "_" + radio.getY() + "_" + radio.getZ() + PATHING);
    }

    public void write(Path file, PathingBake bake, int settings) throws IOException {
        int size = 9 * Integer.BYTES + bake.probes().length * Float.BYTES + bake.sectionKeys().length * 2 * Long.BYTES
                + Integer.BYTES + bake.batch().length;
        ByteBuffer buffer = ByteBuffer.allocate(size + Long.BYTES);
        buffer.putInt(MAGIC).putInt(FORMAT).putInt(settings);
        buffer.putInt(bake.radio().getX()).putInt(bake.radio().getY()).putInt(bake.radio().getZ());
        buffer.putInt(bake.cellSize()).putInt(bake.probeCount());
        for (float coordinate : bake.probes()) buffer.putFloat(coordinate);
        buffer.putInt(bake.sectionKeys().length);
        for (long key : bake.sectionKeys()) buffer.putLong(key);
        for (long state : bake.sectionStates()) buffer.putLong(state);
        buffer.putInt(bake.batch().length).put(bake.batch());
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, size);
        buffer.putLong(crc.getValue());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, buffer.array());
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * @return the bake, or null when there is none for this radio and these settings; a damaged
     *     file is deleted
     */
    public PathingBake read(Path file, BlockPos radio, int settings) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException missing) {
            return null;
        }
        PathingBake bake = parse(bytes, radio, settings);
        if (bake == null) {
            Files.deleteIfExists(file);
            return null;
        }
        // Least recently used means least recently read or written.
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        return bake;
    }

    private static PathingBake parse(byte[] bytes, BlockPos radio, int settings) {
        if (bytes.length < 10 * Integer.BYTES + Long.BYTES) return null;
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - Long.BYTES);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getLong(bytes.length - Long.BYTES) != crc.getValue()) return null;
        try {
            if (buffer.getInt() != MAGIC || buffer.getInt() != FORMAT || buffer.getInt() != settings) return null;
            BlockPos stored = new BlockPos(buffer.getInt(), buffer.getInt(), buffer.getInt());
            if (!stored.equals(radio)) return null;
            int cellSize = buffer.getInt(), probeCount = buffer.getInt();
            if (probeCount < 0 || (long) probeCount * 3 * Float.BYTES > buffer.remaining()) return null;
            float[] probes = new float[probeCount * 3];
            for (int i = 0; i < probes.length; i++) probes[i] = buffer.getFloat();
            int sections = buffer.getInt();
            if (sections < 0 || (long) sections * 2 * Long.BYTES > buffer.remaining()) return null;
            long[] keys = new long[sections], states = new long[sections];
            for (int i = 0; i < sections; i++) keys[i] = buffer.getLong();
            for (int i = 0; i < sections; i++) states[i] = buffer.getLong();
            int length = buffer.getInt();
            if (length <= 0 || length != buffer.remaining() - Long.BYTES) return null;
            byte[] batch = new byte[length];
            buffer.get(batch);
            return new PathingBake(radio, keys, states, probes, cellSize, batch);
        } catch (BufferUnderflowException damaged) {
            return null;
        }
    }

    /** Deletes the least recently used bakes until the rest fit in {@code budget} bytes. */
    public void trim(long budget) throws IOException {
        if (!Files.isDirectory(root)) return;
        record Entry(Path path, long size, long used) { }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                try {
                    entries.add(new Entry(path, Files.size(path), Files.getLastModifiedTime(path).toMillis()));
                } catch (NoSuchFileException gone) { }
            }
        }
        long total = entries.stream().mapToLong(Entry::size).sum();
        entries.sort(Comparator.comparingLong(Entry::used));
        for (Entry entry : entries) {
            if (total <= budget) break;
            Files.deleteIfExists(entry.path);
            total -= entry.size;
        }
    }
}
