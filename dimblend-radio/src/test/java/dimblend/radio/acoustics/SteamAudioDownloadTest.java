package dimblend.radio.acoustics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Offline: hashing, zip extraction and install, plus the offline-mode short-circuit. */
class SteamAudioDownloadTest {
    @TempDir Path temp;

    @Test void sha256MatchesTheStandardVector() throws Exception {
        Path file = temp.resolve("abc.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SteamAudioDownload.sha256Hex(file));
    }

    @Test void extractEntryVerifiesNameSizeAndHash() throws Exception {
        byte[] content = "fake phonon".getBytes(StandardCharsets.US_ASCII);
        Path zip = temp.resolve("sdk.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("other.txt"));
            out.write(content);
            out.closeEntry();
            out.putNextEntry(new ZipEntry(SteamAudioDownload.ENTRY));
            out.write(content);
            out.closeEntry();
        }
        String sha = SteamAudioDownload.sha256Hex(writeTemp(content));
        assertArrayEquals(content,
                SteamAudioDownload.extractEntry(zip, SteamAudioDownload.ENTRY, content.length, sha));
        assertThrows(IOException.class, () ->
                SteamAudioDownload.extractEntry(zip, "missing.dll", content.length, sha));
        assertThrows(IOException.class, () ->
                SteamAudioDownload.extractEntry(zip, SteamAudioDownload.ENTRY, content.length + 1, sha));
        assertThrows(IOException.class, () ->
                SteamAudioDownload.extractEntry(zip, SteamAudioDownload.ENTRY, content.length,
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }

    @Test void installImageWritesAtomicallyAndKeepsIdenticalBytes() throws Exception {
        Path target = temp.resolve("phonon.dll");
        byte[] image = "installed".getBytes(StandardCharsets.US_ASCII);
        SteamAudioDownload.installImage(image, target);
        assertArrayEquals(image, Files.readAllBytes(target));
        // A byte-identical install is a no-op, never a truncated rewrite.
        SteamAudioDownload.installImage(image, target);
        assertArrayEquals(image, Files.readAllBytes(target));
    }

    @Test void offlineModeFailsFastWithoutNetwork() throws Exception {
        String previous = System.getProperty("dimblend.radio.steamaudio.offline");
        System.setProperty("dimblend.radio.steamaudio.offline", "true");
        try {
            SteamAudioDownload.downloadOnce();
            assertEquals(SteamAudioDownload.Status.FAILED, SteamAudioDownload.status());
            assertTrue(SteamAudioDownload.failure().contains("offline"),
                    "failure names the cause: " + SteamAudioDownload.failure());
        } finally {
            if (previous == null) System.clearProperty("dimblend.radio.steamaudio.offline");
            else System.setProperty("dimblend.radio.steamaudio.offline", previous);
        }
    }

    private Path writeTemp(byte[] content) throws IOException {
        Path file = Files.createTempFile(temp, "content", ".bin");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(content);
        }
        return file;
    }
}
