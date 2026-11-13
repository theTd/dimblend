package dimblend.purge;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.LongPredicate;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.IOWorker;

/**
 * Decouples {@link RegionPurgeController} from Sable classloading when Sable is not installed.
 * Only {@link RegionSablePurger} touches {@code dev.ryanhcode.sable.*} classes.
 */
interface SablePurgeBridge {

    /**
     * Releases force-load tickets on unmanned Sable chains outside the keep window once they remain
     * continuously eligible for {@code idleTicks}.
     *
     * @return number of chains whose force-load tickets were removed this scan
     */
    int releaseRemoteForceLoads(
            ServerLevel level,
            int[] centers,
            int keepChunks,
            long nowTick,
            long idleTicks,
            LongPredicate inPlotGrid);

    /**
     * Removes unloaded Sable sub-levels whose canonical {@code pose.position} belongs to
     * {@code (regionX, regionZ)} (or whose chain has no remaining terrain region), clears their
     * plot occupancy bit and tracking points, and prunes empty {@code .slvlr}/{@code .slvls} files.
     *
     * @return number of sub-levels removed
     */
    int purgeRegion(
            ServerLevel level,
            int regionX,
            int regionZ,
            LongSet terrainRegionsRemaining,
            @Nullable IOWorker entityWorker,
            @Nullable IOWorker poiWorker);

    /** Clears continuous idle state on server stop, disable, or {@code /save-off}. */
    void clear();
}
