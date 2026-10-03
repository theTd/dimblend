package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.PathingField;
import dimblend.radio.acoustics.PropagationDelayLine;
import dimblend.radio.acoustics.SteamRenderer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.phys.Vec3;

/**
 * One recording of the radios' acoustics, to debug what is heard: every block each bound radio
 * plays, with its parts and what it was rendered with, and the simulation's events, written into a
 * directory of its own by a background thread. The audio threads only queue copies; while nothing
 * is recorded {@link #active()} is null and they do nothing.
 * <p>
 * Files: {@code timeline.csv} (a row per played block), {@code events.csv}, per radio
 * {@code radio-N-output.wav} (what was handed to OpenAL), {@code radio-N-direct.wav} and
 * {@code radio-N-echo.wav} (the rendered parts before the limiter, at half scale), and
 * {@code README.txt} describing them.
 */
final class AcousticRecording {
    static final int MAX_SECONDS = 120;
    /** Writes queued before further ones are dropped (and counted): about 12 s of four radios, some 50 MB. */
    private static final int CAPACITY = 1 << 12;
    /** The stems can pass full scale before the limiter: they are written at half scale. */
    private static final float STEM_SCALE = 0.5f;
    private static final long FLUSH_INTERVAL = 1_000_000_000L;
    private static final DateTimeFormatter DIRECTORY_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String TIMELINE_HEADER = "radio,block,audio_s,t_ms,mode,inline,render_us,prepare_us,"
            + "listener_x,listener_y,listener_z,ahead_x,ahead_y,ahead_z,source_x,source_y,source_z,distance,"
            + "delay_ms,direct_gain,direct_eq_low,direct_eq_mid,direct_eq_high,occlusion,"
            + "transmission_low,transmission_mid,transmission_high,air_low,air_mid,air_high,direct_age_ms,"
            + "reflections,ir_count,ir_age_ms,wet_scale,"
            + "pathing_low,pathing_mid,pathing_high,pathing_w,pathing_length,"
            + "direct_db,echo_db,reflections_db,pathing_db,peak,limiter_gain,output_db,output_peak";
    private static volatile AcousticRecording active;

    /**
     * What the position stage rendered one block with.
     *
     * @param directAgeNanos since the direct simulation it used was published; -1 without one
     * @param irCount how many IRs this radio had received when the block was rendered
     * @param irAgeNanos since the IR it used was published; -1 without one
     * @param pathing the diffracted path it added, or null
     */
    record Staging(double delaySamples, float directGain, float[] directEq, float occlusion, float[] transmission,
            float[] air, long directAgeNanos, SteamRenderer.ReflectionState reflections, long irCount, long irAgeNanos,
            PathingField pathing, long prepareNanos) { }

    /**
     * One block as played.
     *
     * @param mode how it was produced: rendered, panned, silent, a crossfade such as panned>rendered,
     *     starting (the look-ahead still filling) or failed
     * @param inline the DSP thread had fallen behind and the block was staged on the sound thread
     * @param staging the rendered part's position stage, or null without a rendered part
     * @param stems the rendered part before the limiter, or null without one
     * @param output what was played, or null for silence
     */
    record Played(String mode, boolean inline, Vec3 listener, Vec3 ahead, Vec3 source, Staging staging,
            float wetScale, SteamRenderer.Stems stems, float[][] output, long renderNanos) {
        static Played silent(String mode, Vec3 listener, Vec3 ahead, Vec3 source) {
            return new Played(mode, false, listener, ahead, source, null, 0, null, null, 0);
        }
    }

    /** What a finished recording holds. */
    record Summary(Path directory, double seconds, int radios, long bytes, long dropped, IOException failure) { }

    /** One radio's part of the recording. */
    final class Track {
        private final int number;
        private final String label;
        private final int rate;
        private final long firstNanos = System.nanoTime();
        /** Blocks played so far; by the radio's sound thread. */
        private long blocks;
        // Writer thread.
        private PcmWavWriter output, direct, echo;
        private long written;

        private Track(int number, String label, int rate) {
            this.number = number;
            this.label = label;
            this.rate = rate;
        }

        int number() { return number; }

        /** The radio's next block, in play order; called by its sound thread. */
        void played(Played block) {
            long index = blocks++;
            long at = System.nanoTime();
            submit(() -> writeBlock(this, index, at, block));
        }

        void event(String kind, String detail) {
            AcousticRecording.this.event(this, kind, detail);
        }
    }

    private final Path directory;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private final long startNanos = System.nanoTime();
    private final int limitSeconds;
    private final ThreadPoolExecutor writer;
    private final Map<Object, Track> tracks = new ConcurrentHashMap<>();
    private final AtomicInteger nextTrack = new AtomicInteger(1);
    private final AtomicLong dropped = new AtomicLong();
    private CompletableFuture<Summary> stopped;
    // Writer thread.
    private final BufferedWriter timeline, events;
    private long lastFlush = System.nanoTime();
    private IOException failure;

