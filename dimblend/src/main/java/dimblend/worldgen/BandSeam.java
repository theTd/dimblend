package dimblend.worldgen;

import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Pure band-seam logic: the region-boundary column rule that used to live inside
 * {@link RegionBoundaryWall}, separated from the blocks the wall used to write.
 *
 * <p>W2 removes the {@code biomesoplenty:null_block} partition from newly generated
 * chunks ({@link #isFramelessSeam()} defaults to {@code true}); the seam survives as
 * logic:</p>
 * <ul>
 *   <li>{@link #ownsSeamColumn} — the column is the boundary between two bands whose
 *       {@link BandLayout#laneName} differs. Same-lane boundaries stay open corridors.</li>
 *   <li>{@link #overlappedSeamColumn} — runtime box test for the carved vault
 *       cross-section, used by the walking guard and the Sable crossing sweep.</li>
 *   <li>{@link #crossesSeamX} — one-block horizontal fluid step across the seam, used
 *       by {@code FlowingFluidSeamMixin} to stop water/lava pouring between bands.</li>
 *   <li>{@link #seamMaxDy} — the lower of the two lanes' carve ceilings, so a slice
 *       change can never leave a one-block ceiling step at the seam.</li>
 * </ul>
 *
 * <p>{@code framelessSeam == false} is the rollback path: {@link RegionBoundaryWall#place}
 * writes the legacy wall/gate again and {@link OakTrackCorridor} keeps its old skip of
 * boundary columns. The predicates are correct in both modes.</p>
 */
public final class BandSeam {
    /** {@code true} (default) = no null_block wall, seam enforced by logic only. */
    private static volatile boolean framelessSeam = true;

    private BandSeam() {
    }

    public static boolean isFramelessSeam() {
        return framelessSeam;
    }

    public static void setFramelessSeam(boolean value) {
        framelessSeam = value;
    }

    public static boolean ownsSeamColumn(RotatingChunkGenerator rotating, int blockX) {
        return ownsSeamColumn(rotating.layout(), rotating.bandSize(), blockX);
    }

    /**
     * True when this block column is a region boundary (local X == 0) whose two sides
     * differ in {@link BandLayout#laneName}. Shared by worldgen and runtime guards so
     * both always agree on where a seam exists; it no longer implies a wall block.
     */
    public static boolean ownsSeamColumn(BandLayout layout, int bandSize, int blockX) {
        if (Math.floorMod(blockX, bandSize) != 0) {
            return false;
        }
        return !layout.sameLaneAcrossBoundary(blockX, bandSize);
    }

    /**
     * Runtime check for the passage guard and the Sable sweep: returns the block X of the
     * first seam-column slab the given box overlaps, or -1.
     */
    public static int overlappedSeamColumn(
            RotatingChunkGenerator rotating,
            double minX, double maxX,
            double minY, double maxY,
            double minZ, double maxZ) {
        return overlappedSeamColumn(rotating.layout(), rotating.bandSize(),
                minX, maxX, minY, maxY, minZ, maxZ);
    }

    /** Layout/test overload of {@link #overlappedSeamColumn(RotatingChunkGenerator, double, double, double, double, double, double)}. */
    public static int overlappedSeamColumn(
            BandLayout layout,
            int bandSize,
            double minX, double maxX,
            double minY, double maxY,
            double minZ, double maxZ) {
        int from = (int) Math.floor(minX);
        int to = (int) Math.floor(maxX - 1.0E-7);
        for (int x = from; x <= to; x++) {
            if (Math.floorMod(x, bandSize) != 0 || !ownsSeamColumn(layout, bandSize, x)) {
                continue;
            }
            if (x + 1 > minX && x < maxX
                    && maxY >= OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_FLOOR_DY
                    && minY <= OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_APEX_DY
                    && maxZ >= OakTrackCorridor.CORRIDOR_Z - OakTrackCorridor.VAULT_RADIUS
                    && minZ <= OakTrackCorridor.CORRIDOR_Z + OakTrackCorridor.VAULT_RADIUS + 1) {
                return x;
            }
        }
        return -1;
    }

    /**
     * Carve ceiling shared by both lanes at the seam column {@code blockX}: the lower of
     * the west and east lane ceilings. Today every lane yields {@link OakTrackCorridor#VAULT_APEX_DY},
     * but the invariant keeps the tunnel watertight if a slice/delegate ceiling ever changes.
     */
    public static int seamMaxDy(RotatingChunkGenerator rotating, int blockX) {
        int bandSize = rotating.bandSize();
        int region = BandLayout.regionOfBlockX(blockX, bandSize);
        ChunkGenerator west = rotating.delegates().get(
                rotating.delegateIndexForBlockX(region * bandSize - 1));
        ChunkGenerator east = rotating.delegates().get(
                rotating.delegateIndexForBlockX(region * bandSize));
        return Math.min(OakTrackCorridor.vaultMaxDy(west), OakTrackCorridor.vaultMaxDy(east));
    }

    /**
     * A one-block horizontal fluid step from {@code fromX} to {@code toX} crosses the seam
     * when {@code max(fromX, toX)} is a seam column, in either direction. Vertical steps
     * ({@code |toX - fromX| != 1}) and same-lane boundaries never cross.
     */
    public static boolean crossesSeamX(BandLayout layout, int bandSize, int fromX, int toX) {
        return Math.abs(toX - fromX) == 1
                && ownsSeamColumn(layout, bandSize, Math.max(fromX, toX));
    }
}
