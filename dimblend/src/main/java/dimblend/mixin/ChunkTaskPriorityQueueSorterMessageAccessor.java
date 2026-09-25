package dimblend.mixin;

import java.util.function.Function;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkTaskPriorityQueueSorter.Message.class)
public interface ChunkTaskPriorityQueueSorterMessageAccessor {
    @Invoker("<init>")
    static <T> ChunkTaskPriorityQueueSorter.Message<T> dimblend$new(
            Function<ProcessorHandle<Unit>, T> task,
            long pos,
            IntSupplier level
    ) {
        throw new AssertionError();
    }
}
