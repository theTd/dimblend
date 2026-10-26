package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.Sable;
import dimblend.experience.headlight.HeadlightOccluders;
import dimblend.experience.headlight.HeadlightOcclusionMath;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Camera-centred world occupancy clipmap: 128 x 64 x 128 R8 texels addressed toroidally (world coordinate mod
 * size), so a camera move only refills the bricks (= chunk sections) that entered the window. A slot whose brick
 * changed is zeroed at once (= unoccluded, never stale), then refilled nearest-first under a per-frame voxel
 * budget. Sable plot chunks are never written. Client/render thread only.
 */
final class HeadlightWorldGrid implements AutoCloseable {
    static final int SIZE_X = 128, SIZE_Y = 64, SIZE_Z = 128;
    private static final int B = HeadlightOcclusionMath.BRICK;
    private static final int NX = SIZE_X / B, NY = SIZE_Y / B, NZ = SIZE_Z / B;
    private static final int SLOTS = NX * NY * NZ;
    /** Voxel reads per frame; a palette-pruned (empty/transparent) section costs {@link #SKIP_COST}. */
    static final int VOXEL_BUDGET = 32768;
    private static final int SKIP_COST = 64;
    private static final long NONE = Long.MIN_VALUE;

    private final HeadlightVoxelTexture texture = new HeadlightVoxelTexture(SIZE_X, SIZE_Y, SIZE_Z);
    /** Brick whose data the slot currently holds ({@link #NONE} = zeros). */
    private final long[] held = new long[SLOTS];
    private final boolean[] nonZero = new boolean[SLOTS];
    private final boolean[] dirty = new boolean[SLOTS];
    private final IntArrayList voxelWrites = new IntArrayList();
    private ByteBuffer brick;
    private ByteBuffer zeros;
    private ByteBuffer single;
    private ClientLevel level;
    private int originX, originY, originZ; // window minimum, in bricks
    private boolean placed;

    HeadlightWorldGrid() { java.util.Arrays.fill(held, NONE); }

    int texture() { return texture.id(); }
    int originBlockX() { return originX * B; }
    int originBlockY() { return originY * B; }
    int originBlockZ() { return originZ * B; }

    private static long key(int bx, int by, int bz) {
        return ((long) (bx & 0x3FFFFF) << 42) | ((long) (by & 0xFFFFF) << 22) | (bz & 0x3FFFFF);
    }

    private static int slot(int bx, int by, int bz) {
        return (HeadlightOcclusionMath.wrap(bz, NZ) * NY + HeadlightOcclusionMath.wrap(by, NY)) * NX
                + HeadlightOcclusionMath.wrap(bx, NX);
    }

    private boolean inWindow(int bx, int by, int bz) {
        return placed && bx >= originX && bx < originX + NX && by >= originY && by < originY + NY
                && bz >= originZ && bz < originZ + NZ;
    }

    /** Per frame, before tracing. Returns the voxel reads spent (diagnostics). */
    int update(ClientLevel current, Vec3 camera) {
        if (level != current) {
            level = current;
            java.util.Arrays.fill(held, NONE);
            java.util.Arrays.fill(dirty, false);
            voxelWrites.clear();
            if (texture.allocated()) java.util.Arrays.fill(nonZero, true); // contents of the old level
        }
        if (brick == null) {
            brick = MemoryUtil.memAlloc(B * B * B);
            zeros = MemoryUtil.memCalloc(B * B * B);
            single = MemoryUtil.memAlloc(1);
        }
        originX = HeadlightOcclusionMath.windowOriginBrick(camera.x, NX);
        originY = HeadlightOcclusionMath.windowOriginBrick(camera.y, NY);
        originZ = HeadlightOcclusionMath.windowOriginBrick(camera.z, NZ);
        placed = true;
        int reads = 0;
        try {
            texture.ensure();
            applyVoxelWrites();
            // Candidates: slots not holding their expected brick, or invalidated. Nearest first.
            int cx = Math.floorDiv((int) Math.floor(camera.x), B);
            int cy = Math.floorDiv((int) Math.floor(camera.y), B);
            int cz = Math.floorDiv((int) Math.floor(camera.z), B);
            long[] candidates = new long[SLOTS];
            int count = 0;
            for (int bz = originZ; bz < originZ + NZ; bz++) {
                for (int by = originY; by < originY + NY; by++) {
                    for (int bx = originX; bx < originX + NX; bx++) {
                        int s = slot(bx, by, bz);
                        long k = key(bx, by, bz);
                        if (held[s] != k && held[s] != NONE) {
                            // Stale brick of another window position: clear now so it never shadows wrongly.
                            if (nonZero[s]) uploadBrick(s, zeros);
                            nonZero[s] = false;
                            held[s] = NONE;
                            dirty[s] = false;
                        }
                        if (held[s] == NONE && nonZero[s]) {
                            uploadBrick(s, zeros);
                            nonZero[s] = false;
                        }
                        if (held[s] != k || dirty[s]) {
                            int dx = bx - cx, dy = by - cy, dz = bz - cz;
                            int distance = dx * dx + dy * dy + dz * dz;
                            candidates[count++] = ((long) distance << 32) | (bx - originX) | ((by - originY) << 8)
                                    | ((long) (bz - originZ) << 16);
                        }
                    }
                }
            }
            java.util.Arrays.sort(candidates, 0, count);
            for (int i = 0; i < count && reads < VOXEL_BUDGET; i++) {
                long c = candidates[i];
                int bx = originX + (int) (c & 0xFF);
                int by = originY + (int) ((c >> 8) & 0xFF);
                int bz = originZ + (int) ((c >> 16) & 0xFF);
                reads += fill(bx, by, bz);
            }
        } finally {
            texture.finish();
        }
        return reads;
    }

