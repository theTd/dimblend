package dimblend.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Read-only warp gate geometry for rules outside dimblend core.
 *
 * <p>Implementation (W1) must use the same boundary-column rule as {@code RegionBoundaryWall} /
 * {@code WarpGatePassageGuard} and must read only already loaded FULL chunks
 * ({@code getChunkNow}), never trigger loading or generation.
 *
 * <p>Server-thread only.
 */
public final class WarpGateColumns {
    /** True when the world-space box overlaps any warp gate block in the corridor. */
    public static boolean touchesGate(ServerLevel level, AABB worldBox) {
        return Impl.get().touchesGate(level, worldBox);
    }

    public interface Impl {
        boolean touchesGate(ServerLevel level, AABB worldBox);

        Impl NONE = (level, box) -> false;

        static Impl get() {
            return Holder.impl;
        }

        static void install(Impl impl) {
            Holder.impl = impl == null ? NONE : impl;
        }
    }

    private static final class Holder {
        private static volatile Impl impl = Impl.NONE;
    }

    private WarpGateColumns() {
    }
}
