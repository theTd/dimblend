package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VoidscapeBandStackTest {

    @SuppressWarnings("unchecked")
    private static ArrayDeque<BlockPos> currentThreadStack() throws Exception {
        Field field = VoidscapeBand.class.getDeclaredField("POSITION");
        field.setAccessible(true);
        ThreadLocal<ArrayDeque<BlockPos>> tl = (ThreadLocal<ArrayDeque<BlockPos>>) field.get(null);
        return tl.get();
    }

    @Test
    void exitKeepsThreadLocalDequeInsteadOfReallocatingEveryCall() throws Exception {
        AtomicReference<ArrayDeque<BlockPos>> firstSeen = new AtomicReference<>();
        AtomicReference<ArrayDeque<BlockPos>> secondSeen = new AtomicReference<>();

        VoidscapeBand.run(null, BlockPos.ZERO, () -> {
            try {
                firstSeen.set(currentThreadStack());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(firstSeen.get().isEmpty(), "stack must be emptied on exit");
        assertNull(VoidscapeBand.contextPos(), "empty stack must report null contextPos");

        VoidscapeBand.run(null, new BlockPos(16, 64, 16), () -> {
            try {
                secondSeen.set(currentThreadStack());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertSame(firstSeen.get(), secondSeen.get(),
                "VoidscapeBand must reuse the thread's ArrayDeque like ServerBandTime instead of removing ThreadLocal on every exit");
        assertTrue(secondSeen.get().isEmpty(), "stack must be emptied after second run");
        assertNull(VoidscapeBand.contextPos());
    }

    @Test
    void runUnwindsStackWhenActionThrows() throws Exception {
        assertThrows(IllegalStateException.class, () ->
                VoidscapeBand.run(null, BlockPos.ZERO, () -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals(0, currentThreadStack().size(), "stack must stay balanced after exception");
        assertNull(VoidscapeBand.contextPos());
    }
}
