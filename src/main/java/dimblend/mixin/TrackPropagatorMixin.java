package dimblend.mixin;

import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import dimblend.DimBlendRegistries;
import dimblend.compat.CreateTrackGraphCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Bounds {@link TrackPropagator#onRailAdded}'s connected-track walk to already-loaded
 * chunks inside the rotating dimension only.
 *
 * <p>Why: the track corridor is an effectively endless line along X. When a frontier
 * chunk is stitched, onRailAdded's frontier walk (emergencyExit budget = 1000 pops)
 * follows the corridor into not-yet-loaded chunks; every {@code getBlockState} there
 * sync-loads + generates a fresh corridor chunk on the server thread (the 19-wide vault
 * carve makes generation expensive). Observed as 15-35s full server-thread stalls between
 * ticks ("Can't keep up! Running 34000ms behind") while spark's tick stats stayed clean,
 * because the stall runs in TickTask processing outside the ServerTickEvent span.
 *
 * <p>The redirect returns no connected ends when the walked location's 4-neighborhood
 * chunks are not all loaded, so the walk terminates at the edge of the loaded window
 * instead of forcing loads. Graph linking across the frontier completes when the
 * neighbor chunk loads and is stitched in turn (CreateTrackGraphCompat re-stitches
 * every disk-loaded corridor chunk; rotating graphs are not persisted by Create).
 * Correctness note: {@code walkConnectedTracks} only reads blockstates within +/-1
 * block of the location, so a fully-loaded 4-neighborhood guarantees every read stays
 * inside loaded chunks. The corridor track sits at z=0, i.e. on the border between
 * z-chunks -1 and 0, which is why the z-neighbors are part of the check.
 *
 * <p>Non-rotating dimensions are passed through untouched. Create upgrades that drift
 * the {@code walkConnectedTracks} descriptor make this redirect fail loudly at apply
 * time (require = 1) rather than silently reintroducing the stalls — re-verify the
 * descriptor against the new Create jar when that happens.
 */
@Mixin(value = TrackPropagator.class, remap = false)
public abstract class TrackPropagatorMixin {

    @Redirect(
            method = "onRailAdded",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/track/ITrackBlock;"
                            + "walkConnectedTracks(Lnet/minecraft/world/level/BlockGetter;"
                            + "Lcom/simibubi/create/content/trains/graph/TrackNodeLocation;Z)"
                            + "Ljava/util/Collection;"
            ),
            remap = false,
            require = 1
    )
    private static Collection<TrackNodeLocation.DiscoveredLocation> dimblend$boundWalkConnectedTracks(
            BlockGetter level, TrackNodeLocation location, boolean ignoreTurns
    ) {
        if (level instanceof ServerLevel serverLevel
                && serverLevel.dimension() == DimBlendRegistries.ROTATING_LEVEL) {
            BlockPos pos = BlockPos.containing(location.getLocation());
            if (!CreateTrackGraphCompat.neighborsLoaded(serverLevel, new ChunkPos(pos))) {
                // MUST be mutable: callers mutate the returned collection in place
                // (TrackPropagator.onRailAdded does ends.remove(entry.prevNode));
                // an immutable list here crashes with UnsupportedOperationException.
                return new ArrayList<>();
            }
        }
        return ITrackBlock.walkConnectedTracks(level, location, ignoreTurns);
    }
}
