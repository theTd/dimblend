package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;

/**
 * Plot-space occupancy of up to four Sable vehicles (the lamps' own sub-levels first), packed as 64^3 slots
 * along z of one 64 x 64 x 256 R8 texture. A slot covers the occluding sections of the vehicle's loaded plot
 * chunks, clamped to 64 blocks per axis around the lamp; it is rebuilt only when its vehicle changes outside
 * the covered box (in-box block writes patch a single texel). Client/render thread only.
 */
final class HeadlightVehicleGrids implements AutoCloseable {
    static final int SLOT = 64;
    static final int SLOTS = 4;
    private static final int VOLUME = SLOT * SLOT * SLOT;

    private final HeadlightVoxelTexture texture = new HeadlightVoxelTexture(SLOT, SLOT, SLOT * SLOTS);
    private final ClientSubLevel[] held = new ClientSubLevel[SLOTS];
    private final boolean[] dirty = new boolean[SLOTS];
    private final int[][] origin = new int[SLOTS][3];
    private final int[][] size = new int[SLOTS][3];
    private final ByteBuffer[] mirror = new ByteBuffer[SLOTS];
    private final long[] lastUsed = new long[SLOTS];
    private final int[] active = new int[SLOTS];
    private final Matrix4f[] transforms = new Matrix4f[SLOTS];
    private final boolean[] pendingUpload = new boolean[SLOTS];
    private int activeCount;
    private long frame;
    private int rebuiltVoxels;

    HeadlightVehicleGrids() {
        for (int i = 0; i < SLOTS; i++) transforms[i] = new Matrix4f();
    }

    int texture() { return texture.id(); }
    int count() { return activeCount; }
    int slot(int i) { return active[i]; }
    Matrix4f transform(int i) { return transforms[active[i]]; }
    int[] size(int i) { return size[active[i]]; }

    /**
     * Assigns slots to {@code wanted} (at most {@link #SLOTS}, in priority order), rebuilds dirty ones and
     * computes camera-relative world to slot-grid transforms. {@code focus} is a world point per vehicle
     * (lamp lens) that bounds oversized vehicles. Returns voxels read.
     */
    int update(List<ClientSubLevel> wanted, List<Vec3> focus, Vec3 camera) {
        frame++;
        rebuiltVoxels = 0;
        activeCount = 0;
        try {
            texture.ensure();
            for (int w = 0; w < wanted.size() && activeCount < SLOTS; w++) {
                ClientSubLevel sub = wanted.get(w);
                int s = find(sub);
                if (s < 0) {
                    s = evict();
                    held[s] = sub;
                    dirty[s] = true;
                }
                lastUsed[s] = frame;
                var pose = sub.renderPose();
                if (dirty[s]) rebuild(s, sub, pose.transformPositionInverse(focus.get(w)));
                if (pendingUpload[s]) upload(s);
                if (size[s][0] == 0) continue; // nothing occluding on this vehicle
                computeTransform(s, sub, camera);
                active[activeCount++] = s;
            }
        } finally {
            texture.finish();
        }
        return rebuiltVoxels;
    }

    private int find(ClientSubLevel sub) {
        for (int i = 0; i < SLOTS; i++) if (held[i] == sub) return i;
        return -1;
    }

