package dimblend.experience.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Relights a Sable sub-level's plot after its blocks are written.
 *
 * <p><b>The defect this exists for.</b> Sable gives every plot its <i>own</i>
 * {@code LevelLightEngine} ({@code ServerLevelPlot} constructor: {@code new LevelLightEngine(...)}),
 * separate from the parent world's. Assembly copies the blocks into a plot chunk with
 * {@code LevelChunk#setBlockState}, and that call feeds the light engine of the chunk's own
 * <i>level</i> — which is the parent {@code ServerLevel}, not the plot. So the plot's engine never
 * learns about the blocks: it still holds the data it computed while the chunk was <b>empty</b>.
 * Every light packet and the plot's own save read that empty engine, so the assembled structure
 * renders and reloads fully black, and editing a block later cannot repair it — the edit goes to
 * the parent engine again while the packets keep coming from the plot engine. The user reported
 * exactly this: "预生成的所有 sable 结构会有光照错误（全黑），且没法通过更新其上部分方块恢复".
 *
 * <p><b>Why relighting the plot engine is enough.</b> {@code LevelLightEngine#propagateLightSources}
 * reads the blocks through {@code LightChunkGetter#getChunkForLighting}, and Sable redirects that
 * call to its plot chunks ({@code ServerChunkCacheMixin#getChunkForLighting}). So running it on the
 * plot engine fills that engine from the real, already-copied blocks, instead of from the empty
 * chunk it was initialized against.
 *
 * <p><b>Order matters.</b> The chunk must be marked "not light-correct" first, because
 * {@code ServerLevelPlot}'s own {@code correctLight} returns early on a chunk it believes is
 * correct; then the light sources are re-propagated, then the engine's queue is drained. The same
 * sequence is what {@code ServerLevelPlot#load} does when it restores a saved plot, which is why a
 * reloaded plot is not black while a freshly assembled one is.
 */
public final class SablePlotRelight {

    /**
     * Relights every chunk of {@code car}'s plot.
     *
     * <p>Best effort: an exception is swallowed by the caller's normal error handling, because a
     * structure that assembled correctly must not be destroyed by a lighting failure. Returns the
     * number of chunks relit, which the caller logs.
     */
    public static int relight(ServerLevel level, ServerSubLevel car) {
        ServerLevelPlot plot = asPlot(car);
        if (plot == null) {
            return 0;
        }
        LevelLightEngine engine = plot.getLightEngine();
        if (engine == null) {
            return 0;
        }

        int relit = 0;
        for (var holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) {
                relightChunk(engine, chunk);
                relit++;
            }
        }
        drain(engine);
        return relit;
    }

    /**
     * Relights the one chunk that holds the given block position.
     *
     * <p>Assembly writes into a single chunk, so this is the narrow version the call sites use.
     */
    public static boolean relightChunkAt(ServerSubLevel car, net.minecraft.core.BlockPos plotPos) {
        ServerLevelPlot plot = asPlot(car);
        if (plot == null) {
            return false;
        }
        LevelLightEngine engine = plot.getLightEngine();
        LevelChunk chunk = plot.getChunk(new ChunkPos(plotPos));
        if (engine == null || chunk == null) {
            return false;
        }
        relightChunk(engine, chunk);
        drain(engine);
        return true;
    }

    private static void relightChunk(LevelLightEngine engine, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        // 1. The chunk must stop claiming its light is correct, or correctLight returns early.
        chunk.setLightCorrect(false);
        // 2. Re-enable light for the column and let the engines pull the light sources out of the
        //    real blocks. propagateLightSources sets light-enabled itself for both layers.
        engine.propagateLightSources(pos);
        // 3. Mark sections that hold blocks as non-empty so the engines do not treat them as air.
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            if (!chunk.getSection(index).hasOnlyAir()) {
                SectionPos section = SectionPos.of(pos, chunk.getSectionYFromSectionIndex(index));
                engine.updateSectionStatus(section, false);
            }
        }
        chunk.setLightCorrect(true);
    }

    /** Runs the queued light work until both layers are quiet. */
    private static void drain(LevelLightEngine engine) {
        int guard = 0;
        do {
            engine.runLightUpdates();
            guard++;
        } while (engine.hasLightWork() && guard < MAX_DRAIN_PASSES);
    }

    /**
     * Upper bound on light-pass rounds. Vanilla's own loops are unbounded, but this runs inside the
     * server tick with a player waiting for a wagon to appear, so a pathological queue must not
     * stall the world: after this many rounds the remaining work stays queued and the plot's own
     * {@code tick()} drains it on later ticks.
     */
    private static final int MAX_DRAIN_PASSES = 64;

    private static ServerLevelPlot asPlot(ServerSubLevel car) {
        if (car == null || car.isRemoved()) {
            return null;
        }
        try {
            if (car.getPlot() instanceof ServerLevelPlot plot) {
                return plot;
            }
        } catch (ClassCastException | NullPointerException ignored) {
            // Not a server-side plot.
        }
        return null;
    }

    private SablePlotRelight() {
    }
}