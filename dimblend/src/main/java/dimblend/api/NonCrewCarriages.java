package dimblend.api;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;

/**
 * The force-loaded cars a playthrough spawned on its own, i.e. cars that are <b>not</b> the
 * player's train: the dragon chase's nest, ender-brake cars and crystal cars. Rules that move
 * "every force-loaded car" (the 2 m/s speed floor) must skip them, otherwise they walk away from
 * where the chase put them.
 *
 * <p>The provider is installed by the mod that owns those cars (dimblend-dragonchase's carriage
 * bootstrap) and answered per level, so one world's cars never leak into another. Until a provider
 * is installed every query answers empty; that is a normal state in a save without that mod.
 *
 * <p><b>Force-load protection is a separate question.</b> The same mod also knows which of its cars
 * must never lose the tickets that keep them loaded: {@code dimblend}'s region purge strips
 * {@code COMMAND_FORCED} tickets off an unmanned chain that has drifted out of every player's keep
 * window and then deletes it. A chase nest that is waiting for the train or fleeing from it is
 * exactly such a chain — usually far behind the players — and losing its ticket would unload it,
 * make the director abandon the chase and burn that End region. So this is a second, independent
 * hook: a car can be "not the crew's" and still be perfectly purgeable (the nest after a won chase),
 * which is why it is a predicate and not a flag on {@link #ids}.
 *
 * <p>Server thread only, like every other world query in this package.
 */
public final class NonCrewCarriages {
    private static final Function<ServerLevel, Set<UUID>> EMPTY = level -> Set.of();
    private static final BiPredicate<ServerLevel, UUID> NEVER = (level, subLevelId) -> false;

    private static volatile Function<ServerLevel, Set<UUID>> provider = EMPTY;
    private static volatile BiPredicate<ServerLevel, UUID> unticketGuard = NEVER;

    /** Installs (or with {@code null} removes) the provider. Mod construction time. */
    public static void install(Function<ServerLevel, Set<UUID>> next) {
        provider = next == null ? EMPTY : next;
    }

    /**
     * Installs (or with {@code null} removes) the force-load protection predicate: {@code true}
     * means "this sub-level keeps its loading tickets even when the region purge would strip them".
     * Mod construction time, independent of {@link #install}.
     */
    public static void installUnticketGuard(BiPredicate<ServerLevel, UUID> next) {
        unticketGuard = next == null ? NEVER : next;
    }

    /** Drops the provider and the guard, e.g. when the world that installed them is gone. */
    public static void reset() {
        provider = EMPTY;
        unticketGuard = NEVER;
    }

    /** Ids of the non-crew cars running in {@code level}; empty when no provider is installed. */
    public static Set<UUID> ids(ServerLevel level) {
        Set<UUID> ids = provider.apply(level);
        return ids == null ? Set.of() : ids;
    }

    /**
     * True when {@code subLevelId} must keep its force-load tickets in {@code level}: false until
     * the mod that owns such cars installs its guard, so a save without it purges as before.
     */
    public static boolean isForceLoadProtected(ServerLevel level, UUID subLevelId) {
        return unticketGuard.test(level, subLevelId);
    }

    private NonCrewCarriages() {
    }
}
