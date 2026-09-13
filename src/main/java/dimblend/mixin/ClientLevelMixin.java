package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientTimeLock;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-player time lock for the rotating dimension. Re-applied every client
 * tick after the vanilla time advance, so the server's periodic time sync
 * packets cannot fight the lock.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "tickTime", at = @At("RETURN"))
    private void dimblend$applyTimeLock(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (self.dimension() == DimBlendRegistries.ROTATING_LEVEL && ClientTimeLock.active()) {
            self.setDayTime(ClientTimeLock.currentDayTime());
        }
    }
}
