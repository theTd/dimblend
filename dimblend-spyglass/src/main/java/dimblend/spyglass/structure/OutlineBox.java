package dimblend.spyglass.structure;

import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * One axis-aligned structure piece box, as saved in a structure start's {@code Children}.
 * The box is inclusive: max values are the last covered block coordinate.
 */
public record OutlineBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    /**
     * Coordinates beyond ±2^30 are rejected: they cannot come from a saved structure start, and
     * keeping them bounded means volume math stays finite and float rendering stays meaningful.
     * A box may still touch both ends of this range, which is exactly where {@code minX + maxX}
     * overflows an int — see {@link SpyglassDistance}.
     */
    public static final int MAX_COORDINATE = 1 << 30;

    public OutlineBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Inverted outline bounds");
        }
        requireWorldCoordinate(minX, minY, minZ);
        requireWorldCoordinate(maxX, maxY, maxZ);
    }

    private static void requireWorldCoordinate(int x, int y, int z) {
        if (x < -MAX_COORDINATE || x > MAX_COORDINATE
                || y < -MAX_COORDINATE || y > MAX_COORDINATE
                || z < -MAX_COORDINATE || z > MAX_COORDINATE) {
            throw new IllegalArgumentException("Outline bounds outside the world: " + x + "," + y + "," + z);
        }
    }

    /**
     * Center of the covered block volume on X. Max is inclusive, so the box spans
     * {@code [min, max + 1)} and the center is {@code (min + max + 1) / 2}. The sum is widened to
     * double first: {@code min + max} overflows an int once both sit near {@link #MAX_COORDINATE}.
     */
    public double centerX() {
        return ((double) minX + (double) maxX + 1.0) * 0.5;
    }

    /** Center on Z; same convention as {@link #centerX()}. */
    public double centerZ() {
        return ((double) minZ + (double) maxZ + 1.0) * 0.5;
    }

    public AABB toAABB() {
        return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
    }

    /** Number of blocks covered by this piece, used to rank pieces when a cap truncates them. */
    public double volume() {
        // Widen before subtracting: a span between the ±MAX_COORDINATE limits overflows an int.
        return ((double) maxX - minX + 1.0) * ((double) maxY - minY + 1.0) * ((double) maxZ - minZ + 1.0);
    }

    public boolean encloses(OutlineBox other) {
        return minX <= other.minX && minY <= other.minY && minZ <= other.minZ
                && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
    }

    public static OutlineBox enclosing(List<OutlineBox> boxes) {
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("An outline needs at least one box");
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (OutlineBox box : boxes) {
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        return new OutlineBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
