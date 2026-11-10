package dimblend.experience.mixin;

import dimblend.experience.compat.compass.OriginCompass;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CompassItem.class)
public abstract class OriginCompassMixin {
    // The vanilla target is static; its injection handler must also be private static.
    @Inject(method = "getSpawnPosition(Lnet/minecraft/world/level/Level;)Lnet/minecraft/core/GlobalPos;",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void dimblend$ordinaryCompassPointsToOrigin(Level level, CallbackInfoReturnable<GlobalPos> cir) {
        if (OriginCompass.isRotating(level)) {
            cir.setReturnValue(OriginCompass.target(level));
        }
    }
}
