package dimblend.mixin;

import dimblend.diagnostics.ShutdownUnloadGuard;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Escapes the {@code stopServer} unload livelock: after {@link ShutdownUnloadGuard}'s timeout,
 * report no work and skip further unload processing. Does not clear {@code pendingUnloads} /
 * {@code toDrop} — holders with a non-zero generation refcount stay untouched.
 *
 * <p>The timeout must also be re-evaluated <em>inside</em> {@code processUnloads}: with
 * {@code hasTime == () -> true} its drain loop never ends while a holder with a non-zero
 * refcount keeps re-queuing its {@code scheduleUnload} task, so the main thread never returns to
 * the entry checks (and never runs the main-thread tasks those refcounts are waiting on).
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private Queue<Runnable> unloadQueue;

    @Shadow
    @Final
    private LongSet toDrop;

    /** Polls left in the current {@code processUnloads} call; unlimited unless the server is stopping. */
    @Unique
    private int dimblend$drainBudget = Integer.MAX_VALUE;

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

    /**
     * Bounds the drain loop to one pass over what is queued right now while the server stops.
     * {@code stopServer} calls {@code tick(() -> true)}, and a holder whose generation refcount is
     * still positive re-queues its unload task on every run, so an unbounded loop never returns.
     * Returning lets {@code waitUntilNextTick} run the main-thread tasks (pending chunk loads,
     * ticket updates) those refcounts wait on; the re-queued tasks are retried on the next pass.
     */
    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void dimblend$boundDrainWhileStopping(BooleanSupplier hasTime, CallbackInfo ci) {
        this.dimblend$drainBudget = ShutdownUnloadGuard.isStopping(this.level)
                ? this.unloadQueue.size() + this.toDrop.size() + 1
                : Integer.MAX_VALUE;
    }

    /**
     * Per-poll timeout and budget check for the unload drain loop. Once aborting or out of
     * budget, the queue reads as empty so the loop exits even while re-queued unload tasks keep
     * refilling it.
     */
    @Redirect(
            method = "processUnloads",
            at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;")
    )
    private Object dimblend$stopDrainWhenAborting(Queue<Runnable> unloadQueue) {
        if (ShutdownUnloadGuard.shouldAbort(this.level) || this.dimblend$drainBudget-- <= 0) {
            return null;
        }
        return unloadQueue.poll();
    }
}
