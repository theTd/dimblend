package dimblend.mixin;

import com.simibubi.create.content.trains.track.TrackBlock;
import dimblend.DimBlendRegistries;
import dimblend.compat.CorridorTrackProtector;
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
 * {@link dimblend.compat.CorridorTrackProtector} (BlockEvent.BreakEvent and
 * ExplosionEvent.Detonate); this mixin only affects the digging-feel path.
 * Non-corridor Create tracks everywhere, and all blocks in other dimensions,
 * pass through untouched.
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
}
