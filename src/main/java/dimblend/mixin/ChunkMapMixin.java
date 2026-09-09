package dimblend.mixin;

import dimblend.diagnostics.ShutdownUnloadGuard;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Escapes the {@code stopServer} unload livelock: after {@link ShutdownUnloadGuard}'s timeout,
 * report no work and skip further unload processing. Does not clear {@code pendingUnloads} /
 * {@code toDrop} — holders with a non-zero generation refcount stay untouched.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "hasWork", at = @At("HEAD"), cancellable = true)
    private void dimblend$abortStuckUnloadHasWork(CallbackInfoReturnable<Boolean> cir) {
        if (ShutdownUnloadGuard.shouldAbort(this.level)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "processUnloads", at = @At("HEAD"), cancellable = true)
    private void dimblend$abortStuckUnloadProcess(BooleanSupplier hasTime, CallbackInfo ci) {
        if (ShutdownUnloadGuard.shouldAbort(this.level)) {
            ci.cancel();
        }
    }
}
