package dimblend.mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla {@code completeFuture} yield-loops on the completing thread when the slot was
 * already completed unsuccessfully (ticket demotion / UNLOADED). That freezes the thread
 * that finished the step — worldgen for most statuses, main for FULL. Abort the spin;
 * the unsuccessful completion stays in the slot. The double-success {@code ISE} above
 * this call is left intact.
 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderMixin {
    @Inject(
            method = "completeFuture",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"),
            cancellable = true
    )
    private void dimblend$abortSpinOnFailedSlot(CallbackInfo ci) {
        ci.cancel();
    }
}
