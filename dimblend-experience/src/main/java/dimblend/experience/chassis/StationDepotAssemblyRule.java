package dimblend.experience.chassis;

/**
 * Pure decision for the R6 depot chassis assembly: what should happen to a
 * pre-placed chassis site on this tick?
 *
 * <p>The site is built and assembled in two separate steps, and both have to be
 * idempotent:
 * <ul>
 *   <li><b>built</b> — the blueprint's blocks exist on the server thread. This is the
 *       only thread that can place a bogey or a copycat block entity, so worldgen
 *       (a worker thread) cannot do it.</li>
 *   <li><b>assembled</b> — those blocks have become one Sable sub-level.</li>
 * </ul>
 *
 * <p>Separating them buys two things: a site that fails to assemble does not rebuild
 * its blocks every tick, and assembly always happens on a later tick than the build,
 * so nothing is assembled while the block entity data is still being written.
 */
public final class StationDepotAssemblyRule {
    /** How close a player must be before a depot chassis is built and assembled. */
    public static final int TRIGGER_RADIUS = 64;
    /** Failed attempts allowed per site before it is left alone. */
    public static final int MAX_ATTEMPTS = 8;

    /** What to do with one site this tick. */
    public enum Step {
        /** Nothing to do: too far, already done, or given up. */
        IDLE,
        /** Write the blueprint's blocks. */
        BUILD,
        /** Turn the existing blocks into a sub-level. */
        ASSEMBLE,
        /** Blocks that should be there are gone; stop tracking the site. */
        GIVE_UP
    }

    private StationDepotAssemblyRule() {
    }

    /**
     * Decides this tick's step for one site.
     *
     * @param built     the blueprint's blocks have been written
     * @param assembled a sub-level already exists for this site
     * @param attempts  failed build or assembly attempts so far
     * @param present   at least one of the blueprint's blocks is on the ground
     * @param chunksLoaded every chunk the site spans is loaded right now
     * @param distanceSq squared distance to the nearest player
     */
    public static Step step(boolean built, boolean assembled, int attempts, boolean present,
            boolean chunksLoaded, double distanceSq) {
        if (assembled || attempts >= MAX_ATTEMPTS || !chunksLoaded) {
            return Step.IDLE;
        }
        boolean nearby = distanceSq <= (double) TRIGGER_RADIUS * (double) TRIGGER_RADIUS;
        if (!built) {
            return nearby ? Step.BUILD : Step.IDLE;
        }
        if (!present) {
            // Built once, and now the blocks are gone: a player cleared the site.
            // Rebuilding would fight the player, so the site is dropped instead.
            return Step.GIVE_UP;
        }
        return nearby ? Step.ASSEMBLE : Step.IDLE;
    }
}