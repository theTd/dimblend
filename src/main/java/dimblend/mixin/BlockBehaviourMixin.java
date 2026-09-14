package dimblend.mixin;

import com.simibubi.create.content.trains.track.TrackBlock;
import dimblend.DimBlendRegistries;
import dimblend.compat.CorridorTrackProtector;
import dimblend.worldgen.RegionBoundaryWallProtector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the worldgen corridor track row unminable with bedrock feel: no crack
 * progress on either side. Vanilla bedrock achieves this with destroyTime -1,
 * which {@code BlockBehaviour#getDestroyProgress} maps to 0; returning 0 here
 * for corridor tracks reproduces that exactly (client progress never
 * accumulates, so no crack animation or sounds).
 *
 * <p>Guards run cheap and in order: getDestroyProgress is consulted every tick
 * for every block a player is mining, on both client and server. The
 * TrackBlock instanceof check comes first so non-track blocks pay one
 * comparison; the coordinate check is two int comparisons via
 * {@link CorridorTrackProtector#isCorridorTrackPos}.
 *
 * <p>Companion server-side enforcement lives in
 * {@link dimblend.compat.CorridorTrackProtector} and
 * {@link dimblend.worldgen.RegionBoundaryWallProtector} (BreakEvent, explosion,
 * piston). This mixin only affects the digging-feel path. Wall protection uses
 * the same {@link RegionBoundaryWallProtector#isProtectedWall} predicate as the
 * server events (rotating dimension + null_block + boundary column), so BOP End
 * Corruption trunks in End bands stay mineable.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void dimblend$corridorTrackDestroyProgress(
            BlockState state, Player player, BlockGetter level, BlockPos pos,
            CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof TrackBlock)) {
            return;
        }
        if (!(level instanceof Level world) || world.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        if (CorridorTrackProtector.isCorridorTrackPos(pos)) {
            cir.setReturnValue(0f);
        }
    }

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void dimblend$boundaryWallDestroyProgress(
            BlockState state, Player player, BlockGetter level, BlockPos pos,
            CallbackInfoReturnable<Float> cir) {
        if (!RegionBoundaryWallProtector.isWallBlock(state)) {
            return;
        }
        if (RegionBoundaryWallProtector.isProtectedWall(level, pos, state)) {
            cir.setReturnValue(0f);
        }
    }
}
