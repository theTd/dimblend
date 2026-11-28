package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import dimblend.api.WarpGateColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * {@link WarpGateColumns} on top of the worldgen rules that own the seams.
 *
 * <p>Seam ownership is not re-derived here: {@link BandSeam#ownsSeamColumn} is the same test the
 * corridor carve, {@link WarpGatePassageGuard} and the Sable sweep use, so a seam exists exactly
 * on a region-boundary column whose two sides differ in lane (which includes forced End regions,
 * see {@link BandLayout#delegateIndex}).
 *
 * <p>W2 ({@link BandSeam#isFramelessSeam()}) reports the carved vault cross-section
 * geometrically: the gate is the shape of the tunnel, no block has to exist. Legacy saves with
 * the old {@code warp_gate} blocks still get the block-scan answer, and a boundary column without
 * a carved vault (no gate cells) is never reported as a gate. Only already-loaded chunks are
 * read ({@code ServerChunkCache#getChunkNow}); a null chunk means "not ready" and is treated as
 * not touching — this must never trigger generation or a blocking chunk load from a gameplay
 * check (dimblend-dev §8.28).
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
                    || !BandSeam.ownsSeamColumn(layout, bandSize, x)) {
                continue;
            }
            if (BandSeam.isFramelessSeam()) {
                // W2: the gate is the carved vault cross-section itself; no blocks to read, so
                // report the same geometry the passage guard and Sable sweep use.
                if (intersectsVault(fromY, toY, fromZ, toZ)) {
                    return true;
                }
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

    /**
     * True when the block interval intersects the octagon silhouette. Every row is symmetric in
     * {@code dz}, so testing the interval's closest-to-zero dz against
     * {@link OakTrackCorridor#inVault} answers the whole row.
     */
    private static boolean intersectsVault(int fromY, int toY, int fromZ, int toZ) {
        int dzLow = fromZ - OakTrackCorridor.CORRIDOR_Z;
        int dzHigh = toZ - OakTrackCorridor.CORRIDOR_Z;
        if (dzLow > dzHigh) {
            return false;
        }
        int dz = dzLow <= 0 && dzHigh >= 0 ? 0 : Math.min(Math.abs(dzLow), Math.abs(dzHigh));
        for (int y = fromY; y <= toY; y++) {
            if (OakTrackCorridor.inVault(dz, y - OakTrackCorridor.TRACK_Y)) {
                return true;
            }
        }
        return false;
    }
}
