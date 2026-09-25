package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientTimeLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read-side half of the per-player time lock. The celestial sphere does not
 * go through {@link net.minecraft.world.level.Level#getDayTime}: vanilla reads
 * the day time straight from the level data (see LevelAccessor.dayTime(), used
 * by LevelTimeAccess.getTimeOfDay for the sun/moon/stars). Shadowing this
 * getter pins every reader — including the server's periodic time sync
 * packets, which would otherwise keep dragging the sun back to the real time.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ClientLevelDataMixin {
    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void dimblend$readLockedDayTime(CallbackInfoReturnable<Long> cir) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getLevelData() == (Object) this
                && level.dimension() == DimBlendRegistries.ROTATING_LEVEL && ClientTimeLock.active()) {
            cir.setReturnValue(ClientTimeLock.currentDayTime());
        }
    }
}
