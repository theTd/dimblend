package dimblend.radio.acoustics;

import dimblend.radio.DimBlendRadio;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/**
 * Fetches {@code phonon.dll} at runtime instead of shipping it in the jar (53 MB). The first radio
 * that needs Steam Audio triggers one daemon-thread download of the official SDK zip; until it
 * lands, callers get {@link PhononNotReadyException} and play stereo-panned sound.
 * <p>
 * Trust: the zip's size and SHA-256 are pinned, and the extracted entry's size and SHA-256 are
 * pinned again, so a compromised mirror cannot smuggle in another binary. The zip entry is looked
 * up by exact name and never used as an output path, so there is no zip-slip. A byte-identical
 * install is reused; concurrent game instances share it through the atomic-move protocol already
 * used for the small bundled libraries.
 */
public final class SteamAudioDownload {
    /** Official SDK release; only {@link #ENTRY} and {@link #GPU_ENTRY} are used. */
    static final String SDK_URL =
            "https://github.com/ValveSoftware/steam-audio/releases/download/v4.8.1/steamaudio_4.8.1.zip";
    static final String SDK_SHA256 = "4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449";
    static final long SDK_SIZE = 181171027L;
    static final String ENTRY = "steamaudio/lib/windows-x64/phonon.dll";
    static final String PHONON_SHA256 = "ca3dbc01dbc24492717011e80f6a51404ca143ae344ca660971d2c983f1e058d";
    /** The same bytes with {@link RadeonRaysHistogramFix} applied (same length); accepted as-is. */
    static final String PHONON_FIXED_SHA256 = "7ac89a4487112701334c49e34b366c8a1f8d682fd0be2fd91922f9820f0275f2";
    static final long PHONON_SIZE = 52950168L;
    static final String GPU_ENTRY = "steamaudio/lib/windows-x64/GPUUtilities.dll";
    static final String GPU_SHA256 = "956acfec2e9b53fb17f161b91a1e4d5172276ce3c45446e81568d40055a0f317";
    static final long GPU_SIZE = 44032L;

    enum Status { IDLE, DOWNLOADING, READY, FAILED }

    /** Chat-level progress; empty in tests and on servers. All calls arrive off-thread. */
    public interface Listener {
        void started(long totalBytes);
        void progress(double fraction);
        void done(long millis);
        void failed(String reason);
    }

    private static Status status = Status.IDLE;
    private static String failure;
    private static volatile Path installedPhonon, installedGpu;
    private static volatile Listener listener;

    /** Registers the chat notice; last registration wins. */
    public static void listen(Listener notices) {
        listener = notices;
    }

    /** Local file override (dev/tests): used verbatim, never downloaded. */
    static Path override() {
        String property = System.getProperty("dimblend.radio.phononDll", "").trim();
        return property.isEmpty() ? null : Path.of(property);
    }
    /** Local file override (dev/tests): used verbatim, never downloaded. */
    static Path gpuOverride() {
        String property = System.getProperty("dimblend.radio.gpuUtilitiesDll", "").trim();
        return property.isEmpty() ? null : Path.of(property);
    }

    static synchronized Status status() {
        return status;
    }

    static synchronized String failure() {
        return failure;
    }

