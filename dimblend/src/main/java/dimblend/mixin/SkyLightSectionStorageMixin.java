package dimblend.mixin;

import dimblend.compat.VoidscapeSkyLight;
import javax.annotation.Nullable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sky-light reads answer 0 inside the rotating Voidscape lane, the answer
 * Voidscape's own {@code has_skylight: false} dimension gives: vanilla wires sky
 * reads there to a dummy listener that always returns 0. Every consumer of
 * {@code Level.getBrightness}/{@code getRawBrightness} (lightmap, chunk mesher,
 * mob spawn darkness, crop growth, F3) reads through this storage, and the only
 * engine-internal reader is {@code LightEngine.getLightValue(BlockPos)} —
 * propagation itself uses {@code getStoredLevel}, so masking here leaves the
 * engine's own bookkeeping untouched.
 *
 * <p>The matching half — the band's sky data layers are empty, so nothing is
 * saved, sent, or read as sky light by data-layer consumers — lives in
 * {@link LayerLightSectionStorageMixin}. The lane test lives in
 * {@link VoidscapeSkyLight}; it is a no-op outside {@code dimblend:rotating}.
 */
@Mixin(SkyLightSectionStorage.class)
public abstract class SkyLightSectionStorageMixin {
    /** Set from the constructor: light storages are built with the level they light. */
    @Unique
    @Nullable
    private BlockGetter dimblend$voidscapeSkyLevel;

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;)V", at = @At("RETURN"))
    private void dimblend$captureSkyLightLevel(LightChunkGetter chunkSource, CallbackInfo ci) {
        this.dimblend$voidscapeSkyLevel = chunkSource.getLevel();
    }

    @Inject(method = "getLightValue(J)I", at = @At("HEAD"), cancellable = true)
    private void dimblend$voidscapeSkyLightIsZero(long levelPos, CallbackInfoReturnable<Integer> cir) {
        BlockGetter level = this.dimblend$voidscapeSkyLevel;
        if (level != null && VoidscapeSkyLight.isMasked(level, levelPos)) {
            cir.setReturnValue(0);
        }
    }
}
