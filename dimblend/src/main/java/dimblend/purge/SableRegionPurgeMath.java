package dimblend.purge;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Pure block/bounding-box-to-region math used when matching Sable world coordinates to a purged
 * {@code r.X.Z} region (512x512 blocks = 32x32 chunks).
 */
public final class SableRegionPurgeMath {

    private static final int BLOCK_TO_REGION_SHIFT = 9;

    private SableRegionPurgeMath() {
    }

    /** Returns the vanilla region coordinate along one axis for a world block coordinate. */
    public static int regionCoordOfBlock(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 512);
    }

    /** Returns the packed {@link RegionPurgePlanner#key(int, int)} for world coordinates {@code (x, z)}. */
    public static long poseRegionKey(double x, double z) {
        return RegionPurgePlanner.key(regionCoordOfBlock(x), regionCoordOfBlock(z));
    }

    /** True when the point {@code (x, z)} lies inside {@code r.<regionX>.<regionZ>}. */
    public static boolean isPoseInRegion(double x, double z, int regionX, int regionZ) {
        return ((int) Math.floor(x) >> BLOCK_TO_REGION_SHIFT) == regionX
                && ((int) Math.floor(z) >> BLOCK_TO_REGION_SHIFT) == regionZ;
    }

    /** True when the horizontal projection of a world bounding box intersects {@code r.<regionX>.<regionZ>}. */
    public static boolean doesBoxIntersectRegion(
            double minX, double minZ, double maxX, double maxZ, int regionX, int regionZ) {
        int rMinX = (int) Math.floor(minX) >> BLOCK_TO_REGION_SHIFT;
        int rMaxX = (int) Math.floor(maxX) >> BLOCK_TO_REGION_SHIFT;
        int rMinZ = (int) Math.floor(minZ) >> BLOCK_TO_REGION_SHIFT;
        int rMaxZ = (int) Math.floor(maxZ) >> BLOCK_TO_REGION_SHIFT;
        return regionX >= rMinX && regionX <= rMaxX && regionZ >= rMinZ && regionZ <= rMaxZ;
    }

    /**
     * Atomically decides whether a coupled dependency chain should be deleted when purging
     * {@code targetRegionKey}. Under {@code pose_center}, the chain is purged iff at least one
     * member or its holding chunk belongs to {@code targetRegionKey} and every member's canonical
     * {@code pose.position} region is either {@code targetRegionKey} or no longer present on disk
     * in {@code terrainRegionsRemaining}.
     */
    public static boolean shouldPurgePoseChain(
            LongCollection chainPoseRegionKeys,
            LongCollection chainHoldingRegionKeys,
            long targetRegionKey,
            LongSet terrainRegionsRemaining) {
        if (chainPoseRegionKeys.isEmpty()) {
            return false;
        }
        if (!chainPoseRegionKeys.contains(targetRegionKey) && !chainHoldingRegionKeys.contains(targetRegionKey)) {
            return false;
        }
        var iter = chainPoseRegionKeys.iterator();
        while (iter.hasNext()) {
            long poseRegionKey = iter.nextLong();
            if (poseRegionKey != targetRegionKey && terrainRegionsRemaining.contains(poseRegionKey)) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code (px, py, pz)} falls inside the world box expanded by {@code margin} on every axis. */
    public static boolean isPointInExpandedBox(
            double px,
            double py,
            double pz,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double margin) {
        return px >= minX - margin
                && px <= maxX + margin
                && py >= minY - margin
                && py <= maxY + margin
                && pz >= minZ - margin
                && pz <= maxZ + margin;
    }
}
