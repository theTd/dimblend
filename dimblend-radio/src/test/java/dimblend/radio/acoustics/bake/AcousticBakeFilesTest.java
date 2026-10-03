package dimblend.radio.acoustics.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AcousticBakeFilesTest {
    private static final BlockPos RADIO = new BlockPos(-12, 64, 300);

    private static PathingBake bake(int batchSize) {
        byte[] batch = new byte[batchSize];
        for (int i = 0; i < batch.length; i++) batch[i] = (byte) (i * 31);
        return new PathingBake(RADIO, new long[] {1, 2, 3}, new long[] {77, -3, -2}, new float[] {0.5f, 1.5f, -3, 8, 1.5f, 12.25f},
                6, batch);
    }

    @Test
    void aBakeComesBackAsWritten(@TempDir Path root) throws IOException {
        var files = new AcousticBakeFiles(root);
        Path file = files.pathingFile("local-My World", "minecraft:overworld", RADIO);
        assertTrue(file.startsWith(root));
        assertEquals("-12_64_300.pathing", file.getFileName().toString());
        files.write(file, bake(5000), 9);
        var read = files.read(file, RADIO, 9);
        assertNotNull(read);
        assertEquals(RADIO, read.radio());
        assertArrayEquals(new long[] {1, 2, 3}, read.sectionKeys());
        assertArrayEquals(new long[] {77, -3, -2}, read.sectionStates());
        assertEquals(2, read.probeCount());
        assertArrayEquals(new float[] {0.5f, 1.5f, -3, 8, 1.5f, 12.25f}, read.probes(), "probe centres for the bake view");
        assertEquals(6, read.cellSize());
        assertArrayEquals(bake(5000).batch(), read.batch());
        try (var leftovers = Files.list(file.getParent())) {
            assertEquals(1, leftovers.count(), "no temporary file left behind");
        }
    }

    @Test
    void otherSettingsOrAnotherRadioReadAsNothing(@TempDir Path root) throws IOException {
        var files = new AcousticBakeFiles(root);
        Path file = files.pathingFile("w", "d", RADIO);
        files.write(file, bake(100), 9);
        assertNull(files.read(file, RADIO, 10), "baked with other settings");
        assertNull(files.read(files.pathingFile("w", "d", RADIO.above()), RADIO.above(), 9), "no file");
    }

    @Test
    void aDamagedFileIsDropped(@TempDir Path root) throws IOException {
        var files = new AcousticBakeFiles(root);
        Path file = files.pathingFile("w", "d", RADIO);
        files.write(file, bake(1000), 9);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);
        assertNull(files.read(file, RADIO, 9));
        assertFalse(Files.exists(file), "deleted, so it is baked again");
        Files.write(file, new byte[] {1, 2, 3});
        assertNull(files.read(file, RADIO, 9), "truncated");
    }

    @Test
    void namesAreSafeAndDistinct() {
        assertEquals("plain-name_1.0", AcousticBakeFiles.sanitize("plain-name_1.0"));
        String colon = AcousticBakeFiles.sanitize("minecraft:overworld");
        assertTrue(colon.matches("[A-Za-z0-9._-]+"), colon);
        assertNotEquals(colon, AcousticBakeFiles.sanitize("minecraft_overworld"));
        assertNotEquals(AcousticBakeFiles.sanitize("server-a/b"), AcousticBakeFiles.sanitize("server-a?b"));
        assertFalse(AcousticBakeFiles.sanitize("..").startsWith(".."));
        assertTrue(AcousticBakeFiles.sanitize("x".repeat(300)).length() < 64);
    }

    @Test
    void trimmingDeletesTheLeastRecentlyUsedFirst(@TempDir Path root) throws IOException {
        var files = new AcousticBakeFiles(root);
        Path old = files.pathingFile("w", "d", RADIO);
        Path recent = files.pathingFile("w", "d", RADIO.east());
        Path middle = files.pathingFile("w", "e", RADIO.west());
        files.write(old, bake(1000), 9);
        files.write(recent, new PathingBake(RADIO.east(), new long[0], new long[0], new float[3], 4, new byte[1000]), 9);
        files.write(middle, new PathingBake(RADIO.west(), new long[0], new long[0], new float[3], 4, new byte[1000]), 9);
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(middle, FileTime.fromMillis(2_000_000));
        Files.setLastModifiedTime(recent, FileTime.fromMillis(3_000_000));
        long each = Files.size(recent);
        files.trim(2 * each + 10);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(middle) && Files.exists(recent));
        // Reading counts as use.
        assertNotNull(files.read(middle, RADIO.west(), 9));
        files.trim(each + 10);
        assertTrue(Files.exists(middle));
        assertFalse(Files.exists(recent));
    }
}
