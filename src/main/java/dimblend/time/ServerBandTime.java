package dimblend.time;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Server-side counterpart of {@link dimblend.client.ClientTimeLock}: while a
 * rotating-dimension tick is attributed to a column, {@code isDay}/{@code isNight}
 * / {@code getDayTime} read this stack instead of the shared {@code skyDarken}.
 */
public final class ServerBandTime {
    private static final ThreadLocal<ArrayDeque<TimeLockTarget>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final long TWILIGHT_DAY_TIME =
            (TimeLockTarget.TWILIGHT_MIN + TimeLockTarget.TWILIGHT_MAX_EXCLUSIVE - 1) / 2;

    private ServerBandTime() {
    }

    public static void push(Level level, BlockPos pos) {
        STACK.get().push(targetAt(level, pos.getX()));
    }

    public static void pop() {
        ArrayDeque<TimeLockTarget> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    public static TimeLockTarget current() {
        TimeLockTarget target = STACK.get().peek();
        return target == null ? TimeLockTarget.NONE : target;
    }

    public static boolean locked() {
        return current().mode() != TimeLockTarget.Mode.NONE;
    }

    public static boolean lockedIsDay() {
        TimeLockTarget target = current();
        return switch (target.mode()) {
            case NONE -> false;
            case TWILIGHT_JITTER -> false;
            case FIXED -> {
                long time = Math.floorMod(target.time(), 24000L);
                yield time < 12000L;
            }
        };
    }

    public static long lockedDayTime() {
        TimeLockTarget target = current();
        if (target.mode() == TimeLockTarget.Mode.TWILIGHT_JITTER) {
            return TWILIGHT_DAY_TIME;
        }
        return target.time();
    }

    public static void run(Level level, BlockPos pos, Runnable body) {
        push(level, pos);
        try {
            body.run();
        } finally {
            pop();
        }
    }

    private static TimeLockTarget targetAt(Level level, int blockX) {
        if (!(level instanceof ServerLevel server) || server.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return TimeLockTarget.NONE;
        }
        ChunkGenerator generator = server.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return TimeLockTarget.NONE;
        }
        int index = rotating.delegateIndexForBlockX(blockX);
        return TimeLockTarget.fromLane(BandLayout.laneName(rotating.delegates().get(index)));
    }
}
