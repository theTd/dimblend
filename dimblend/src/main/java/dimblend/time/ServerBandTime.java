package dimblend.time;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Server-side counterpart of {@link dimblend.client.ClientTimeLock}: while a
 * rotating-dimension tick is attributed to a column, {@code isDay}/{@code isNight}
 * / {@code getDayTime}/{@code getSkyDarken} read this stack instead of the shared
 * {@code skyDarken} field. Natural spawn must push the stack too: monster light
 * checks use {@code getSkyDarken}, not {@code isNight}.
 */
public final class ServerBandTime {
    /**
     * One deque per thread, kept for the thread's lifetime. It used to be removed whenever it
     * emptied, so every mob tick re-created it: a ThreadLocal miss plus a fresh ArrayDeque per
     * call (~2 % of slow server ticks in the 2026-10-12 spark capture, and GC garbage).
     */
    private static final ThreadLocal<ArrayDeque<TimeLockTarget>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final long TWILIGHT_DAY_TIME =
            (TimeLockTarget.TWILIGHT_MIN + TimeLockTarget.TWILIGHT_MAX_EXCLUSIVE - 1) / 2;
    /** Lock target per band of the current rotating generator; held weakly so an unloaded world can be freed. */
    private static volatile LaneTargets laneTargets;

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

    /**
     * Vanilla {@link Level#updateSkyBrightness()} formula, evaluated at the
     * locked day time, so monster spawn light checks see the same night the
     * client time lock shows. Only the day-time item is pinned here: rain and
     * thunder still read the shared level, so rainy weather can still darken
     * spawn light on clear-locked bands (known deviation, see
     * docs/generation-rules.md).
     */
    public static int lockedSkyDarken(Level level) {
        return SkyDarken.of(lockedDayTime(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
    }

    public static void run(Level level, BlockPos pos, Runnable body) {
        TimeLockTarget target = targetAt(level, pos.getX());
        ArrayDeque<TimeLockTarget> stack = STACK.get();
        if (target.mode() == TimeLockTarget.Mode.NONE && stack.isEmpty()) {
            // Nothing to attribute and nothing to shadow: current() already reads NONE.
            body.run();
            return;
        }
        stack.push(target);
        try {
            body.run();
        } finally {
            stack.pop();
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
        return laneTargets(rotating)[rotating.delegateIndexForBlockX(blockX)];
    }

    /**
     * The lane of a band never changes for a generator, so resolve each band's target once
     * instead of re-deriving the lane name (instanceof checks, registry id, string switch) on
     * every mob tick, random tick and spawn attempt.
     */
    private static TimeLockTarget[] laneTargets(RotatingChunkGenerator rotating) {
        LaneTargets cached = laneTargets;
        if (cached != null && cached.generator().get() == rotating) {
            return cached.targets();
        }
        List<ChunkGenerator> delegates = rotating.delegates();
        TimeLockTarget[] targets = new TimeLockTarget[delegates.size()];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = TimeLockTarget.fromLane(BandLayout.laneName(delegates.get(i)));
        }
        laneTargets = new LaneTargets(new WeakReference<>(rotating), targets);
        return targets;
    }

    private record LaneTargets(WeakReference<RotatingChunkGenerator> generator, TimeLockTarget[] targets) {
    }
}
