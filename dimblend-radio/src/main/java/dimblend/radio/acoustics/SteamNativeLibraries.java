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
 * Extracts the bundled Steam Audio DLLs once per process, phonon.dll with its GPU reflection kernel
 * fixed ({@link RadeonRaysHistogramFix}). An existing copy is used only if it is byte-identical to
 * what would be written, so a truncated or stale file is repaired instead of failing every launch;
 * a new copy is written beside it and moved into place atomically, so concurrent game instances
 * never load a partially written library.
 */
final class SteamNativeLibraries {
    private static final String RESOURCES = "/native/steamaudio/windows-x64/";
    /** Named for the fixed build, so a game still running the unfixed copy does not hold this one. */
    private static final String DIRECTORY = "dimblend-steamaudio-4.8.1-histogram-fix";
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
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), DIRECTORY);
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        byte[] image = image(name);
        if (Files.isRegularFile(target) && matches(image, target)) {
            return target;
        }
        Path partial = Files.createTempFile(directory, name, ".part");
        try {
            Files.write(partial, image);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replaceFailed) {
                // Another instance won the race, or holds the library loaded (Windows cannot replace it).
                if (!Files.isRegularFile(target) || !matches(image, target)) {
                    throw replaceFailed;
                }
            }
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    /** The library as installed: the bundled bytes, phonon.dll with {@link RadeonRaysHistogramFix} applied. */
    static byte[] image(String name) throws IOException {
        byte[] bundled;
        try (InputStream input = SteamNativeLibraries.class.getResourceAsStream(RESOURCES + name)) {
            if (input == null) {
                throw new IOException("Missing bundled " + name);
            }
            bundled = input.readAllBytes();
        }
        return name.equals("phonon.dll") ? RadeonRaysHistogramFix.apply(bundled) : bundled;
    }

    private static boolean matches(byte[] image, Path file) throws IOException {
        if (Files.size(file) != image.length) {
            return false;
        }
        byte[] actual = new byte[1 << 16];
        try (InputStream extracted = new BufferedInputStream(Files.newInputStream(file), actual.length)) {
            for (int offset = 0; offset < image.length; ) {
                int read = extracted.readNBytes(actual, 0, Math.min(actual.length, image.length - offset));
                if (read <= 0 || !Arrays.equals(image, offset, offset + read, actual, 0, read)) {
                    return false;
                }
                offset += read;
            }
            return true;
        }
    }

    private SteamNativeLibraries() { }
}
