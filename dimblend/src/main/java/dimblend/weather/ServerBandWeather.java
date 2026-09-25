package dimblend.weather;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Server-side counterpart of {@link dimblend.client.ClientWeatherLock}: while a
 * rotating-dimension tick is attributed to a column, {@code isRaining} /
 * {@code isThundering} read this stack instead of the shared rain level.
 * {@link #clearAt} is the position-aware half used by {@code isRainingAt}.
 */
public final class ServerBandWeather {
    private static final ThreadLocal<ArrayDeque<Boolean>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private ServerBandWeather() {
    }

    public static void push(Level level, BlockPos pos) {
        STACK.get().push(clearAt(level, pos));
    }

    public static void pop() {
        ArrayDeque<Boolean> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    public static boolean locked() {
        Boolean clear = STACK.get().peek();
        return clear != null && clear;
    }

    /**
     * True when this column is a clear-sky lane. Safe to call without a stack
     * frame; used by {@code isRainingAt} so lightning and entity wetness still
     * respect the lock outside wrapped ticks.
     */
    public static boolean clearAt(Level level, BlockPos pos) {
        return clearAt(level, pos.getX());
    }

    public static boolean clearAt(Level level, int blockX) {
        if (!(level instanceof ServerLevel server) || server.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        ChunkGenerator generator = server.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        int index = rotating.delegateIndexForBlockX(blockX);
        return WeatherLockTarget.clearSky(BandLayout.laneName(rotating.delegates().get(index)));
    }

    public static void run(Level level, BlockPos pos, Runnable body) {
        push(level, pos);
        try {
            body.run();
        } finally {
            pop();
        }
    }
}
