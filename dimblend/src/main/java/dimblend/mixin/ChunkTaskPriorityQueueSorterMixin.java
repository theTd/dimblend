package dimblend.mixin;

import java.util.function.IntSupplier;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla {@code message(Runnable)} is {@code run(); tell(Unit)}. A throw from a raw
 * sorter runnable (worldgen {@code runUntilWait}, light tasks, ticket throttler) skips
 * {@code Unit} and {@code pollTask} never reschedules that processor. FULL conversion
 * uses {@code CompletableFuture.supplyAsync}, whose {@code AsyncSupply.run} already
 * catches supplier throws — this mixin is not that path. It only closes the raw-runnable
 * handshake.
 */
@Mixin(ChunkTaskPriorityQueueSorter.class)
public abstract class ChunkTaskPriorityQueueSorterMixin {
    @Inject(
            method = "message(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void dimblend$releaseMailboxAfterTask(
            Runnable task,
            long pos,
            IntSupplier level,
            CallbackInfoReturnable<ChunkTaskPriorityQueueSorter.Message<Runnable>> cir
    ) {
        cir.setReturnValue(ChunkTaskPriorityQueueSorterMessageAccessor.dimblend$new(
                handle -> () -> {
                    try {
                        task.run();
                    } finally {
                        handle.tell(Unit.INSTANCE);
                    }
                },
                pos,
                level
        ));
    }
}