    private AcousticRecording(Path root, int limitSeconds) throws IOException {
        this.limitSeconds = limitSeconds;
        Path candidate = root.resolve(DIRECTORY_NAME.format(startedAt));
        for (int n = 2; Files.exists(candidate); n++) candidate = root.resolve(DIRECTORY_NAME.format(startedAt) + "-" + n);
        directory = Files.createDirectories(candidate);
        timeline = Files.newBufferedWriter(directory.resolve("timeline.csv"), StandardCharsets.UTF_8);
        events = Files.newBufferedWriter(directory.resolve("events.csv"), StandardCharsets.UTF_8);
        timeline.write(TIMELINE_HEADER);
        timeline.newLine();
        events.write("t_ms,radio,kind,detail");
        events.newLine();
        writeReadme(null);
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(CAPACITY), task -> {
            Thread thread = new Thread(task, "Radio acoustic recording");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The running recording, or null. */
    static AcousticRecording active() { return active; }

    /**
     * Starts recording into a new directory under {@code root}.
     *
     * @throws IllegalStateException while another recording runs
     */
    static synchronized AcousticRecording start(Path root, int limitSeconds) throws IOException {
        if (active != null) throw new IllegalStateException("Already recording");
        if (limitSeconds < 1 || limitSeconds > MAX_SECONDS) throw new IllegalArgumentException("Recording length out of range: " + limitSeconds);
        active = new AcousticRecording(root, limitSeconds);
        DimBlendRadio.LOGGER.info("[radio] recording acoustics for {} s into {}", limitSeconds, active.directory);
        return active;
    }

    Path directory() { return directory; }

    int limitSeconds() { return limitSeconds; }

    double elapsedSeconds() { return (System.nanoTime() - startNanos) / 1e9; }

    /** {@code key}'s track, created on first use; numbered in the order radios first appear. */
    Track track(Object key, String label, int rate) {
        return tracks.computeIfAbsent(key, ignored -> new Track(nextTrack.getAndIncrement(), label, rate));
    }

    /** @param track the radio it concerns, or null for the simulation as a whole */
    void event(Track track, String kind, String detail) {
        long at = System.nanoTime();
        submit(() -> writeEvent(track, at, kind, detail));
    }

    /**
     * Stops taking blocks and events. Completes once everything queued is written and the files are
     * closed; calling it again returns the same result.
     */
    synchronized CompletableFuture<Summary> stop() {
        if (stopped != null) return stopped;
        synchronized (AcousticRecording.class) {
            if (active == this) active = null;
        }
        writer.shutdown();
        stopped = new CompletableFuture<>();
        Thread closing = new Thread(() -> {
            try { stopped.complete(finish()); }
            catch (RuntimeException | Error error) { stopped.completeExceptionally(error); }
        }, "Radio acoustic recording close");
        closing.setDaemon(true);
        closing.start();
        return stopped;
    }

    private void submit(Runnable task) {
        if (writer.isShutdown()) return;
        try { writer.execute(task); }
        catch (RejectedExecutionException full) { if (!writer.isShutdown()) dropped.incrementAndGet(); }
    }

    private Summary finish() {
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) DimBlendRadio.LOGGER.warn("[radio] acoustic recording still writing after 30 s");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        double seconds = elapsedSeconds();
        long bytes = 0;
        for (Track track : tracks.values()) {
            for (PcmWavWriter file : new PcmWavWriter[] {track.output, track.direct, track.echo}) {
                if (file == null) continue;
                bytes += file.dataBytes();
                try { file.close(); } catch (IOException error) { remember(error); }
            }
        }
        try { timeline.close(); } catch (IOException error) { remember(error); }
        try { events.close(); } catch (IOException error) { remember(error); }
        try { writeReadme(seconds); } catch (IOException error) { remember(error); }
        try { bytes += Files.size(directory.resolve("timeline.csv")) + Files.size(directory.resolve("events.csv")); }
        catch (IOException error) { remember(error); }
        DimBlendRadio.LOGGER.info("[radio] acoustic recording saved: {} ({} radios, {} s, {} writes dropped)",
                directory, tracks.size(), Math.round(seconds), dropped.get());
        return new Summary(directory, seconds, tracks.size(), bytes, dropped.get(), failure);
    }

    private void writeBlock(Track track, long index, long at, Played block) {
        if (failure != null) return;
        try {
            if (track.output == null) {
                String prefix = "radio-" + track.number;
                track.output = new PcmWavWriter(directory.resolve(prefix + "-output.wav"), track.rate, 2);
                track.direct = new PcmWavWriter(directory.resolve(prefix + "-direct.wav"), track.rate, 2);
                track.echo = new PcmWavWriter(directory.resolve(prefix + "-echo.wav"), track.rate, 2);
            }
            track.output.write(block.output, SteamRenderer.FRAME, 1);
            track.direct.write(block.stems == null ? null : block.stems.direct, SteamRenderer.FRAME, STEM_SCALE);
            track.echo.write(block.stems == null ? null : block.stems.echo, SteamRenderer.FRAME, STEM_SCALE);
            track.written++;
            timeline.write(row(track, index, at, block));
            timeline.newLine();
            flushIfDue();
        } catch (IOException error) { remember(error); }
    }

    private void writeEvent(Track track, long at, String kind, String detail) {
        if (failure != null) return;
        try {
            events.write(String.format(Locale.ROOT, "%.1f,%s,%s,\"%s\"", millis(at), track == null ? "" : Integer.toString(track.number),
                    kind, detail.replace("\"", "\"\"")));
            events.newLine();
            flushIfDue();
        } catch (IOException error) { remember(error); }
    }

    private void flushIfDue() throws IOException {
        long now = System.nanoTime();
        if (now - lastFlush < FLUSH_INTERVAL) return;
        lastFlush = now;
        timeline.flush();
        events.flush();
        for (Track track : tracks.values()) {
            if (track.output == null) continue;
            track.output.flush();
            track.direct.flush();
            track.echo.flush();
        }
    }

    private void remember(IOException error) {
        if (failure == null) {
            failure = error;
            DimBlendRadio.LOGGER.warn("[radio] acoustic recording cannot write; later blocks are lost", error);
        }
    }

    private double millis(long nanos) { return (nanos - startNanos) / 1e6; }

    private String row(Track track, long index, long at, Played block) {
        StringBuilder row = new StringBuilder(512);
        row.append(track.number).append(',').append(index).append(',');
        number(row, (double) index * SteamRenderer.FRAME / track.rate, 4);
        number(row, millis(at), 1);
        row.append(block.mode).append(',').append(block.inline ? 1 : 0).append(',');
        number(row, block.renderNanos / 1e3, 0);
        Staging staging = block.staging;
        if (staging == null) row.append(',');
        else number(row, staging.prepareNanos / 1e3, 0);
        vector(row, block.listener, 3);
        vector(row, block.ahead, 4);
        vector(row, block.source, 3);
        number(row, block.listener == null || block.source == null ? Double.NaN : block.listener.distanceTo(block.source), 3);
        if (staging == null) {
            row.append(",".repeat(16));
        } else {
            number(row, staging.delaySamples * 1000 / track.rate, 3);
            number(row, staging.directGain, 4);
            for (float band : staging.directEq) number(row, band, 4);
            number(row, staging.occlusion, 4);
            for (float band : staging.transmission) number(row, band, 4);
            for (float band : staging.air) number(row, band, 4);
            number(row, staging.directAgeNanos < 0 ? Double.NaN : staging.directAgeNanos / 1e6, 1);
            row.append(staging.reflections.name().toLowerCase(Locale.ROOT)).append(',').append(staging.irCount).append(',');
            number(row, staging.irAgeNanos < 0 ? Double.NaN : staging.irAgeNanos / 1e6, 1);
        }
        number(row, block.stems == null ? Double.NaN : block.wetScale, 3);
        PathingField pathing = staging == null ? null : staging.pathing;
        if (pathing == null) row.append(",".repeat(5));
        else {
            for (float band : pathing.eq()) number(row, band, 4);
            number(row, pathing.sh()[0], 4);
            number(row, pathing.length(), 2);
        }
        SteamRenderer.Stems stems = block.stems;
        if (stems == null) row.append(",".repeat(6));
        else {
            number(row, decibels(energy(stems.direct) / (2.0 * SteamRenderer.FRAME)), 1);
            number(row, decibels(energy(stems.echo) / (2.0 * SteamRenderer.FRAME)), 1);
            number(row, decibels(stems.reflectedEnergy / (4.0 * SteamRenderer.FRAME)), 1);
            number(row, decibels(stems.pathEnergy / (4.0 * SteamRenderer.FRAME)), 1);
            number(row, stems.peak, 4);
            number(row, stems.limiterGain, 4);
        }
        number(row, decibels(energy(block.output) / (2.0 * SteamRenderer.FRAME)), 1);
        float peak = 0;
        if (block.output != null) for (float[] channel : block.output) for (float sample : channel) peak = Math.max(peak, Math.abs(sample));
        row.append(String.format(Locale.ROOT, "%.4f", peak));
        return row.toString();
    }

    private static void vector(StringBuilder row, Vec3 value, int decimals) {
        number(row, value == null ? Double.NaN : value.x, decimals);
        number(row, value == null ? Double.NaN : value.y, decimals);
        number(row, value == null ? Double.NaN : value.z, decimals);
    }

    /** The value and a comma; nothing for NaN. */
    private static void number(StringBuilder row, double value, int decimals) {
        if (!Double.isNaN(value)) row.append(String.format(Locale.ROOT, "%." + decimals + "f", value));
        row.append(',');
    }

    private static double energy(float[][] samples) {
        double sum = 0;
        if (samples != null) for (float[] channel : samples) for (float sample : channel) sum += sample * (double) sample;
        return sum;
    }

    /** Mean square as decibels relative to full scale, floored at -120. */
    static double decibels(double meanSquare) {
        return meanSquare <= 1e-12 ? -120 : 10 * Math.log10(meanSquare);
    }

    private void writeReadme(Double seconds) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Radio acoustics recording");
        lines.add("Started " + startedAt + ", limit " + limitSeconds + " s"
                + (seconds == null ? " (still recording, or the game stopped before it finished)"
                        : String.format(Locale.ROOT, ", recorded %.1f s", seconds)));
        lines.add("Settings: wetgain=" + System.getProperty("dimblend.radio.acoustic.wetgain", "default")
                + ", doppler scale=" + PropagationDelayLine.configuredScale()
                + ", gpu=" + System.getProperty("dimblend.radio.acoustic.gpu", "default"));
        if (seconds != null) {
            lines.add("Writes dropped (queue full): " + dropped.get() + (failure == null ? "" : "; write failure: " + failure));
            lines.add("");
            lines.add("Radios (numbered as they first played during the recording):");
            List<Track> ordered = new ArrayList<>(tracks.values());
            ordered.sort(Comparator.comparingInt(track -> track.number));
            for (Track track : ordered) {
                lines.add(String.format(Locale.ROOT, "  radio %d: %s, %d Hz, %d blocks (%.1f s of audio) from t=%.0f ms; clipped samples output/direct/echo %d/%d/%d",
                        track.number, track.label, track.rate, track.written, (double) track.written * SteamRenderer.FRAME / track.rate,
                        millis(track.firstNanos), clipped(track.output), clipped(track.direct), clipped(track.echo)));
            }
        }
        lines.add("");
        lines.add("radio-N-output.wav: exactly what radio N handed to OpenAL (16-bit stereo), block after block.");
        lines.add("radio-N-direct.wav / radio-N-echo.wav: its rendered parts before the limiter, at HALF scale (-6 dB):");
        lines.add("  direct = the spatialized direct sound; echo = reflections and the diffracted path, decoded together.");
        lines.add("  They are silent where the radio was panned or silent. In a crossfade they hold the rendered side, unweighted.");
        lines.add("Block b of radio N starts at audio_s = b * 512 / rate in its WAV files.");
        lines.add("");
        lines.add("timeline.csv, a row per played block (512 samples):");
        lines.add("  t_ms: when the block was rendered, since the start; it is heard after the stream's few queued buffers (25-90 ms).");
        lines.add("  mode: rendered (Steam Audio), panned (stereo panning), silent, a crossfade such as panned>rendered,");
        lines.add("        starting (the look-ahead still filling) or failed. inline=1: the DSP thread had fallen behind.");
        lines.add("  render_us / prepare_us: time of the orientation stage (sound thread) and of the position stage (DSP thread).");
        lines.add("  listener/ahead/source: world positions and look direction as the block was played.");
        lines.add("  delay_ms: propagation delay. direct_gain: the direct sound's level, direct_eq_*: its band shape.");
        lines.add("  occlusion, transmission_*, air_*: Steam Audio's direct simulation; direct_age_ms: how old it was.");
        lines.add("  reflections: none, awaiting (reset, waiting for a fresh IR), convolved, tail, invalid.");
        lines.add("  ir_count: IRs received so far (a change is a new IR); ir_age_ms: age of the IR in use. wet_scale: echo gain.");
        lines.add("  pathing_*: the diffracted path's band gains, its omni level (w) and length in blocks; empty without one.");
        lines.add("  *_db: block level in dB full scale: direct and echo after decoding, reflections and pathing before it.");
        lines.add("  peak / limiter_gain: level before the limiter and its gain after the block. output_*: what was played.");
        lines.add("events.csv: t_ms, radio (empty: the whole simulation), kind, detail.");
        lines.add("  reflection_run: a shared GPU run; ir_ready: a radio received an IR; ir_invalid: an IR was rejected;");
        lines.add("  restart: the renderer resumed after silence or panning; starved: the audio device ran dry;");
        lines.add("  scene_capture: the world snapshot was taken again; selected/released: a radio gained or lost simulation;");
        lines.add("  bound/closed: a radio's acoustic session began or ended; failed: its acoustics were disabled.");
        Files.write(directory.resolve("README.txt"), lines, StandardCharsets.UTF_8);
    }

    private static long clipped(PcmWavWriter file) {
        return file == null ? 0 : file.clipped();
    }
}
