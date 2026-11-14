package dimblend.api;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/**
 * Public boundary for runtime changes to the rotating dimension's region layout.
 *
 * <p>Owned by dimblend core because world generation must read it even when the mod that wrote
 * the override is absent. An override is persisted with the world and applies only to chunks
 * generated after it was written: already generated chunks are never rewritten.
 *
 * <p>Thread contract: {@link #overrideOf} and {@link #isBarren} are called from world-generation
 * worker threads and must be lock-free reads; {@link #forceLane} is server-thread only.
 *
 * <p>Requirement: dimblend-dragonchase/docs/requirements.md §3.3, §4.
 */
public final class RegionOverrides {
    /** What a region is forced to become. */
    public enum Override {
        /** End lane with plain end stone islands only: one barren biome, no features, no structures, no generation-time mobs. */
        END_BARREN
    }

    /**
     * Forces {@code region} (positive-side region index, same numbering as the layout) to {@code override}
     * and persists it. Idempotent. Returns false when the region index is not a random region (&lt; 34),
     * or the server has no rotating dimension.
     */
    public static boolean forceLane(MinecraftServer server, int region, Override override) {
        return Impl.get().forceLane(server, region, override);
    }

    /** The override written for {@code region}, if any. Lock-free; safe on worldgen threads. */
    public static Optional<Override> overrideOf(int region) {
        return Impl.get().overrideOf(region);
    }

    /** True when {@code region} must be generated barren. Lock-free; safe on worldgen threads. */
    public static boolean isBarren(int region) {
        return overrideOf(region).filter(o -> o == Override.END_BARREN).isPresent();
    }

    /** Implementation hook installed by dimblend core at mod construction. */
    public interface Impl {
        boolean forceLane(MinecraftServer server, int region, Override override);

        Optional<Override> overrideOf(int region);

        Impl NONE = new Impl() {
            // The nested enum Override shadows java.lang.Override inside this class, so the
            // annotation must be qualified (or the file cannot compile).
            @java.lang.Override
            public boolean forceLane(MinecraftServer server, int region, Override override) {
                return false;
            }

            @java.lang.Override
            public Optional<Override> overrideOf(int region) {
                return Optional.empty();
            }
        };

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

    private RegionOverrides() {
    }
}
