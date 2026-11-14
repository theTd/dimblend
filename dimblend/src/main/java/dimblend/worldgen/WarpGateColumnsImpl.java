package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import dimblend.api.WarpGateColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * {@link WarpGateColumns} on top of the worldgen rules that own the gates.
 *
 * <p>Column ownership is not re-derived here: {@link RegionBoundaryWall#ownsBoundaryColumn} is the
 * same test {@link RegionBoundaryWall#place} used to write the wall/gate and
 * {@link WarpGatePassageGuard} uses at runtime, so a gate exists exactly on a region-boundary
 * column whose two sides differ in lane (which now includes forced End regions, see
 * {@link BandLayout#delegateIndex}). The answer is then read from the actual blocks inside the
 * corridor cross-section, so a boundary column without a carved vault (no gate cells) is not
 * reported as a gate.
 *
 * <p>Only already-loaded chunks are read ({@code ServerChunkCache#getChunkNow}); a null chunk
 * means "not ready" and is treated as not touching — this must never trigger generation or a
 * blocking chunk load from a gameplay check (dimblend-dev §8.28).
 *
 * <p>Server thread only (the caller's contract).
 */
public final class WarpGateColumnsImpl implements WarpGateColumns.Impl {
    /** Same epsilon the passage guard uses so a box ending exactly on a column edge does not count it. */
    private static final double EDGE_EPSILON = 1.0E-7;

    @Override
    public boolean touchesGate(ServerLevel level, AABB worldBox) {
        if (level == null || worldBox == null || !level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return false;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            // Layout is published when the level is constructed (createState); before that no
            // gate can exist and asking for it must not throw.
            return false;
        }
        // Cheap rejects before touching the generator or any chunk: gates only exist inside the
        // carved corridor cross-section (same bounds as WarpGatePassageGuard).
        int gateMinY = OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_FLOOR_DY;
        int gateMaxY = OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_APEX_DY;
        if (worldBox.maxY < gateMinY || worldBox.minY > gateMaxY + 1) {
            return false;
        }
        int corridorMinZ = OakTrackCorridor.CORRIDOR_Z - OakTrackCorridor.VAULT_RADIUS;
        int corridorMaxZ = OakTrackCorridor.CORRIDOR_Z + OakTrackCorridor.VAULT_RADIUS + 1;
        if (worldBox.maxZ < corridorMinZ || worldBox.minZ > corridorMaxZ) {
            return false;
        }
        int bandSize = rotating.bandSize();
        int fromX = (int) Math.floor(worldBox.minX);
        int toX = (int) Math.floor(worldBox.maxX - EDGE_EPSILON);
        int fromY = Math.max(gateMinY, (int) Math.floor(worldBox.minY));
        int toY = Math.min(gateMaxY, (int) Math.ceil(worldBox.maxY) - 1);
        int fromZ = Math.max(corridorMinZ, (int) Math.floor(worldBox.minZ));
        int toZ = Math.min(corridorMaxZ, (int) Math.ceil(worldBox.maxZ) - 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            if (Math.floorMod(x, bandSize) != 0
                    || !RegionBoundaryWall.ownsBoundaryColumn(layout, bandSize, x)) {
                continue;
            }
            for (int z = fromZ; z <= toZ; z++) {
                // Null = chunk not generated/loaded yet: never force a load, report "no gate".
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }
                for (int y = fromY; y <= toY; y++) {
                    if (chunk.getBlockState(cursor.set(x, y, z)).is(DimBlendRegistries.WARP_GATE.get())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
