package dimblend.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Which Sable sub-levels are currently under a minimum-speed mechanism, and which of those are
 * being <em>actively pushed</em> by it right now.
 *
 * <p><b>Why it exists.</b> Two unrelated mechanisms keep a sub-level from stopping:
 * the warp gate raises {@code +X} velocity to 1 m/s ({@code dimblend}, {@code WarpGateMotion}),
 * and the dragon chase tops consists up to {@code ChaseMath.SPEED_FLOOR} (2 m/s) plus its own
 * nest floor ({@code dimblend-dragonchase}).
 * Both live in different modules from the low-speed parking brake
 * ({@code dimblend-experience}), which must keep a braked sub-level at exactly 0 m/s — except
 * when one of those mechanisms is deliberately holding it above zero. This class is that
 * cross-module handshake: producers mark, the brake consumer asks.
 *
 * <p><b>Two levels of truth.</b> "Governed" means a mechanism <i>claims</i> this sub-level this
 * tick (it is inside a warp gate while somebody is aboard, it is eligible for the chase floor),
 * so the brake hold must stand down. "Actively pushing" means the mechanism actually had to
 * apply an impulse to keep the speed up this tick — the visual crate of the feature:
 * {@code min-speed particles} only spark while the train is really being shoved along, so a
 * train running at 15 m/s under a nominal 2 m/s floor does not sparkle. The push flag is held
 * for {@link #ACTIVE_PUSH_HOLD_TICKS} ticks so a speed hovering around the floor
 * ({@code 1.99 <-> 2.01}) does not flicker the particles off and on.
 *
 * <p><b>Threading.</b> Marks are written on the server thread only. Values are plain
 * {@code long}s behind {@code volatile} so a read from another server thread (payload assembly,
 * diagnostics) can never see a torn pair, and expiry is decided by comparing against
 * {@code gameTime} rather than by removing entries — a stale entry is harmless and is pruned
 * lazily.
 *
 * <p><b>Lifecycle.</b> {@link #clear(ResourceKey)} on level unload and {@link #clearAll()} on
 * server stop: the state describes the running world's ticks, and {@code gameTime} restarts
 * with a new save.
 */
public final class MinimumSpeedState {

    /** How many ticks an "actually pushed" mark stays valid after the last push. */
    public static final int ACTIVE_PUSH_HOLD_TICKS = 5;

    /** How long a "governed" mark survives: this tick plus the next, covering tick order skew. */
    private static final int GOVERNED_HOLD_TICKS = 2;

    /** Above this many live entries the map is swept for expired ones (see {@link #prune}). */
    private static final int PRUNE_THRESHOLD = 256;

    private static final Map<ResourceKey<Level>, ConcurrentHashMap<UUID, Entry>> STATE =
            new ConcurrentHashMap<>();

    /** One sub-level's deadlines; {@code >= gameTime} means "still true". */
    private static final class Entry {
        volatile long governedUntil;
        volatile long pushingUntil;
    }

    private MinimumSpeedState() {
    }

    /**
     * Declares that a minimum-speed mechanism is responsible for this sub-level this tick.
     *
     * <p>Callers are the mechanisms themselves, whether or not they needed to push: being inside
     * the gate's envelope or being eligible for the chase floor already forbids the brake from
     * pinning the train to zero.
     */
    public static void markGoverned(ServerLevel level, UUID subLevelId) {
        markGoverned(level.dimension(), subLevelId, level.getGameTime());
    }

    /**
     * Declares that this sub-level was actually shoved along by a minimum-speed mechanism this
     * tick. Implies {@link #markGoverned} — a mechanism that pushed is certainly responsible.
     */
    public static void markActivelyPushing(ServerLevel level, UUID subLevelId) {
        markActivelyPushing(level.dimension(), subLevelId, level.getGameTime());
    }

    /** True while a minimum-speed mechanism claims this sub-level, so the brake must not hold it at 0. */
    public static boolean isExemptFromBrakeHold(ServerLevel level, UUID subLevelId) {
        return isExemptFromBrakeHold(level.dimension(), subLevelId, level.getGameTime());
    }

    /** True while the sub-level was pushed within the last {@link #ACTIVE_PUSH_HOLD_TICKS} ticks. */
    public static boolean isActivelyPushing(ServerLevel level, UUID subLevelId) {
        return isActivelyPushing(level.dimension(), subLevelId, level.getGameTime());
    }

    /**
     * Every sub-level in this level that is being actively pushed right now.
     *
     * <p>A copy, so callers may fan it out to clients or iterate while the next tick marks new
     * entries. Empty when nothing is being pushed — never null.
     */
    public static Set<UUID> activePushingSubLevels(ServerLevel level) {
        return activePushingSubLevels(level.dimension(), level.getGameTime());
    }

    /** Forgets one dimension; used when its level unloads. */
    public static void clear(ResourceKey<Level> dimension) {
        if (dimension != null) {
            STATE.remove(dimension);
        }
    }

    /** Forgets everything; used when the server stops so a new save cannot inherit stale marks. */
    public static void clearAll() {
        STATE.clear();
    }

    // ------------------------------------------------------------------ package-private tick-injected forms

    static void markGoverned(ResourceKey<Level> dimension, UUID subLevelId, long gameTime) {
        if (dimension == null || subLevelId == null) {
            return;
        }
        Entry entry = entry(dimension, subLevelId);
        entry.governedUntil = Math.max(entry.governedUntil, gameTime + GOVERNED_HOLD_TICKS);
        prune(dimension, gameTime);
    }

    static void markActivelyPushing(ResourceKey<Level> dimension, UUID subLevelId, long gameTime) {
        if (dimension == null || subLevelId == null) {
            return;
        }
        Entry entry = entry(dimension, subLevelId);
        entry.governedUntil = Math.max(entry.governedUntil, gameTime + GOVERNED_HOLD_TICKS);
        entry.pushingUntil = Math.max(entry.pushingUntil, gameTime + ACTIVE_PUSH_HOLD_TICKS);
        prune(dimension, gameTime);
    }

    static boolean isExemptFromBrakeHold(ResourceKey<Level> dimension, UUID subLevelId, long gameTime) {
        Entry entry = lookup(dimension, subLevelId);
        return entry != null && entry.governedUntil >= gameTime;
    }

    static boolean isActivelyPushing(ResourceKey<Level> dimension, UUID subLevelId, long gameTime) {
        Entry entry = lookup(dimension, subLevelId);
        return entry != null && entry.pushingUntil >= gameTime;
    }

    static Set<UUID> activePushingSubLevels(ResourceKey<Level> dimension, long gameTime) {
        ConcurrentHashMap<UUID, Entry> entries = lookupAll(dimension);
        if (entries == null) {
            return Set.of();
        }
        Set<UUID> out = new HashSet<>();
        for (Map.Entry<UUID, Entry> candidate : entries.entrySet()) {
            if (candidate.getValue().pushingUntil >= gameTime) {
                out.add(candidate.getKey());
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    // ------------------------------------------------------------------ internals

    private static Entry entry(ResourceKey<Level> dimension, UUID subLevelId) {
        return STATE.computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(subLevelId, ignored -> new Entry());
    }

    private static Entry lookup(ResourceKey<Level> dimension, UUID subLevelId) {
        if (dimension == null || subLevelId == null) {
            return null;
        }
        ConcurrentHashMap<UUID, Entry> entries = STATE.get(dimension);
        return entries == null ? null : entries.get(subLevelId);
    }

    private static ConcurrentHashMap<UUID, Entry> lookupAll(ResourceKey<Level> dimension) {
        return dimension == null ? null : STATE.get(dimension);
    }

    /** Drops deadlines that expired long ago; only once the dimension's map has grown a map's worth. */
    private static void prune(ResourceKey<Level> dimension, long gameTime) {
        ConcurrentHashMap<UUID, Entry> entries = STATE.get(dimension);
        if (entries == null || entries.size() < PRUNE_THRESHOLD) {
            return;
        }
        entries.values().removeIf(entry -> entry.governedUntil < gameTime
                && entry.pushingUntil < gameTime);
    }
}
