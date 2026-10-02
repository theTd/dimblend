package dimblend.radio.mixin.client;

import dimblend.radio.acoustics.AcousticPaletteVersion;
import dimblend.radio.acoustics.AcousticSceneChanges;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PalettedContainer.class)
public abstract class AcousticPaletteVersionMixin implements AcousticPaletteVersion {
    @Unique private long dimblend$acousticVersion;
    @Unique private long dimblend$watchGeneration, dimblend$watchSection;

    @Unique private void dimblend$invalidateAcoustics() {
        dimblend$acousticVersion++;
        if (dimblend$watchGeneration != 0) AcousticSceneChanges.paletteChanged(dimblend$watchGeneration, dimblend$watchSection);
    }

    // Both public getAndSet variants delegate to this indexed overload in 1.21.1.
    @Inject(method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private void dimblend$changed(int index, Object value, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() != value) dimblend$invalidateAcoustics();
    }

    @Inject(method = "set(ILjava/lang/Object;)V", at = @At("RETURN"))
    private void dimblend$set(int index, Object value, CallbackInfo ci) { dimblend$invalidateAcoustics(); }

    @Inject(method = "read", at = @At("RETURN"))
    private void dimblend$read(FriendlyByteBuf buffer, CallbackInfo ci) { dimblend$invalidateAcoustics(); }

    @Override public long dimblend$acousticVersion() { return dimblend$acousticVersion; }

    @Override public void dimblend$observeAcoustics(long generation, long section) {
        dimblend$watchGeneration = generation;
        dimblend$watchSection = section;
    }
}