    private int evict() {
        int best = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (held[i] == null) return i;
            if (lastUsed[i] < lastUsed[best]) best = i;
        }
        return best;
    }

    private void rebuild(int s, ClientSubLevel sub, Vec3 focus) {
        dirty[s] = false;
        if (mirror[s] == null) mirror[s] = MemoryUtil.memCalloc(VOLUME);
        ByteBuffer data = mirror[s];
        MemoryUtil.memSet(data, 0);
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        var holders = sub.getPlot().getLoadedChunks();
        for (var holder : holders) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            var sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (!HeadlightOccluders.mayOcclude(sections[i])) continue;
                int x = chunk.getPos().getMinBlockX(), y = chunk.getSectionYFromSectionIndex(i) << 4, z = chunk.getPos().getMinBlockZ();
                min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
                max[0] = Math.max(max[0], x + 16); max[1] = Math.max(max[1], y + 16); max[2] = Math.max(max[2], z + 16);
            }
        }
        if (min[0] == Integer.MAX_VALUE) {
            size[s][0] = size[s][1] = size[s][2] = 0;
            pendingUpload[s] = true;
            return;
        }
        double[] f = {focus.x, focus.y, focus.z};
        for (int k = 0; k < 3; k++) {
            if (max[k] - min[k] > SLOT) {
                int lo = (int) Math.floor(f[k]) - SLOT / 2;
                min[k] = Math.max(min[k], Math.min(lo, max[k] - SLOT));
                max[k] = min[k] + SLOT;
            }
            origin[s][k] = min[k];
            size[s][k] = max[k] - min[k];
        }
        for (var holder : holders) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            int cx = chunk.getPos().getMinBlockX(), cz = chunk.getPos().getMinBlockZ();
            if (cx + 16 <= min[0] || cx >= max[0] || cz + 16 <= min[2] || cz >= max[2]) continue;
            var sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                int cy = chunk.getSectionYFromSectionIndex(i) << 4;
                if (cy + 16 <= min[1] || cy >= max[1] || !HeadlightOccluders.mayOcclude(sections[i])) continue;
                int x0 = Math.max(min[0], cx), y0 = Math.max(min[1], cy), z0 = Math.max(min[2], cz);
                int x1 = Math.min(max[0], cx + 16), y1 = Math.min(max[1], cy + 16), z1 = Math.min(max[2], cz + 16);
                int base = (x0 - min[0]) + (y0 - min[1]) * SLOT + (z0 - min[2]) * SLOT * SLOT;
                HeadlightOccluders.read(sections[i], x0 - cx, y0 - cy, z0 - cz, x1 - cx, y1 - cy, z1 - cz,
                        data, base, SLOT, SLOT * SLOT);
                rebuiltVoxels += (x1 - x0) * (y1 - y0) * (z1 - z0);
            }
        }
        pendingUpload[s] = true;
    }

    private void upload(int s) {
        pendingUpload[s] = false;
        ByteBuffer data = mirror[s];
        if (data == null) return;
        data.position(0).limit(VOLUME);
        texture.upload(0, 0, s * SLOT, SLOT, SLOT, SLOT, data);
    }

    /** Camera-relative world position to slot cells: g = A^-1 (r - (T(origin) - camera)). */
    private void computeTransform(int s, ClientSubLevel sub, Vec3 camera) {
        var pose = sub.renderPose();
        Vector3d o = new Vector3d(origin[s][0], origin[s][1], origin[s][2]);
        Vector3d p0 = pose.transformPosition(o, new Vector3d());
        Vector3d ex = pose.transformPosition(new Vector3d(o).add(1, 0, 0), new Vector3d()).sub(p0);
        Vector3d ey = pose.transformPosition(new Vector3d(o).add(0, 1, 0), new Vector3d()).sub(p0);
        Vector3d ez = pose.transformPosition(new Vector3d(o).add(0, 0, 1), new Vector3d()).sub(p0);
        Matrix3d inverse = new Matrix3d(ex, ey, ez).invert();
        Vector3d translation = inverse.transform(new Vector3d(p0.x - camera.x, p0.y - camera.y, p0.z - camera.z)).negate();
        transforms[s].set(
                (float) inverse.m00, (float) inverse.m01, (float) inverse.m02, 0f,
                (float) inverse.m10, (float) inverse.m11, (float) inverse.m12, 0f,
                (float) inverse.m20, (float) inverse.m21, (float) inverse.m22, 0f,
                (float) translation.x, (float) translation.y, (float) translation.z, 1f);
    }

    /** A block write in {@code sub}'s plot: patch the slot texel, or rebuild when it falls outside the box. */
    void blockChanged(ClientSubLevel sub, BlockPos pos, BlockState state) {
        int s = find(sub);
        if (s < 0 || dirty[s]) return;
        boolean solid = HeadlightOccluders.occludes(state);
        int x = pos.getX() - origin[s][0], y = pos.getY() - origin[s][1], z = pos.getZ() - origin[s][2];
        if (size[s][0] == 0 || x < 0 || y < 0 || z < 0 || x >= size[s][0] || y >= size[s][1] || z >= size[s][2]) {
            if (solid) dirty[s] = true;
            return;
        }
        int index = x + y * SLOT + z * SLOT * SLOT;
        byte value = solid ? HeadlightOccluders.SOLID : HeadlightOccluders.CLEAR;
        if (mirror[s].get(index) == value) return;
        mirror[s].put(index, value);
        pendingUpload[s] = true;
    }

    /** A plot chunk of {@code sub} loaded/unloaded. */
    void invalidate(ClientSubLevel sub) {
        int s = find(sub);
        if (s >= 0) dirty[s] = true;
    }

    @Override
    public void close() {
        texture.close();
        for (int i = 0; i < SLOTS; i++) {
            if (mirror[i] != null) MemoryUtil.memFree(mirror[i]);
            mirror[i] = null;
            held[i] = null;
            dirty[i] = false;
            pendingUpload[i] = false;
            size[i][0] = size[i][1] = size[i][2] = 0;
        }
        activeCount = 0;
    }
}
