package dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public final class OakTrackCorridor {
    public static final int CORRIDOR_Z = 0;
    public static final int TRACK_Y = 64;
    /** Inclusive |dz| of the tunnel equator. Diameter 19 = Z[-9, +9]. */
    public static final int VAULT_RADIUS = 9;
    /** Circle center is this many blocks above the track. */
    public static final int VAULT_CENTER_DY = VAULT_RADIUS - 1;
    public static final int VAULT_FLOOR_DY = VAULT_CENTER_DY - VAULT_RADIUS;
    public static final int VAULT_APEX_DY = VAULT_CENTER_DY + VAULT_RADIUS;
    public static final ResourceLocation TRACK_ID = ResourceLocation.fromNamespaceAndPath("railways", "track_create_andesite_wide");
    private static final Direction[] DIRECTIONS = Direction.values();

    private OakTrackCorridor() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        carveVault(level.getLevel().getChunkSource().getGenerator(), level.registryAccess(), chunk, null);
    }

    public static void reclearLoadedChunk(ServerLevel level, ChunkAccess chunk) {
        carveVault(level.getChunkSource().getGenerator(), level.registryAccess(), chunk, level);
    }

    private static void carveVault(
            ChunkGenerator generator,
            RegistryAccess access,
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live
    ) {
        int chunkZ = chunk.getPos().z;
        if (!touchesVault(chunkZ)) {
            return;
        }
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block trackBlock = access.registryOrThrow(Registries.BLOCK).get(TRACK_ID);
        if (trackBlock == null) {
            throw new IllegalStateException("missing required block railways:track_create_andesite_wide");
        }

        int minX = chunk.getPos().getMinBlockX();
        int maxX = chunk.getPos().getMaxBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        int vaultMinZ = Math.max(minZ, CORRIDOR_Z - VAULT_RADIUS);
        int vaultMaxZ = Math.min(maxZ, CORRIDOR_Z + VAULT_RADIUS);
        if (vaultMinZ > vaultMaxZ) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (chunkZ == 0) {
            for (int x = minX; x <= maxX; x++) {
                writeTrack(chunk, cursor, x, trackBlock, live);
            }
        }
        boolean[] carved = new boolean[maxX - minX + 1];
        for (int x = minX; x <= maxX; x++) {
            int maxDy = vaultMaxDy(rotating, x);
            if (maxDy < 0) {
                continue;
            }
            carved[x - minX] = true;
            clearVaultColumn(chunk, cursor, x, vaultMinZ, vaultMaxZ, trackBlock, maxDy, live);
            stabilizeVaultCeiling(chunk, cursor, x, vaultMinZ, vaultMaxZ, maxDy, live);
        }
        for (int x = minX; x <= maxX; x++) {
            if (!carved[x - minX]) {
                continue;
            }
            sealVaultShell(chunk, cursor, x, minX, maxX, minZ, maxZ, vaultMinZ, vaultMaxZ, trackBlock, carved, vaultMaxDy(rotating, x), live);
        }
    }

    public static boolean touchesVault(int chunkZ) {
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;
        return maxZ >= CORRIDOR_Z - VAULT_RADIUS && minZ <= CORRIDOR_Z + VAULT_RADIUS;
    }

    public static BoundingBox vaultAabb() {
        return new BoundingBox(
                Integer.MIN_VALUE,
                TRACK_Y + VAULT_FLOOR_DY - 1,
                CORRIDOR_Z - VAULT_RADIUS - 1,
                Integer.MAX_VALUE,
                TRACK_Y + VAULT_APEX_DY,
                CORRIDOR_Z + VAULT_RADIUS + 1
        );
    }

    public static boolean intersectsVault(StructureStart start) {
        return start != null && start.isValid() && start.getBoundingBox().intersects(vaultAabb());
    }

    public static void dropStartsIntersectingVault(ChunkAccess chunk) {
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }
        Map<Structure, StructureStart> kept = new HashMap<>();
        boolean dropped = false;
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (intersectsVault(start)) {
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
     * Full circle of radius 9 in the Z/Y plane. Track sits on the 9-wide row.
     * Center is (z=0, y=TRACK_Y+8). Integer form dz^2 + (dy - 8)^2 <= 81.
     * Floor dy = -1 (1 cell); track dy = 0 (z=-4..4); equator dy = 8 (|dz|<=9); apex dy = 17.
     */
    static boolean inVault(int dz, int dy) {
        int offY = dy - VAULT_CENTER_DY;
        return dz * dz + offY * offY <= VAULT_RADIUS * VAULT_RADIUS;
    }


    private static void clearVaultColumn(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            Block trackBlock,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = VAULT_FLOOR_DY; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                int y = TRACK_Y + dy;
                if (z == CORRIDOR_Z && y == TRACK_Y) {
                    continue;
                }
                clearTunnelCell(chunk, cursor, x, y, z, trackBlock, live);
            }
        }
    }

    private static void sealVaultShell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int vaultMinZ,
            int vaultMaxZ,
            Block trackBlock,
            boolean[] carved,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        if (maxDy < 0) {
            return;
        }
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = VAULT_FLOOR_DY; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                int y = TRACK_Y + dy;
                boolean trackCenter = z == CORRIDOR_Z && y == TRACK_Y;
                if (!trackCenter && !chunk.getBlockState(cursor.set(x, y, z)).isAir()) {
                    continue;
                }
                for (Direction dir : DIRECTIONS) {
                    if (dir.getAxis() == Direction.Axis.X) {
                        int nx = x + dir.getStepX();
                        if (nx < minX || nx > maxX || carved[nx - minX]) {
                            continue;
                        }
                        sealFluidShellCell(chunk, cursor, nx, y, z, minX, maxX, minZ, maxZ, trackBlock, true, maxDy, live);
                        continue;
                    }
                    if (trackCenter && dir != Direction.DOWN) {
                        continue;
                    }
                    sealFluidShellCell(
                            chunk,
                            cursor,
                            x,
                            y + dir.getStepY(),
                            z + dir.getStepZ(),
                            minX,
                            maxX,
                            minZ,
                            maxZ,
                            trackBlock,
                            false,
                            maxDy,
                            live
                    );
                }
            }
        }
    }

    private static void sealFluidShellCell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            Block trackBlock,
            boolean vaultFace,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return;
        }
        if (vaultFace) {
            if (y < TRACK_Y + VAULT_FLOOR_DY || y > TRACK_Y + maxDy || !inVault(z - CORRIDOR_Z, y - TRACK_Y)) {
                return;
            }
        } else if (!isVaultShellCell(y, z, maxDy)) {
            return;
        }
        cursor.set(x, y, z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.is(Blocks.BEDROCK) || current.getBlock() == trackBlock) {
            return;
        }
        BlockState glass = glassForFluid(current);
        if (glass == null || current.is(glass.getBlock())) {
            return;
        }
        setCell(chunk, live, cursor, glass);
    }

    private static boolean isVaultShellCell(int y, int z, int maxDy) {
        int dy = y - TRACK_Y;
        if (dy < VAULT_FLOOR_DY - 1 || dy > maxDy + 1) {
            return false;
        }
        if (dy == VAULT_FLOOR_DY - 1) {
            return true;
        }
        return !inVault(z - CORRIDOR_Z, dy);
    }

    private static void stabilizeVaultCeiling(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = VAULT_FLOOR_DY; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                if (dy < maxDy && inVault(dz, dy + 1)) {
                    continue;
                }
                cursor.set(x, TRACK_Y + dy + 1, z);
                BlockState current = chunk.getBlockState(cursor);
                if (isBamboo(current)) {
                    clearBambooAbove(chunk, live, cursor, x, TRACK_Y + dy + 1, z);
                    continue;
                }
                BlockState stable = stableCeiling(current);
                if (stable != null) {
                    setCell(chunk, live, cursor, stable);
                }
            }
        }
    }

    private static BlockState stableCeiling(BlockState current) {
        if (current.is(Blocks.GRAVEL) || current.is(Blocks.SUSPICIOUS_GRAVEL)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (current.is(Blocks.SAND) || current.is(Blocks.SUSPICIOUS_SAND)) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (current.is(Blocks.RED_SAND)) {
            return Blocks.RED_SANDSTONE.defaultBlockState();
        }
        return null;
    }

    private static boolean isBamboo(BlockState current) {
        return current.is(Blocks.BAMBOO) || current.is(Blocks.BAMBOO_SAPLING);
    }

    private static void clearBambooAbove(
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live,
            BlockPos.MutableBlockPos cursor,
            int x,
            int startY,
            int z
    ) {
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int y = startY; y <= maxY; y++) {
            cursor.set(x, y, z);
            BlockState current = chunk.getBlockState(cursor);
            if (!isBamboo(current)) {
                return;
            }
            setCell(chunk, live, cursor, Blocks.AIR.defaultBlockState());
        }
    }

    private static BlockState glassForFluid(BlockState current) {
        if (current.getFluidState().is(FluidTags.WATER)) {
            return Blocks.BLUE_STAINED_GLASS.defaultBlockState();
        }
        if (current.getFluidState().is(FluidTags.LAVA)) {
            return Blocks.RED_STAINED_GLASS.defaultBlockState();
        }
        return null;
    }

    private static int vaultMaxDy(RotatingChunkGenerator rotating, int x) {
        ChunkGenerator delegate = rotating.delegates().get(rotating.layout().delegateIndex(
                BandLayout.regionOfBlockX(x, rotating.bandSize())
        ));
        if (!(delegate instanceof SlicedOverworldChunkGenerator sliced)) {
            return VAULT_APEX_DY;
        }
        OverworldSlice slice = sliced.slice();
        if (TRACK_Y < slice.targetMinY() || TRACK_Y >= slice.targetMaxExclusiveY()) {
            return -1;
        }
        return Math.min(VAULT_APEX_DY, slice.targetMaxExclusiveY() - 1 - TRACK_Y);
    }


    private static void writeTrack(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            Block trackBlock,
            @javax.annotation.Nullable ServerLevel live
    ) {
        cursor.set(x, TRACK_Y, CORRIDOR_Z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.is(Blocks.BEDROCK)) {
            return;
        }
        if (current.getBlock() == trackBlock) {
            dryTrack(chunk, live, cursor, current);
            return;
        }
        BlockState track = trackBlock.defaultBlockState()
                .setValue(TrackBlock.SHAPE, TrackShape.XO)
                .setValue(TrackBlock.HAS_BE, false);
        if (track.hasProperty(TrackBlock.WATERLOGGED)) {
            track = track.setValue(TrackBlock.WATERLOGGED, false);
        }
        setCell(chunk, live, cursor, track);
    }

    private static void dryTrack(
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live,
            BlockPos.MutableBlockPos cursor,
            BlockState current
    ) {
        if (!current.hasProperty(TrackBlock.WATERLOGGED) || !current.getValue(TrackBlock.WATERLOGGED)) {
            return;
        }
        setCell(chunk, live, cursor, current.setValue(TrackBlock.WATERLOGGED, false));
    }

    private static void clearTunnelCell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            Block trackBlock,
            @javax.annotation.Nullable ServerLevel live
    ) {
        cursor.set(x, y, z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.is(Blocks.BEDROCK) || current.isAir()) {
            return;
        }
        if (current.getBlock() == trackBlock) {
            dryTrack(chunk, live, cursor, current);
            return;
        }
        setCell(chunk, live, cursor, Blocks.AIR.defaultBlockState());
    }

    private static void setCell(
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live,
            BlockPos.MutableBlockPos cursor,
            BlockState state
    ) {
        if (live != null) {
            live.setBlock(cursor.immutable(), state, Block.UPDATE_CLIENTS);
            return;
        }
        chunk.setBlockState(cursor, state, false);
    }

}
