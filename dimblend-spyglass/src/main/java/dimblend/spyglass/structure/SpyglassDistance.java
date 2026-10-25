package dimblend.spyglass.structure;

public final class SpyglassDistance {
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

    /**
     * Horizontal distance from the player to the <b>farthest</b> point of the structure's union
     * footprint, i.e. the farthest of its four corners. Max coordinates are inclusive, so the far
     * faces sit at {@code max + 1}; against the reveal distance this is the "the structure reaches out
     * of the fog" test, which is not the same as the center distance: a long building can start
     * inside the reveal distance while its center is still outside it.
     */
    public static double farthestHorizontal(double playerX, double playerZ, StructureOutline outline) {
        OutlineBox bounds = outline.bounds();
        double dx = Math.max(Math.abs(playerX - bounds.minX()), Math.abs(bounds.maxX() + 1.0 - playerX));
        double dz = Math.max(Math.abs(playerZ - bounds.minZ()), Math.abs(bounds.maxZ() + 1.0 - playerZ));
        return Math.hypot(dx, dz);
    }

    /**
     * Exact per-frame range checked by the client.
     *
     * @param centerDistance   {@link #horizontal} of the structure
     * @param farthestDistance {@link #farthestHorizontal} of the structure
     * @param revealBlocks     the client's current terrain fog start (the reveal distance)
     */
    public static boolean inRange(double centerDistance, double farthestDistance, double revealBlocks) {
        return centerDistance <= MAX_BLOCKS && farthestDistance >= revealBlocks;
    }

    /**
     * Padded range the server keeps in the snapshot. {@code padBlocks} must cover both the server's
     * movement refresh threshold and its reveal-distance refresh threshold, so any structure inside
     * the exact range of the player's live position and live reveal distance is still inside the
     * padded range of the state the snapshot was built for.
     */
    public static boolean inSnapshotRange(
            double centerDistance, double farthestDistance, double revealBlocks, double padBlocks) {
        if (padBlocks < 0.0) {
            throw new IllegalArgumentException("Negative snapshot padding: " + padBlocks);
        }
        return centerDistance <= MAX_BLOCKS + padBlocks && farthestDistance >= revealBlocks - padBlocks;
    }

    public static long roundToTen(double distance) {
        return Math.round(distance / 10.0) * 10L;
    }

    public static String format(double distance) {
        return "~" + roundToTen(distance);
    }
}
