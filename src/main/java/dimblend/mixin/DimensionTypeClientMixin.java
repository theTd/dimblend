package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientTimeLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DimensionType.class)
public abstract class DimensionTypeClientMixin {
    @Inject(method = "hasFixedTime", at = @At("HEAD"), cancellable = true)
    private void dimblend$clientBandFixedTime(CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != null
                && level.dimension() == DimBlendRegistries.ROTATING_LEVEL
                && level.dimensionType() == (Object) this
                && ClientTimeLock.active()) {
            cir.setReturnValue(true);
        }
    }
}
