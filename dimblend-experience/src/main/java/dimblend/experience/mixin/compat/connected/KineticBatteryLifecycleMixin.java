package dimblend.experience.mixin.compat.connected;

import com.hlysine.create_connected.content.kineticbattery.KineticBatteryBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A conflict can destroy the source in super.tick before CC writes its new charge stage. */
@Mixin(KineticBatteryBlockEntity.class)
public abstract class KineticBatteryLifecycleMixin {
    @Inject(method = "updateLevel()V", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$onlyUpdateCurrentBattery(CallbackInfo ci) {
        var self = (KineticBatteryBlockEntity) (Object) this;
        var level = self.getLevel();
        // Also protect a replacement battery at the same position from this stale instance.
        if (level == null || self.isRemoved() || level.getBlockEntity(self.getBlockPos()) != self) {
            ci.cancel();
        }
    }
}
