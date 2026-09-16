package dimblend.worldgen;

import java.util.function.IntPredicate;

/**
 * Block-X geometry for {@link RegionBoundaryNoStructureZone}. Kept free of
 * Minecraft types so the inclusive 16-chunk wall clearance can be unit-tested.
 */
final class RegionBoundaryNoStructureGeometry {
    /**
     * Inclusive half-width in blocks. Keep equal to
     * {@code OakTrackCorridor.NO_STRUCTURE_CHUNK_RANGE * 16 + 15}.
     */
    static final int HALF_WIDTH = 16 * 16 + 15;

    private RegionBoundaryNoStructureGeometry() {
    }

    static boolean originInside(int blockX, int bandSize, int half, IntPredicate isWall) {
        int local = Math.floorMod(blockX, bandSize);
        if (local <= half) {
            int westWall = blockX - local;
            if (isWall.test(westWall)) {
                return true;
            }
        }
        int distEast = bandSize - local;
        if (distEast <= half) {
            int eastWall = blockX + distEast;
            if (isWall.test(eastWall)) {
                return true;
            }
        }
        return false;
    }

    static boolean originFullyInside(int minX, int maxX, int bandSize, int half, IntPredicate isWall) {
        return originInside(minX, bandSize, half, isWall) && originInside(maxX, bandSize, half, isWall);
    }

    static boolean aabbIntersects(int minX, int maxX, int bandSize, int half, IntPredicate isWall) {
        if (maxX < minX) {
            return false;
        }
        int kFrom = (int) Math.floorDiv((long) minX - half, bandSize);
        int kTo = (int) Math.floorDiv((long) maxX + half, bandSize);
        for (int k = kFrom; k <= kTo; k++) {
            long wallX = (long) k * (long) bandSize;
            if (wallX < Integer.MIN_VALUE || wallX > Integer.MAX_VALUE) {
                continue;
            }
            int wall = (int) wallX;
            if (!isWall.test(wall)) {
                continue;
            }
            if (maxX >= wall - half && minX <= wall + half) {
                return true;
            }
        }
        return false;
    }
}
