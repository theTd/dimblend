package dimblend.diagnostics;

import com.mojang.logging.LogUtils;
import dimblend.DimBlend;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Out-of-band hang watchdog for the integrated/dedicated server thread.
 *
 * <p>When the server thread hangs (infinite loop, blocking IO, lock deadlock), every
 * in-band reporter dies with it: the bossbar freezes and the stall log goes silent.
 * This watchdog runs on its own daemon thread and only needs volatile reads, so it
 * keeps working while the rest of the JVM is stuck. Two escalation levels:
 *
 * <ul>
 * <li>After {@link #JFR_START_NANOS} without a completed tick a JFR recording starts
 * (in-memory, bounded by maxAge). On recovery, a recording that covered at least
 * {@link #JFR_KEEP_NANOS} is exported to {@code logs/dimblend-jfr-*.jfr} and the path
 * is announced in chat to permission-2 players; shorter blips are discarded.
 * <li>After {@link #HANG_NANOS} the full thread dump is written to
 * {@code logs/dimblend-hang-*.txt} (every thread, locked monitors and synchronizers,
 * deadlock detection), prefixed with the monitor's last engine sample, and a JFR
 * snapshot is dumped alongside so a kill after a permanent hang still keeps the data.
 * While the hang persists the dump is rewritten every {@link #REDUMP_NANOS}; recovery
 * is logged with the total hang duration. Recovery is detected solely by the heartbeat
 * value advancing between polls — never by silence dropping under a threshold — so a
 * long-tick pattern that never shows a sub-threshold window still closes its episode.
 * </ul>
 *
 * <p>Timing uses {@link System#nanoTime()} throughout: a wall-clock step (NTP) must not
 * fabricate or mask a hang. Wall clock appears only in dump headers and file names.
 *
 * <p>Session isolation: every {@code start()} publishes a new immutable {@link Session}
 * (server + per-session heartbeat and JFR state); the old session's loop exits at its
 * next check, so a stop() followed by a quick restart can never let an old thread write
 * the new session's state. A session is armed by its first heartbeat, not by start():
 * slow world load before the first tick cannot fire a false dump.
 *
 * <p>False-positive suppression reads only volatile-backed state across threads: server
 * halting uses {@code isRunning()} (volatile, flips false at halt before the save
 * window) plus the {@link ServerStoppedEvent} detach. Singleplayer pause is mirrored by
 * the client bridge from the volatile {@code Minecraft.pause} into this watchdog's
 * {@code clientPaused} (gated by {@code clientSeen}, so dedicated servers never
 * suppress), because {@code IntegratedServer.paused} is a plain boolean with no
 * happens-before edge to this thread. A synchronous save longer than
 * {@link #HANG_NANOS} still dumps by design (save IO can itself be the hang); the dump
 * header carries {@code isCurrentlySaving()} so those are distinguishable.
 */
public final class HangWatchdog {
    /** Server-thread silence that starts the JFR recording. */
    private static final long JFR_START_NANOS = 1_000_000_000L;
    /** Minimum JFR coverage for the recording to be worth exporting on recovery. */
    private static final long JFR_KEEP_NANOS = 5_000_000_000L;
    /** Server-thread silence that triggers the full thread dump. */
    private static final long HANG_NANOS = 10_000_000_000L;
    /** Rewrite the thread dump at most this often while a hang persists. */
    private static final long REDUMP_NANOS = 30_000_000_000L;
    /** Watchdog poll cadence; bounds detection latency well below JFR_START_NANOS. */
    private static final long POLL_MILLIS = 500L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS");

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Immutable monitoring session. All mutable counters, including the JFR recording,
     * are confined to the watchdog thread that owns this session; {@code lastTickDone}
     * is an AtomicLong only so the server thread can write the heartbeat without a
     * happens-before edge to the loop.
     */
    private static final class Session {
        final MinecraftServer server;
        /**
         * nanoTime of the last completed server tick. Zero until the first heartbeat
         * arms the session: a world load slower than HANG_NANOS is not a hang.
         */
        final AtomicLong lastTickDone = new AtomicLong();
        /** nanoTime of the last dump; watchdog thread only. Valid once hasDumped is set. */
        long lastDumpAt;
        /** True once this session has written at least one dump; makes lastDumpAt valid. */
        boolean hasDumped;
        /** nanoTime heartbeat when the current hang started; 0 while healthy. */
        long hangStart;
        boolean hanging;
        /** Heartbeat value seen at the previous poll; watchdog thread only. */
        long lastSeenTick;
        /** JFR recording while a hang is being covered; watchdog thread only. */
        Recording jfr;
        /** nanoTime when {@link #jfr} started. */
        long jfrStartedAt;
        /** True once JFR proved unavailable in this JVM; stops retrying every poll. */
        boolean jfrFailed;

        Session(MinecraftServer server) {
            this.server = server;
        }
    }

    /** Current session; null while no server is running. Server thread writes, watchdog reads. */
    private volatile Session session;
    /** Current watchdog thread handle, for interrupt on stop; null while disarmed. */
    private volatile Thread thread;
    /**
     * Client-side pause mirror, written by the client bridge only. {@code clientSeen}
     * gates it: a dedicated server never sets it, so client pause never suppresses there.
     */
    private final AtomicBoolean clientPaused = new AtomicBoolean();
    private final AtomicBoolean clientSeen = new AtomicBoolean();

    /** Client tick bridge; mirrors the volatile client pause flag. Client thread only. */
    public void clientPause(boolean paused) {
        this.clientSeen.set(true);
        this.clientPaused.set(paused);
    }

    /**
     * Heartbeat written by {@link dimblend.worldgen.ChunkGenMonitor} at the very end of
     * its tick handler, so a hang inside sampling itself stops the heartbeat immediately.
     */
    public void heartbeat() {
        Session current = this.session;
        if (current != null) {
            current.lastTickDone.set(System.nanoTime());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        this.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        this.stop();
    }

    private void start(MinecraftServer server) {
        // Invalidate any previous session first so its loop exits even mid-hang.
        this.session = null;
        Session fresh = new Session(server);
        Thread started = new Thread(() -> this.loop(fresh), "dimblend-hang-watchdog");
        started.setDaemon(true);
        this.thread = started;
        // Publish the session last so the loop owns it from its first instruction.
        this.session = fresh;
        started.start();
        LOGGER.info("dimblend hang watchdog armed (jfr {}ms, dump {}ms thresholds)",
                JFR_START_NANOS / 1_000_000L, HANG_NANOS / 1_000_000L);
    }

    private void stop() {
        // Detach the session first (loop notices within one poll), then interrupt to
        // break it out of sleep sooner. Save the handle before nulling it.
        this.session = null;
        Thread current = this.thread;
        this.thread = null;
        if (current != null) {
            current.interrupt();
        }
    }

    private void loop(Session owned) {
        try {
            this.loopBody(owned);
        } finally {
            // Any exit path (stop, restart, interrupt) must release the recording.
            this.finalizeJfr(owned, System.nanoTime());
        }
    }

    private void loopBody(Session owned) {
        while (this.session == owned) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            if (this.session != owned) {
                return;
            }
            long now = System.nanoTime();
            long tick = owned.lastTickDone.get();
            if (tick == 0L) {
                // Not armed yet: no tick has completed since start. A slow world load
                // before the first tick is not a hang.
                continue;
            }
            if (tick != owned.lastSeenTick) {
                // The heartbeat value advanced since the previous poll: a server tick
                // genuinely completed, no matter how long the gap was. That is the
                // only reliable recovery signal — silence below a threshold is not
                // (the poll can keep missing sub-threshold windows when ticks run
                // long), so episodes close here and only here.
                if (owned.jfr != null || owned.hanging) {
                    this.finalizeJfr(owned, now);
                }
                if (owned.hanging) {
                    owned.hanging = false;
                    owned.hasDumped = false;
                    owned.lastDumpAt = 0L;
                    LOGGER.error("dimblend watchdog: server thread recovered after {}ms",
                            (tick - owned.hangStart) / 1_000_000L);
                }
                owned.lastSeenTick = tick;
            }
            MinecraftServer current = owned.server;
            // Halting (volatile running flips false at halt, before the save window)
            // and singleplayer pause (mirrored volatile, clientSeen-gated) legitimately
            // stop ticks: close out any recording, treat both as fresh activity so
            // neither fires a dump.
            if (!current.isRunning() || (this.clientSeen.get() && this.clientPaused.get())) {
                this.finalizeJfr(owned, now);
                owned.lastTickDone.set(now);
                // Baseline reset closes any episode: next hang starts from a clean
                // first-dump + throttle state.
                owned.hanging = false;
                owned.hasDumped = false;
                owned.lastDumpAt = 0L;
                continue;
            }
            long silent = now - tick;
            if (silent >= JFR_START_NANOS) {
                this.startJfr(owned, now, silent);
            }
            if (silent < HANG_NANOS) {
                // Unresponsive but below the dump threshold: the running JFR covers it.
                continue;
            }
            if (owned.hasDumped && now - owned.lastDumpAt < REDUMP_NANOS) {
                continue;
            }
            if (!owned.hasDumped) {
                // First dump of this hang: also snapshot the JFR so the data survives
                // a process kill after a permanent hang.
                this.snapshotJfr(owned);
            }
            if (!owned.hanging) {
                owned.hanging = true;
                owned.hangStart = tick;
            }
            owned.lastDumpAt = now;
            owned.hasDumped = true;
            // The dump must never kill the watchdog: a single JMX/formatting failure
            // logs and the loop keeps monitoring.
            try {
                this.dump(owned, silent / 1_000_000L);
            } catch (Throwable t) {
                LOGGER.error("dimblend watchdog: thread dump failed", t);
            }
        }
    }

    /** Starts the hang recording once per hang; a no-op while one is already running. */
    private void startJfr(Session owned, long now, long silent) {
        if (owned.jfr != null || owned.jfrFailed) {
            return;
        }
        Recording recording = null;
        try {
            // Bare new Recording() enables no events; the built-in "profile"
            // configuration turns on method/allocation sampling with stack traces —
            // the data that shows what the server thread was doing during the hang.
            recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName("dimblend-hang");
            // Memory-resident: nothing is written to disk until the explicit dump (or
            // the post-hang snapshot), so a discarded blip costs RAM only. JDK default
            // is disk-backed, which would churn the disk on every 1s blip.
            recording.setToDisk(false);
            recording.setMaxAge(Duration.ofSeconds(60L));
            recording.start();
            owned.jfr = recording;
            owned.jfrStartedAt = now;
            LOGGER.warn("dimblend watchdog: server thread unresponsive {}ms; JFR recording started",
                    silent / 1_000_000L);
        } catch (Throwable t) {
            owned.jfrFailed = true;
            closeQuietly(recording);
            LOGGER.error("dimblend watchdog: JFR unavailable in this JVM", t);
        }
    }

    /** Closes a recording that was never started, swallowing secondary failures. */
    private static void closeQuietly(Recording recording) {
        if (recording == null) {
            return;
        }
        try {
            recording.close();
        } catch (Throwable ignored) {
            // never constructed fully or already closed; nothing to release
        }
    }

    /**
     * Ends the hang recording. A recording that covered at least {@link #JFR_KEEP_NANOS}
     * is exported and announced in chat to permission-2 players; a shorter blip is
     * discarded. Idempotent: a no-op when no recording is running.
     */
    private void finalizeJfr(Session owned, long now) {
        Recording recording = owned.jfr;
        if (recording == null) {
            return;
        }
        owned.jfr = null;
        long coveredMillis = (now - owned.jfrStartedAt) / 1_000_000L;
        try {
            if (now - owned.jfrStartedAt >= JFR_KEEP_NANOS) {
                Path file = jfrFile();
                recording.dump(file);
                recording.close();
                LOGGER.error("dimblend watchdog: JFR covering {}ms hang exported to {}", coveredMillis, file);
                this.announce(owned.server, "JFR covering " + coveredMillis + "ms hang exported: " + file);
            } else {
                recording.close();
            }
        } catch (Throwable t) {
            LOGGER.error("dimblend watchdog: JFR finalize failed (covered {}ms)", coveredMillis, t);
            try {
                recording.close();
            } catch (Throwable ignored) {
                // already closed or unreleasable; nothing more to do
            }
        }
    }

    /** Dumps the running recording to a file without stopping it; survives process kills. */
    private void snapshotJfr(Session owned) {
        Recording recording = owned.jfr;
        if (recording == null) {
            return;
        }
        try {
            Path file = jfrFile();
            recording.dump(file);
            LOGGER.error("dimblend watchdog: JFR snapshot taken while the hang continues: {}", file);
        } catch (Throwable t) {
            LOGGER.error("dimblend watchdog: JFR snapshot failed", t);
        }
    }

    private static Path jfrFile() throws IOException {
        Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
        Files.createDirectories(dir);
        return dir.resolve("dimblend-jfr-" + STAMP.format(LocalDateTime.now()) + ".jfr");
    }

    /** Announces a diagnostic artifact path in chat to permission-2 players. */
    private static void announce(MinecraftServer server, String message) {
        // Recovery (the only announce site) implies the server thread is alive again,
        // so the queued task runs on the next tick. After shutdown the queue simply
        // never drains, which is harmless.
        try {
            // executeIfPossible, not execute: MinecraftServer overrides
            // scheduleExecutables() with !isStopped() (MinecraftServer.java:1396), so
            // a plain execute() from this daemon runs INLINE on the watchdog thread
            // once the server is stopping — off-thread player access. executeIfPossible
            // throws RejectedExecutionException before that (MinecraftServer.java:1401).
            server.executeIfPossible(() -> {
                Component text = Component.literal("[dimblend] " + message).withStyle(ChatFormatting.GOLD);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.hasPermissions(2)) {
                        player.sendSystemMessage(text);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Server shutting down: the artifact is already on disk and logged; the
            // chat hint is best-effort and must never mark the finalize (which already
            // succeeded) as failed.
            LOGGER.info("dimblend watchdog: chat announcement skipped (server shutting down)");
        }
    }

    private void dump(Session session, long silentMillis) {
        MinecraftServer server = session.server;
        String summary = DimBlend.monitor().lastSampleSummary();
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("dimblend hang watchdog: no completed server tick for ").append(silentMillis).append("ms\n");
        out.append("dumped at  ").append(LocalDateTime.now()).append('\n');
        out.append("currently saving  ").append(server.isCurrentlySaving()).append('\n');
        if (summary != null) {
            out.append("last engine sample  ").append(summary).append('\n');
        }
        ThreadMXBean beans = ManagementFactory.getThreadMXBean();
        long[] deadlocked = beans.findDeadlockedThreads();
        if (deadlocked == null || deadlocked.length == 0) {
            out.append("deadlock detection  none\n");
        } else {
            out.append("deadlock detection  DEADLOCKED THREADS: ").append(Arrays.toString(deadlocked)).append('\n');
        }
        out.append('\n');
        ThreadInfo[] infos = beans.dumpAllThreads(true, true);
        for (ThreadInfo info : infos) {
            out.append(info.getThreadName()).append(" id=").append(info.getThreadId())
                    .append(" state=").append(info.getThreadState()).append('\n');
            if (info.getLockName() != null) {
                out.append("  waiting on ").append(info.getLockName());
                if (info.getLockOwnerName() != null) {
                    out.append("  held by ").append(info.getLockOwnerName());
                }
                out.append('\n');
            }
            StackTraceElement[] frames = info.getStackTrace();
            int depth = Math.min(frames.length, 64);
            for (int i = 0; i < depth; i++) {
                out.append("  at ").append(frames[i]).append('\n');
            }
            for (MonitorInfo m : info.getLockedMonitors()) {
                out.append("  locked monitor ").append(m).append(" at frame ")
                        .append(m.getLockedStackDepth()).append('\n');
            }
            for (LockInfo l : info.getLockedSynchronizers()) {
                out.append("  locked sync ").append(l).append('\n');
            }
            out.append('\n');
        }
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("dimblend-hang-" + STAMP.format(LocalDateTime.now()) + ".txt");
            Files.writeString(file, out, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            LOGGER.error("dimblend watchdog: server thread hung for {}ms; thread dump at {} ({} threads)",
                    silentMillis, file, infos.length);
        } catch (IOException e) {
            LOGGER.error("dimblend watchdog: failed to write thread dump", e);
        }
    }
}
