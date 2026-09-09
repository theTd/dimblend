package dimblend.mixin;

import dimblend.diagnostics.ShutdownUnloadGuard;
import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Unblocks {@code saveAllChunks}'s {@code managedBlock(isReadyForSaving)} and
 * {@code scheduleUnload}'s ready check after {@link ShutdownUnloadGuard} times out.
 *
 * <p><b>Consequence:</b> holders still mid-generation are treated as ready, so their
 * {@code getLatestChunk()} result (possibly a partial {@code ProtoChunk}) is serialized and
 * written to disk at its current persisted status; generation resumes on next load. A holder
 * with no materialized chunk returns null and writes nothing. Unload maps are not cleared.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Inject(method = "isReadyForSaving", at = @At("HEAD"), cancellable = true)
    private void dimblend$abortStuckUnloadReady(CallbackInfoReturnable<Boolean> cir) {
        if (ShutdownUnloadGuard.isAborting()) {
            cir.setReturnValue(true);
        }
    }
}
