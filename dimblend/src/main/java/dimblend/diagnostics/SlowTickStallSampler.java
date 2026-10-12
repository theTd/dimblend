package dimblend.diagnostics;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * Background thread of a slow-tick session: once the current server tick has run longer than
 * {@link #START_AFTER_NANOS}, it samples the server thread's stack every
 * {@link #INTERVAL_MILLIS} until that tick ends, then writes {@code stall-<tick>.txt}. The
 * section profiler cannot look inside a leaf section (e.g. a multi-second {@code spawner});
 * the stacks can. {@link Thread#getStackTrace()} on another thread pauses only that thread
 * (a handshake), not the whole JVM like spark's Java sampler.
 */
final class SlowTickStallSampler implements Runnable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final long START_AFTER_NANOS = 200_000_000L;
    private static final long INTERVAL_MILLIS = 20L;
    private static final long IDLE_POLL_MILLIS = 10L;
    private static final int MAX_FILES = 50;
    private final Path directory;
    private volatile boolean running = true;
    private int written;

    SlowTickStallSampler(Path directory) {
        this.directory = directory;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            while (running) {
                Thread target = SlowTickRecorder.serverThread();
                long start = SlowTickRecorder.tickStartNanos();
                int serial = SlowTickRecorder.tickSerial();
                if (target == null || start == 0L || System.nanoTime() - start < START_AFTER_NANOS) {
                    Thread.sleep(IDLE_POLL_MILLIS);
                    continue;
                }
                StallStackProfile profile = new StallStackProfile();
                while (running && SlowTickRecorder.tickSerial() == serial && SlowTickRecorder.tickStartNanos() == start) {
                    profile.add(target.getStackTrace());
                    Thread.sleep(INTERVAL_MILLIS);
                }
                report(serial, start, profile);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void report(int serial, long start, StallStackProfile profile) {
        if (profile.samples() == 0) {
            return;
        }
        double tickMs = (System.nanoTime() - start) / 1_000_000.0D;
        LOGGER.info("dimblend tick stall: tick #{} ran ~{} ms; {} stack samples, hot spot {}", serial,
                String.format(Locale.ROOT, "%.0f", tickMs), profile.samples(), profile.hottestLeaf(0.6D));
        if (written >= MAX_FILES) {
            return;
        }
        written++;
        String header = String.format(Locale.ROOT, "dimblend tick stall: tick #%d ran ~%.0f ms; sampling started %d ms into the tick",
                serial, tickMs, START_AFTER_NANOS / 1_000_000L);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("stall-" + serial + ".txt"), profile.render(header, 40, 3),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write stall report", e);
        }
    }
}