    /**
     * Starts the background download once. Cheap to call from every thread: only the first call
     * while idle (or after a failure, for the next retry) spawns the worker.
     */
    static synchronized void prefetch() {
        if (status == Status.DOWNLOADING) return;
        if (status == Status.READY && haveBoth()) return;
        status = Status.DOWNLOADING;
        failure = null;
        Thread worker = new Thread(SteamAudioDownload::downloadOnce, "dimblend-steamaudio-download");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The installed {@code phonon.dll}, kicking off the background download when it is missing.
     *
     * @throws PhononNotReadyException while downloading, after a failure, or in offline mode
     */
    static synchronized Path phononLibrary() {
        Path local = override();
        if (local != null) {
            if (Files.isRegularFile(local)) {
                status = Status.READY;
                installedPhonon = local;
                return local;
            }
            throw new PhononNotReadyException(
                    "dimblend.radio.phononDll points at a missing file: " + local);
        }
        Path ready = installedPhonon;
        if (status == Status.READY && ready != null && Files.isRegularFile(ready)) return ready;
        Path cached = SteamNativeLibraries.cacheFile("phonon.dll");
        if (Files.isRegularFile(cached)) {
            try {
                verify(cached, PHONON_SIZE, PHONON_SHA256, PHONON_FIXED_SHA256);
                status = Status.READY;
                installedPhonon = cached;
                return cached;
            } catch (IOException bad) {
                DimBlendRadio.LOGGER.warn("[radio] cached phonon.dll failed verification, re-downloading", bad);
                deleteQuietly(cached);
            }
        }
        prefetch();
        throw new PhononNotReadyException(describe());
    }

    /**
     * The installed {@code GPUUtilities.dll}, from the same download, verified verbatim.
     *
     * @throws PhononNotReadyException while downloading, after a failure, or in offline mode
     */
    static synchronized Path gpuLibrary() {
        Path local = gpuOverride();
        if (local != null) {
            if (Files.isRegularFile(local)) {
                status = Status.READY;
                installedGpu = local;
                return local;
            }
            throw new PhononNotReadyException(
                    "dimblend.radio.gpuUtilitiesDll points at a missing file: " + local);
        }
        Path ready = installedGpu;
        if (status == Status.READY && ready != null && Files.isRegularFile(ready)) return ready;
        Path cached = SteamNativeLibraries.cacheFile("GPUUtilities.dll");
        if (Files.isRegularFile(cached)) {
            try {
                verify(cached, GPU_SIZE, GPU_SHA256);
                status = Status.READY;
                installedGpu = cached;
                return cached;
            } catch (IOException bad) {
                DimBlendRadio.LOGGER.warn("[radio] cached GPUUtilities.dll failed verification, re-downloading", bad);
                deleteQuietly(cached);
            }
        }
        prefetch();
        throw new PhononNotReadyException(describe());
    }

    private static boolean haveBoth() {
        return installedPhonon != null && Files.isRegularFile(installedPhonon)
                && installedGpu != null && Files.isRegularFile(installedGpu);
    }

    /** The installed bytes with {@link RadeonRaysHistogramFix} applied. */
    static byte[] phononImage() throws IOException {
        return RadeonRaysHistogramFix.apply(Files.readAllBytes(phononLibrary()));
    }

    /** The installed bytes, verbatim. */
    static byte[] gpuImage() throws IOException {
        return Files.readAllBytes(gpuLibrary());
    }

    private static String describe() {
        return switch (status) {
            case DOWNLOADING -> "Steam Audio natives are downloading in the background"
                    + " (official 4.8.1 SDK); this radio plays stereo-panned until they arrive";
            case FAILED -> "Steam Audio natives download failed: " + failure
                    + "; this radio plays stereo-panned (set dimblend.radio.steamaudio.offline=true to stop retrying)";
            default -> "Steam Audio natives are not downloaded yet"
                    + "; this radio plays stereo-panned until the background download finishes";
        };
    }

    /** One download attempt on the worker thread; tests call it directly. */
    static void downloadOnce() {
        long started = System.nanoTime();
        DimBlendRadio.LOGGER.info(
                "[radio] downloading Steam Audio 4.8.1 SDK (~173 MiB) to extract the natives;"
                        + " radios play stereo-panned meanwhile");
        try {
            if (Boolean.getBoolean("dimblend.radio.steamaudio.offline")) {
                throw new IOException("dimblend.radio.steamaudio.offline=true, download skipped");
            }
            Path directory = SteamNativeLibraries.cacheDirectory();
            Files.createDirectories(directory);
            Path part = directory.resolve("steamaudio_4.8.1.zip.part");
            try {
                downloadFile(sdkUrl(), part);
                verify(part, SDK_SIZE, SDK_SHA256);
                byte[] raw = extractEntry(part, ENTRY, PHONON_SIZE, PHONON_SHA256);
                byte[] gpu = extractEntry(part, GPU_ENTRY, GPU_SIZE, GPU_SHA256);
                installImage(RadeonRaysHistogramFix.apply(raw), directory.resolve("phonon.dll"));
                installImage(gpu, directory.resolve("GPUUtilities.dll"));
            } finally {
                deleteQuietly(part);
            }
            synchronized (SteamAudioDownload.class) {
                status = Status.READY;
                installedPhonon = directory.resolve("phonon.dll");
                installedGpu = directory.resolve("GPUUtilities.dll");
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            DimBlendRadio.LOGGER.info("[radio] Steam Audio natives ready in {} ms", millis);
            Listener ready = listener;
            if (ready != null) ready.done(millis);
        } catch (Exception error) {
            synchronized (SteamAudioDownload.class) {
                status = Status.FAILED;
                failure = error.toString();
            }
            DimBlendRadio.LOGGER.warn("[radio] Steam Audio natives download failed;"
                    + " radios stay stereo-panned", error);
            Listener broken = listener;
            if (broken != null) broken.failed(error.toString());
        }
    }

    private static String sdkUrl() {
        String url = System.getProperty("dimblend.radio.steamaudio.url", SDK_URL).trim();
        return url.isEmpty() ? SDK_URL : url;
    }

    private static void downloadFile(String url, Path part) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(15))
                .header("User-Agent", "dimblend-radio")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("SDK download returned HTTP " + response.statusCode() + " for " + url);
        }
        // Streamed (not ofFile) so the chat can report progress; the pinned size/hash still verify after.
        Listener notices = listener;
        if (notices != null) notices.started(SDK_SIZE);
        long total = 0;
        int lastDecile = -1;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = response.body();
                var out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int read; (read = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, read);
                total += read;
                if (notices != null) {
                    int decile = (int) Math.min(10, total * 10 / SDK_SIZE);
                    if (decile >= 1 && decile > lastDecile) {
                        lastDecile = decile;
                        notices.progress(total / (double) SDK_SIZE);
                    }
                }
            }
        }
    }

    /** Size-then-hash check against any accepted digest; streams so the 53 MB library never sits on the heap twice. */
    static void verify(Path file, long expectSize, String... expectSha) throws IOException {
        if (Files.size(file) != expectSize) {
            throw new IOException(file.getFileName() + " size " + Files.size(file)
                    + ", expected " + expectSize);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException impossible) {
            throw new IOException(impossible);
        }
        byte[] buffer = new byte[1 << 20];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        for (String expect : expectSha) {
            if (actual.equalsIgnoreCase(expect)) return;
        }
        throw new IOException(file.getFileName() + " SHA-256 " + actual
                + ", expected " + String.join(" or ", expectSha));
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException impossible) {
            throw new IOException(impossible);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Extracts one entry by exact name. The name is only looked up, never turned into an output
     * path, so a malicious zip cannot escape the cache directory.
     */
    static byte[] extractEntry(Path zip, String entry, long expectSize, String expectSha) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            var found = zipFile.getEntry(entry);
            if (found == null) throw new IOException(zip.getFileName() + " has no " + entry);
            if (found.getSize() != expectSize) {
                throw new IOException(entry + " size " + found.getSize() + ", expected " + expectSize);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            long total = 0;
            var out = new ByteArrayOutputStream(Math.toIntExact(expectSize));
            try (InputStream input = zipFile.getInputStream(found)) {
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    total += read;
                    if (total > expectSize) throw new IOException(entry + " overruns its directory size");
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectSha)) {
                throw new IOException(entry + " SHA-256 " + actual + ", expected " + expectSha);
            }
            return out.toByteArray();
        } catch (GeneralSecurityException impossible) {
            throw new IOException(impossible);
        }
    }

    /** Atomic install shared with the small bundled libraries' protocol: identical bytes win. */
    static void installImage(byte[] image, Path target) throws IOException {
        if (Files.isRegularFile(target) && SteamNativeLibraries.matches(image, target)) return;
        Path partial = Files.createTempFile(target.getParent(), "phonon", ".part");
        try {
            Files.write(partial, image);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replaceFailed) {
                // Another instance won the race, or holds the library loaded (Windows cannot replace it).
                if (!Files.isRegularFile(target) || !SteamNativeLibraries.matches(image, target)) {
                    throw replaceFailed;
                }
            }
        } finally {
            deleteQuietly(partial);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort: a stale part is overwritten next attempt.
        }
    }

    private SteamAudioDownload() { }
}
