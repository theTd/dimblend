package dimblend.diagnostics;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.ResultField;
import org.slf4j.Logger;

/**
 * Records what slow server ticks do, using vanilla's section profiler (the one behind
 * {@code /debug start}) instead of a sampler. On Windows spark can only sample with its Java
 * engine, which stops the whole JVM per sample and inflates the very ticks it measures; the
 * section profiler only timestamps named sections on the server thread.
 *
 * <p>While a session runs, every tick gets its own {@link ActiveProfiler}, tee'd next to the
 * server's profiler (vanilla's dormant {@code SingleTickProfiler} hook). A tick is slow when its
 * {@code tick} section (the work) reaches the threshold; {@code nextTickWait} is reported
 * separately, so late wake-ups cannot inflate the count. Slow ticks are logged one per line,
 * the first {@link #MAX_FULL_REPORTS} also get vanilla's full section report, and stopping
 * writes the aggregated {@link SlowTickReport}. Everything runs on the server thread.
 */
public final class SlowTickRecorder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "root";
    private static final char SEPARATOR = '\u001e';
    private static final int MAX_DEPTH = 10;
    private static final int MAX_FULL_REPORTS = 20;
    /** Ticks at the start of a session that are profiled but not evaluated. */
    static final int WARMUP_TICKS = 40;
    /** Sections shorter than this are not walked further (keeps the per-slow-tick walk cheap). */
    private static final long MIN_WALK_NANOS = 50_000L;
    private static Session session;
    private static ActiveProfiler current;
    private static int tickNumber;
    /** Read by the stall sampler thread. */
    private static volatile Thread serverThread;
    private static volatile long tickStartNanos;
    private static volatile int tickSerial;
    private static SlowTickStallSampler sampler;

    static Thread serverThread() { return serverThread; }
    static long tickStartNanos() { return tickStartNanos; }
    static int tickSerial() { return tickSerial; }

    private SlowTickRecorder() {
    }

    private static final class Session {
        final long thresholdNanos;
        final long thresholdMs;
        final Path directory;
        final SlowTickReport report = new SlowTickReport();
        int warmupSeen;
        long ticksSeen;
        int fullReports;

        Session(long thresholdMs, Path directory) {
            this.thresholdMs = thresholdMs;
            this.thresholdNanos = thresholdMs * 1_000_000L;
            this.directory = directory;
        }
    }

    public static boolean running() {
        return session != null;
    }

    public static Path start(MinecraftServer server, int thresholdMs) {
        Path directory = server.getServerDirectory().resolve("debug").resolve("dimblend-slowticks")
                .resolve(Util.getFilenameFormattedDateTime());
        session = new Session(thresholdMs, directory);
        sampler = new SlowTickStallSampler(directory);
        Thread thread = new Thread(sampler, "dimblend-stall-sampler");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("dimblend slow-tick recording started: threshold {} ms, output {}", thresholdMs, directory);
        return directory;
    }

    /** Stops the session and writes the summary; returns its path, or null when nothing was running. */
    public static Path stop() throws IOException {
        Session finished = session;
        session = null;
        current = null;
        tickStartNanos = 0L;
        if (sampler != null) {
            sampler.stop();
            sampler = null;
        }
        if (finished == null) {
            return null;
        }
        Files.createDirectories(finished.directory);
        Path summary = finished.directory.resolve("summary.txt");
        Files.writeString(summary, finished.report.render(finished.thresholdMs, finished.ticksSeen, WARMUP_TICKS, 0.1D),
                StandardCharsets.UTF_8);
        LOGGER.info("dimblend slow-tick recording stopped: {} of {} ticks slow, summary {}",
                finished.report.slowTicks(), finished.ticksSeen, summary);
        return summary;
    }

    /** A session never outlives its server: write what was recorded when the world closes. */
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        try {
            stop();
        } catch (IOException e) {
            LOGGER.warn("Could not write the slow-tick summary on shutdown", e);
        }
    }

    public static String status() {
        Session running = session;
        if (running == null) {
            return "slow-tick recording is off";
        }
        return String.format(java.util.Locale.ROOT, "recording ticks over %d ms: %d of %d slow so far (worst %.1f ms work), output %s",
                running.thresholdMs, running.report.slowTicks(), running.ticksSeen,
                running.report.worstWorkNanos() / 1_000_000.0D, running.directory);
    }

    /** Called where the server builds this tick's profiler; adds this tick's section profiler. */
    public static ProfilerFiller decorate(ProfilerFiller serverProfiler) {
        if (session == null) {
            current = null;
            return serverProfiler;
        }
        current = new ActiveProfiler(Util.timeSource, () -> tickNumber, false);
        serverThread = Thread.currentThread();
        tickSerial++;
        tickStartNanos = System.nanoTime();
        return ProfilerFiller.tee(current, serverProfiler);
    }

    /** Called after the server ended this tick's profiling. */
    public static void endTick() {
        ActiveProfiler profiler = current;
        Session running = session;
        current = null;
        tickStartNanos = 0L;
        if (profiler == null || running == null) {
            return;
        }
        tickNumber++;
        if (++running.warmupSeen <= WARMUP_TICKS) {
            // The first profiled ticks pay one-time setup (class init, first section paths) and
            // came out as false slow ticks; they say nothing about the game.
            return;
        }
        running.ticksSeen++;
        ProfileResults results = profiler.getResults();
        long total = results.getNanoDuration();
        Map<String, Long> paths = new HashMap<>();
        walk(results, ROOT, "", total, 0, paths);
        long work = paths.getOrDefault("tick", 0L);
        if (work < running.thresholdNanos) {
            return;
        }
        long wait = paths.getOrDefault("nextTickWait", 0L);
        running.report.add(paths, work, wait);
        LOGGER.info("dimblend slow tick: {}", SlowTickReport.line(paths, work, wait));
        if (running.fullReports < MAX_FULL_REPORTS) {
            running.fullReports++;
            try {
                Files.createDirectories(running.directory);
                results.saveResults(running.directory.resolve("tick-" + running.ticksSeen + ".txt"));
            } catch (IOException e) {
                LOGGER.warn("Could not write slow-tick report", e);
            }
        }
    }

    /** Flattens the profiler tree into {@code a/b/c -> nanos}, skipping the root and tiny sections. */
    private static void walk(ProfileResults results, String profilerPath, String reportPath, long total,
                             int depth, Map<String, Long> out) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        for (ResultField field : results.getTimes(profilerPath)) {
            if (field.name.equals(profilerPath)) {
                // getTimes() lists the parent itself first, under its full internal path.
                continue;
            }
            long nanos = Math.round(field.globalPercentage * total / 100.0D);
            String child = reportPath.isEmpty() ? field.name : reportPath + "/" + field.name;
            out.merge(child, nanos, Long::sum);
            if (!SlowTickReport.UNSPECIFIED.equals(field.name) && nanos >= MIN_WALK_NANOS) {
                walk(results, profilerPath + SEPARATOR + field.name, child, total, depth + 1, out);
            }
        }
    }
}
