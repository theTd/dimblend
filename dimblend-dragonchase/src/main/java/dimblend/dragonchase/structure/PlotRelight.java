package dimblend.dragonchase.structure;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Relights a Sable plot after this module writes blocks into it.
 *
 * <p><b>The defect, in Sable's own terms.</b> Every plot has its own {@code LevelLightEngine}
 * ({@code ServerLevelPlot}'s constructor: {@code new LevelLightEngine(...)}), separate from the
 * parent world's. Assembly — and every later {@code Level#setBlock} on a plot position — feeds the
 * light engine of the chunk's <i>level</i>, which is the parent {@code ServerLevel}, so the plot's
 * engine still holds what it computed for an empty chunk. The structure then renders and reloads
 * fully black, and editing a block later cannot repair it.
 *
 * <p><b>Why relighting the plot engine is enough.</b> {@code LevelLightEngine#propagateLightSources}
 * reads blocks through {@code LightChunkGetter#getChunkForLighting}, which Sable redirects to the
 * plot chunks ({@code ServerChunkCacheMixin#getChunkForLighting}), so running it on the plot engine
 * fills that engine from the real blocks.
 *
 * <p><b>Order matters.</b> The chunk must be marked "not light-correct" first, because
 * {@code ServerLevelPlot}'s own {@code correctLight} returns early on a chunk it believes is
 * correct; then the light sources are re-propagated, then the queue is drained. This is the same
 * sequence {@code ServerLevelPlot#load} runs when it restores a saved plot.
 *
 * <p><b>Why a copy lives here.</b> {@code dimblend-experience} has the identical helper, but this
 * module must not import that module (plan.md §1: dragonchase depends on {@code dimblend.api}
 * only), so the same ~60 lines are kept on this side rather than making the cars depend on the
 * experience module. Reported to the orchestrator as a candidate for a {@code dimblend.api}
 * exposure.
 */
public final class PlotRelight {

    /**
     * Upper bound on light-pass rounds. Vanilla's own loops are unbounded, but this runs inside the
     * server tick with a player waiting for a car to appear, so a pathological queue must not stall
     * the world: after this many rounds the remaining work stays queued and the plot's own
     * {@code tick()} drains it on later ticks.
     */
    private static final int MAX_DRAIN_PASSES = 64;

    private PlotRelight() {
    }

    /**
     * Relights every loaded chunk of {@code car}'s plot.
     *
     * @return the number of chunks relit; 0 when the plot cannot be read
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
     * Relights the one chunk that holds {@code plotPos}.
     *
     * <p>Used after writing into an already-assembled plot (the kill rewards), where the block
     * lands in a chunk whose light was computed before the write.
     */
    public static boolean relightChunkAt(ServerSubLevel car, BlockPos plotPos) {
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
        //    real blocks.
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
}