    private int fill(int bx, int by, int bz) {
        int s = slot(bx, by, bz);
        boolean refill = dirty[s];
        held[s] = key(bx, by, bz);
        dirty[s] = false;
        LevelChunk chunk = level.getChunkSource().getChunk(bx, bz, false);
        int sectionIndex = level.getSectionIndexFromSectionY(by);
        // Unloaded chunks stay clear until their load event invalidates the column; plot chunks are never written.
        if (chunk == null || sectionIndex < 0 || sectionIndex >= chunk.getSections().length
                || Sable.HELPER.getContainingClient(bx, bz) != null) {
            clearIfNeeded(s, refill);
            return SKIP_COST;
        }
        var section = chunk.getSection(sectionIndex);
        if (!HeadlightOccluders.mayOcclude(section)) {
            clearIfNeeded(s, refill);
            return SKIP_COST;
        }
        brick.clear();
        boolean any = HeadlightOccluders.read(section, 0, 0, 0, B, B, B, brick, 0, B, B * B);
        if (any || nonZero[s]) uploadBrick(s, any ? brick : zeros);
        nonZero[s] = any;
        return B * B * B;
    }

    private void clearIfNeeded(int s, boolean refill) {
        if (nonZero[s] || refill) {
            if (nonZero[s]) uploadBrick(s, zeros);
            nonZero[s] = false;
        }
    }

    private void uploadBrick(int s, ByteBuffer data) {
        int x = s % NX, y = (s / NX) % NY, z = s / (NX * NY);
        data.position(0).limit(B * B * B);
        texture.upload(x * B, y * B, z * B, B, B, B, data);
    }

    private void applyVoxelWrites() {
        for (int i = 0; i < voxelWrites.size(); i++) {
            int packed = voxelWrites.getInt(i);
            single.put(0, (byte) (packed >>> 24)).position(0).limit(1);
            texture.upload(packed & 0x7F, (packed >> 7) & 0x3F, (packed >> 13) & 0x7F, 1, 1, 1, single);
        }
        voxelWrites.clear();
    }

    /** A client block write: patch the single texel if its brick is resident. */
    void blockChanged(BlockPos pos, BlockState state) {
        int bx = pos.getX() >> 4, by = pos.getY() >> 4, bz = pos.getZ() >> 4;
        if (!inWindow(bx, by, bz)) return;
        int s = slot(bx, by, bz);
        if (held[s] != key(bx, by, bz) || dirty[s]) return; // refilled from the chunk anyway
        boolean solid = HeadlightOccluders.occludes(state);
        if (voxelWrites.size() > 4096) {
            dirty[s] = true;
            return;
        }
        nonZero[s] |= solid;
        int x = HeadlightOcclusionMath.wrap(pos.getX(), SIZE_X);
        int y = HeadlightOcclusionMath.wrap(pos.getY(), SIZE_Y);
        int z = HeadlightOcclusionMath.wrap(pos.getZ(), SIZE_Z);
        voxelWrites.add(x | (y << 7) | (z << 13) | ((solid ? 0xFF : 0) << 24));
    }

    /** Chunk (re)load or unload: refill the column's resident bricks. */
    void chunkChanged(int chunkX, int chunkZ) {
        if (!placed) return;
        for (int by = originY; by < originY + NY; by++) {
            if (!inWindow(chunkX, by, chunkZ)) return;
            int s = slot(chunkX, by, chunkZ);
            if (held[s] == key(chunkX, by, chunkZ)) dirty[s] = true;
        }
    }

    @Override
    public void close() {
        texture.close();
        java.util.Arrays.fill(held, NONE);
        java.util.Arrays.fill(nonZero, false);
        java.util.Arrays.fill(dirty, false);
        voxelWrites.clear();
        level = null;
        placed = false;
        if (brick != null) {
            MemoryUtil.memFree(brick);
            MemoryUtil.memFree(zeros);
            MemoryUtil.memFree(single);
        }
        brick = zeros = single = null;
    }
}
