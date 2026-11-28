package dimblend.client.mirage;

import dimblend.mirage.MirageChunkKey;

/**
 * Pure routing rules for a mirage chunk's quart-biome lookup: given a global quart coordinate,
 * decide whether it comes from this chunk, a sibling mirage chunk, the real level (which is the
 * <em>same band</em> across the seam), or a clamped local cell.
 *
 * <p><b>Why this is not "delegate to the level".</b> A mirage chunk holds band A's terrain
 * generated inside band B's coordinates, so the vanilla {@code ClientLevel} biome at the same
 * position belongs to band B (underground / caves / plains — whatever B generated). Tinting
 * against it is exactly the colour cast the mirage used to show. The rule below keeps every
 * lookup inside the tableau and only crosses into the real level on the <em>mirage's own</em>
 * side of the seam, where the real chunks really are band A's continuation and the tint must
 * blend seamlessly across {@code X = slabX}.</p>
 *
 * <p>Kept free of Minecraft types so JUnit can pin the routing without a registry bootstrap.</p>
 */
public final class MirageBiomeLookup {
    /** Where a quart-cell's biome must be read from. */
    public enum Route {
        /** Inside this mirage chunk: read the shipped quart-biome palette. */
        LOCAL,
        /** Inside a sibling mirage chunk of the same (seam, side): ask it. */
        SIBLING,
        /** On the mirage band's own side of the seam: the real level is the true continuation. */
        REAL,
        /** Outer edge of the mirage on the far side: clamp into this chunk. NEVER the real level. */
        CLAMPED
    }

    private MirageBiomeLookup() {
    }

    /**
     * @param side            which band the mirage belongs to ({@code +1} west band into east)
     * @param slabX           seam block X
     * @param ownChunkX       this chunk's X
     * @param ownChunkZ       this chunk's Z
     * @param qx              global quart X of the cell
     * @param qz              global quart Z of the cell
     * @param siblingPresent  true when a sibling mirage chunk holds that chunk and has biomes
     */
    public static Route route(byte side, int slabX, int ownChunkX, int ownChunkZ,
                              int qx, int qz, boolean siblingPresent) {
        int targetChunkX = qx >> 2;
        int targetChunkZ = qz >> 2;
        if (targetChunkX == ownChunkX && targetChunkZ == ownChunkZ) {
            return Route.LOCAL;
        }
        if (siblingPresent) {
            return Route.SIBLING;
        }
        return isRealSide(side, slabX, qx) ? Route.REAL : Route.CLAMPED;
    }

    /**
     * True when quart {@code qx} is on the mirage band's own side of the seam plane, i.e. where
     * the real chunks are the same universe as the mirage: west of the seam for side {@code +1}
     * (band A is west), east of it for side {@code -1} (band B is east).
     */
    public static boolean isRealSide(byte side, int slabX, int qx) {
        int seamQuartX = slabX >> 2;
        return side == MirageChunkKey.WEST_BAND_INTO_EAST ? qx < seamQuartX : qx >= seamQuartX;
    }

    /** Clamps a global quart X into {@code chunkX}'s own four quart columns. */
    public static int clampQx(int qx, int chunkX) {
        int first = chunkX << 2;
        return Math.max(first, Math.min(first + 3, qx));
    }

    /** Clamps a global quart Z into {@code chunkZ}'s own four quart rows. */
    public static int clampQz(int qz, int chunkZ) {
        int first = chunkZ << 2;
        return Math.max(first, Math.min(first + 3, qz));
    }

    /** Clamps a global quart Y into the sections this chunk actually carries. */
    public static int clampQy(int qy, int minSectionY, int sectionCount) {
        int first = minSectionY << 2;
        int last = ((minSectionY + sectionCount) << 2) - 1;
        return Math.max(first, Math.min(last, qy));
    }
}
