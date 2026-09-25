package dimblend.diagnostics;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Breaks the vanilla {@code stopServer} unload livelock after a timeout.
 *
 * <p>{@link net.minecraft.server.MinecraftServer#stopServer()} spins on
 * {@code chunkMap.hasWork()} and re-queues {@code scheduleUnload} forever when a holder never
 * becomes ready for saving. Gated on {@code !isRunning() && isCurrentlySaving()} so a long
 * in-game autosave / {@code /save-all} never trips it — {@code isRunning()} flips false at halt,
 * before the save window. After {@link #TIMEOUT_NANOS} in that window, {@link #shouldAbort}
 * flips true so mixins make {@code hasWork} report idle and skip {@code processUnloads}.
 *
 * <p><b>Data-loss semantics:</b> once aborting, {@code isReadyForSaving()} is forced true for
 * every holder, so {@code saveAllChunks(true)} and {@code scheduleUnload} proceed with whatever
 * data the holder has. Mid-generation chunks are NOT skipped: their {@code ProtoChunk} is
 * serialized at its current persisted status and written to disk (vanilla-format partial chunk;
 * generation resumes from that status on next load). Blocks placed this session inside a chunk
 * that never reached {@code FULL} may be missing from that write; chunks with no materialized
 * data at all ({@code getLatestChunk() == null}) yield nothing and are simply not written.
 * Nothing already flushed by the pre-quit autosave is affected.
 */
public final class ShutdownUnloadGuard {
    /** Wall time inside the save window before unload is abandoned so shutdown can finish. */
    static final long TIMEOUT_NANOS = 30_000_000_000L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SAVING_SINCE = new AtomicLong();
    private static final AtomicBoolean ABORTING = new AtomicBoolean();
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private ShutdownUnloadGuard() {
    }

    /**
     * True once the server has been {@link MinecraftServer#isCurrentlySaving() saving} longer
     * than {@link #TIMEOUT_NANOS}. Resets automatically when saving ends.
     */
    public static boolean shouldAbort(ServerLevel level) {
        MinecraftServer server = level.getServer();
        // Halt flips isRunning() false before stopServer sets isSaving; both must hold so a
        // long in-game save (autosave / save-all) never trips the abort.
        if (server == null || server.isRunning() || !server.isCurrentlySaving()) {
            SAVING_SINCE.set(0L);
            ABORTING.set(false);
            LOGGED.set(false);
            return false;
        }
        if (ABORTING.get()) {
            return true;
        }
        long since = SAVING_SINCE.updateAndGet(v -> v == 0L ? System.nanoTime() : v);
        if (System.nanoTime() - since <= TIMEOUT_NANOS) {
            return false;
        }
        ABORTING.set(true);
        if (LOGGED.compareAndSet(false, true)) {
            LOGGER.error(
                    "dimblend: chunk unload stuck for {}s during server stop; abandoning in-flight chunks so shutdown can finish (those chunks may not be saved)",
                    TIMEOUT_NANOS / 1_000_000_000L);
        }
        return true;
    }

    /** True after {@link #shouldAbort} has fired for the current save window. */
    public static boolean isAborting() {
        return ABORTING.get();
    }
}
