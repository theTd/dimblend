package dimblend.mixin.voidscape;

import dimblend.client.ClientBandLane;
import dimblend.compat.VoidscapeBand;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player-centric light/fog/gamma in the rotating voidscape lane. The client
 * mixin set only skips dedicated servers — integrated/LAN hosts still apply
 * this transform to the shared {@code LevelUtil} class, so the fallback must
 * also check {@code level.isClientSide()}. Skipped when a position is already
 * on the VoidscapeBand stack; that path belongs to {@link LevelUtilMixin}.
 */
@Mixin(targets = "tamaized.voidscape.util.LevelUtil", remap = false)
public abstract class LevelUtilClientMixin {
    @Inject(method = "isInVoidDimension", at = @At("HEAD"), cancellable = true)
    private void dimblend$clientLaneFallback(@Nullable Level level, CallbackInfoReturnable<Boolean> cir) {
        if (VoidscapeBand.contextPos() != null) {
            return;
        }
        if (level != null
                && level.isClientSide()
                && VoidscapeBand.isRotating(level)
                && ClientBandLane.voidscape()) {
            cir.setReturnValue(true);
        }
    }
}
