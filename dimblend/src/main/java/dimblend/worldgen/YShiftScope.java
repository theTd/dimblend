package dimblend.worldgen;

import java.util.ArrayDeque;
import java.util.function.Supplier;

/**
 * Thread-local Y offset while a y-shifted delegate is generating.
 * {@link YShiftedNoiseChunkGenerator} (Twilight) and {@link YShiftedChunkGenerator}
 * (Voidscape) both push their offset here. Used by Twilight Forest mixins that read
 * hardcoded sea-level Y, and by {@code WorldGenerationContextMixin} when the
 * generating instance is the inner Voidscape generator rather than the wrapper.
 */
public final class YShiftScope {
    private static final ThreadLocal<ArrayDeque<Integer>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private YShiftScope() {
    }

    public static void push(int offset) {
        STACK.get().push(offset);
    }

    public static void pop() {
        ArrayDeque<Integer> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    public static int current() {
        Integer offset = STACK.get().peek();
        return offset == null ? 0 : offset;
    }

    public static void run(int offset, Runnable body) {
        push(offset);
        try {
            body.run();
        } finally {
            pop();
        }
    }

    public static <T> T get(int offset, Supplier<T> body) {
        push(offset);
        try {
            return body.get();
        } finally {
            pop();
        }
    }
}
