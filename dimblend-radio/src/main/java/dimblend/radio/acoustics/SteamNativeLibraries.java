package dimblend.radio.acoustics;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts the bundled Steam Audio DLLs once per process. An existing copy is used only if it is
 * byte-identical to the bundled one, so a truncated or stale file is repaired instead of failing
 * every launch; a new copy is written beside it and moved into place atomically, so concurrent
 * game instances never load a partially written library.
 */
final class SteamNativeLibraries {
    private static final String RESOURCES = "/native/steamaudio/windows-x64/";
    private static final Map<String, Path> EXTRACTED = new HashMap<>();

    static synchronized Path library(String name) {
        Path path = EXTRACTED.get(name);
        if (path == null) {
            try {
                path = extract(name);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot extract Steam Audio " + name, error);
            }
            EXTRACTED.put(name, path);
        }
        return path;
    }

    private static Path extract(String name) throws IOException {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "dimblend-steamaudio-4.8.1");
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        if (Files.isRegularFile(target) && matches(name, target)) {
            return target;
        }
        Path partial = Files.createTempFile(directory, name, ".part");
        try {
            try (InputStream input = open(name)) {
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replaceFailed) {
                // Another instance won the race, or holds the library loaded (Windows cannot replace it).
                if (!Files.isRegularFile(target) || !matches(name, target)) {
                    throw replaceFailed;
                }
            }
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    private static InputStream open(String name) throws IOException {
        InputStream input = SteamNativeLibraries.class.getResourceAsStream(RESOURCES + name);
        if (input == null) {
            throw new IOException("Missing bundled " + name);
        }
        return input;
    }

    private static boolean matches(String name, Path file) throws IOException {
        byte[] expected = new byte[1 << 16], actual = new byte[1 << 16];
        try (InputStream bundled = new BufferedInputStream(open(name), expected.length);
                InputStream extracted = new BufferedInputStream(Files.newInputStream(file), actual.length)) {
            while (true) {
                int length = bundled.readNBytes(expected, 0, expected.length);
                int read = extracted.readNBytes(actual, 0, actual.length);
                if (length != read || !Arrays.equals(expected, 0, length, actual, 0, read)) {
                    return false;
                }
                if (length < expected.length) {
                    return true;
                }
            }
        }
    }

    private SteamNativeLibraries() { }
}
