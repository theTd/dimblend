package dimblend.radio.acoustics;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Resolves the Steam Audio native libraries. Both arrive over the network
 * ({@link SteamAudioDownload}), never in the jar: an existing copy is used only if it is
 * byte-identical to what would be written, so a truncated or stale file is repaired instead of
 * failing every launch.
 */
final class SteamNativeLibraries {
    /** Named for the fixed build, so a game still running the unfixed copy does not hold this one. */
    private static final String DIRECTORY = "dimblend-steamaudio-4.8.1-histogram-fix";

    static Path cacheDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir"), DIRECTORY);
    }

    static Path cacheFile(String name) {
        return cacheDirectory().resolve(name);
    }

    static synchronized Path library(String name) {
        return switch (name) {
            case "phonon.dll" -> SteamAudioDownload.phononLibrary();
            case "GPUUtilities.dll" -> SteamAudioDownload.gpuLibrary();
            default -> throw new IllegalArgumentException("Unknown Steam Audio library: " + name);
        };
    }

    /** The library as installed: phonon.dll with {@link RadeonRaysHistogramFix} applied, GPUUtilities.dll verbatim. */
    static byte[] image(String name) throws IOException {
        return switch (name) {
            case "phonon.dll" -> SteamAudioDownload.phononImage();
            case "GPUUtilities.dll" -> SteamAudioDownload.gpuImage();
            default -> throw new IllegalArgumentException("Unknown Steam Audio library: " + name);
        };
    }

    static boolean matches(byte[] image, Path file) throws IOException {
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
