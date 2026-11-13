package dimblend.spyglass.structure;

/**
 * Horizontal distances between a player and a structure's union bounds, and the display ring.
 *
 * <p>The ring is fixed: a structure is shown when its union-bounds center is at least
 * {@link #MIN_BLOCKS} and at most {@link #MAX_BLOCKS} blocks away. The outer edge is a hard cap;
 * the inner edge keeps the player from re-marking what is already beside them. Both edges measure
 * the <b>center</b>, so a long building does not pop in and out as its far corner crosses an edge.
 */
public final class SpyglassDistance {
    /** Structures closer than this (union-bounds center) are not shown. */
    public static final double MIN_BLOCKS = 64.0;
    /** Structures whose union-bounds center is farther than this are not shown. */
    public static final double MAX_BLOCKS = 1024.0;

    private SpyglassDistance() {
    }

    /**
     * Horizontal distance from the player to the horizontal center of the structure's union bounds.
     * The union center is computed from the server-provided bounds, never from the (possibly
     * truncated) piece list, so the distance stays exact.
     */
    public static double horizontal(double playerX, double playerZ, StructureOutline outline) {
        OutlineBox bounds = outline.bounds();
        return Math.hypot(playerX - bounds.centerX(), playerZ - bounds.centerZ());
    }

    /** Exact per-frame range checked by the client: center distance in {@code [64, 1024]}. */
    public static boolean inRange(double centerDistance) {
        return centerDistance >= MIN_BLOCKS && centerDistance <= MAX_BLOCKS;
    }

    /**
     * Padded range the server keeps in the snapshot: {@code [64 - pad, 1024 + pad]}. The client
     * re-filters against its live position, so the pad only has to cover the server's movement
     * refresh threshold.
     */
    public static boolean inSnapshotRange(double centerDistance, double padBlocks) {
        if (padBlocks < 0.0) {
            throw new IllegalArgumentException("Negative snapshot padding: " + padBlocks);
        }
        return centerDistance >= MIN_BLOCKS - padBlocks && centerDistance <= MAX_BLOCKS + padBlocks;
    }

    public static long roundToTen(double distance) {
        return Math.round(distance / 10.0) * 10L;
    }

    public static String format(double distance) {
        return "~" + roundToTen(distance);
    }
}
