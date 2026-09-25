package dimblend.compat;

import com.simibubi.create.content.trains.track.TrackBlock;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.OakTrackCorridor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Bedrock-style protection for the worldgen track corridor: the track row at
 * Z = {@link OakTrackCorridor#CORRIDOR_Z}, Y = {@link OakTrackCorridor#TRACK_Y}
 * in the rotating dimension cannot be broken by non-creative players (by hand
 * or wrench) and survives explosions.
 *
 * <p>Covered paths, all verified against Create 6.0.10-281 / NeoForge 21.1.249:
 * <ul>
 *   <li>Hand breaking: {@code ServerPlayerGameMode.destroyBlock} posts
 *       {@link BlockEvent.BreakEvent} for every game mode and aborts when the
 *       event is canceled.</li>
 *   <li>Wrench removal: Create wrenches route IWrenchable blocks to
 *       {@code IWrenchable.onSneakWrenched}, whose default implementation posts
 *       the same {@link BlockEvent.BreakEvent} and returns without removing the
 *       block when the event is canceled. A single listener therefore covers
 *       both hand and wrench removal. Creative players are intentionally left
 *       alone, matching vanilla bedrock (creative may break bedrock).</li>
 *   <li>Explosions: blocks are filtered out of
 *       {@link ExplosionEvent.Detonate#getAffectedBlocks()}.</li>
 * </ul>
 *
 * <p>Residual gap: {@code Level.destroyBlock} callers that post no event
 * (wither, /setblock, Create drills, other mods' block breakers) can still
 * remove corridor tracks. Closing that would require a deterministic
 * integrity sweep (rewrite missing track cells and re-stitch the graph);
 * deliberately out of scope for now.
 *
 * <p>Tracks placed by players on the same Y/Z row are indistinguishable from
 * worldgen tracks and are protected too, which matches the corridor being
 * treated as bedrock-grade infrastructure.
 */
public final class CorridorTrackProtector {

    private CorridorTrackProtector() {
    }

    public static void onBreakBlock(BlockEvent.BreakEvent event) {
        // Creative mirrors vanilla bedrock: breaking is allowed, so neither
        // hand nor wrench removal is canceled for creative players.
        if (event.getPlayer().isCreative()) {
            return;
        }
        if (isCorridorTrack(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof Level level)
                || level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> isCorridorTrack(level, pos));
    }

    /**
     * True when pos is one of the protected worldgen corridor track cells:
     * the rotating dimension's fixed track row holding a Create-family track.
     */
    public static boolean isCorridorTrack(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof Level world) || world.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        return isCorridorTrackPos(pos) && isTrackBlock(world.getBlockState(pos));
    }

    /** Pure coordinate check for the corridor track row (no block reads). */
    public static boolean isCorridorTrackPos(BlockPos pos) {
        return pos.getY() == OakTrackCorridor.TRACK_Y
                && pos.getZ() == OakTrackCorridor.CORRIDOR_Z;
    }

    private static boolean isTrackBlock(BlockState state) {
        return state.getBlock() instanceof TrackBlock;
    }
}
