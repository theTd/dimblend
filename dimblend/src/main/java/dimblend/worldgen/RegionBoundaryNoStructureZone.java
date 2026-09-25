package dimblend.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * No-structure / no-building-feature zone on both sides of a partition wall.
 *
 * Railway-corridor Z clearance lives in {@link OakTrackCorridor}. Walls are the
 * X analogue: every region boundary whose two sides differ in
 * {@link BandLayout#laneName} (the same predicate as {@link RegionBoundaryWall})
 * gets the corridor's 16-chunk half-width on each side. Same-name continuous
 * bands (surface 0/1, underground 5/6, nether 11/12) have no wall and no zone.
 *
 * Structure starts are skipped when the origin chunk sits fully inside a zone,
 * then {@link #dropBlockedStarts} drops any remaining start whose AABB still
 * intersects one. Building-like biome features (see {@link BuildingLikeFeatures})
 * retreat from the same columns; trees and other decorations do not.
 */
public final class RegionBoundaryNoStructureZone {
    private RegionBoundaryNoStructureZone() {
    }

    /** Inclusive half-width in blocks; same 16-chunk band as the corridor. */
    public static int halfWidth() {
        return OakTrackCorridor.noStructureZoneHalfWidth();
    }

    public static boolean originInside(RotatingChunkGenerator rotating, int blockX) {
        return RegionBoundaryNoStructureGeometry.originInside(
                blockX, rotating.bandSize(), halfWidth(), wallAt(rotating));
    }

    public static boolean originFullyInside(RotatingChunkGenerator rotating, ChunkPos pos) {
        return RegionBoundaryNoStructureGeometry.originFullyInside(
                pos.getMinBlockX(),
                pos.getMaxBlockX(),
                rotating.bandSize(),
                halfWidth(),
                wallAt(rotating));
    }

    public static boolean intersectsNoStructureZone(RotatingChunkGenerator rotating, StructureStart start) {
        if (start == null || !start.isValid()) {
            return false;
        }
        return RegionBoundaryNoStructureGeometry.aabbIntersects(
                start.getBoundingBox().minX(),
                start.getBoundingBox().maxX(),
                rotating.bandSize(),
                halfWidth(),
                wallAt(rotating));
    }

    /**
     * Drops starts whose AABB intersects a wall no-structure zone.
     * Safety net for origins that still generate: overlap-only chunks and
     * out-of-zone origins whose AABB crosses a walled boundary.
     */
    public static void dropBlockedStarts(RotatingChunkGenerator rotating, ChunkAccess chunk) {
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }
        Map<Structure, StructureStart> kept = new HashMap<>();
        boolean dropped = false;
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (intersectsNoStructureZone(rotating, start)) {
                dropped = true;
                continue;
            }
            kept.put(entry.getKey(), start);
        }
        if (dropped) {
            chunk.setAllStarts(kept);
        }
    }

    /**
     * Small mod buildings ride the biome-decoration feature pipeline instead of
     * structure starts, so {@link #dropBlockedStarts} never sees them. Retreat
     * their origins from the whole wall no-structure zone, mirroring
     * {@link OakTrackCorridor#blocksBuildingOrigin}.
     */
    public static boolean blocksBuildingOrigin(WorldGenLevel level, BlockPos origin) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        return originInside(rotating, origin.getX());
    }

    private static IntPredicate wallAt(RotatingChunkGenerator rotating) {
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return wallX -> false;
        }
        int bandSize = rotating.bandSize();
        return wallX -> RegionBoundaryWall.ownsBoundaryColumn(layout, bandSize, wallX);
    }
}
