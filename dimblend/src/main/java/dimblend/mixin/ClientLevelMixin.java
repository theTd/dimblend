package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.DimBlendRegistries;
import dimblend.client.ClientSkyTimeTrace;
import dimblend.client.ClientTimeLock;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Write-side half of the per-player time lock: while a band lock is active,
 * the client behaves exactly as if the doDaylightCycle gamerule were false —
 * it never advances the day time on its own. Vanilla {@code tickTime} reads
 * {@code getDayTime} to increment, and that getter is shadowed to the pin, so
 * letting it run would smash the live clock. Game time still advances so
 * clouds and animations keep moving. The day-time <em>field</em> is left
 * alone: read-side pinning lives in ClientLevelDataMixin, and vanilla time
 * sync plus the unlock packet keep the field on the world clock.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @WrapOperation(method = "tickTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"), require = 1, allow = 1)
    private void dimblend$traceNaturalAdvance(ClientLevel self, long time, Operation<Void> original) {
        if (!ClientSkyTimeTrace.enabled()) {
            original.call(self, time);
            return;
        }
        long before = self.getDayTime();
        original.call(self, time);
        ClientSkyTimeTrace.naturalAdvance(self, before, self.getDayTime());
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void dimblend$freezeDaylightCycle(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (self.dimension() == DimBlendRegistries.ROTATING_LEVEL && ClientTimeLock.active()) {
            ci.cancel();
            self.setGameTime(self.getGameTime() + 1L);
            ClientTimeLock.advanceTick();
        }
    }
}
