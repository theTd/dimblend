package dimblend.mixin;

import com.mojang.datafixers.util.Either;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Region purge access to one IO worker. {@code storage} and {@code pendingWrites} belong to the
 * worker mailbox thread: touch them only inside a task passed to {@link #dimblend$submitTask}.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    @Accessor("storage")
    RegionFileStorage dimblend$getStorage();

    /** Queued writes not yet flushed to a region file; values are the package-private PendingStore. */
    @Accessor("pendingWrites")
    Map<ChunkPos, ?> dimblend$getPendingWrites();

    /** Runs {@code task} on the worker mailbox at FOREGROUND priority, FIFO with loads and stores. */
    @Invoker("submitTask")
    <T> CompletableFuture<T> dimblend$submitTask(Supplier<Either<T, Exception>> task);
}
