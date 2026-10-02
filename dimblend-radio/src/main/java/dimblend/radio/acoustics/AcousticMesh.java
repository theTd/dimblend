package dimblend.radio.acoustics;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dimblend.radio.acoustics.terrain.SectionQuads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import dev.ryanhcode.sable.companion.math.Pose3d;

/** Coarse one-metre surfaces, with coplanar faces merged before upload. */
public final class AcousticMesh {
    public record Data(float[] vertices, int[] triangles, int[] materials, Vec3 origin) { }
    private float[] vertices = new float[4096];
    private int[] triangles = new int[4096], materials = new int[1024];
    private int vertexCount, triangleCount;
    private final Vec3 origin;

    public AcousticMesh(Vec3 origin) { this.origin = origin; }

    public void append(BlockGetter blocks, AABB bounds, BlockPos emitter, Pose3d pose) {
        append(blocks, bounds, emitter, pose, null);
    }

    /**
     * @param skipSections render-mesh-covered section keys; their cells read as air so the voxel
     *                     filler only patches sections the mirror lacks. Boundary faces toward a
     *                     covered section are still generated, keeping the gaps sealed. Where both
     *                     sides of a section border are solid this creates phantom faces buried
     *                     inside solid volume — unreachable by nearest-hit rays, so acoustically
     *                     inert, at the price of a few extra triangles per section border.
     */
    public void append(BlockGetter blocks, AABB bounds, BlockPos emitter, Pose3d pose,
            Set<Long> skipSections) {
        int[] min = {(int) Math.floor(bounds.minX), Math.max(blocks.getMinBuildHeight(), (int) Math.floor(bounds.minY)), (int) Math.floor(bounds.minZ)};
        int[] size = {(int) Math.ceil(bounds.maxX) - min[0], Math.min(blocks.getMaxBuildHeight(), (int) Math.ceil(bounds.maxY)) - min[1], (int) Math.ceil(bounds.maxZ) - min[2]};
        if (size[0] <= 0 || size[1] <= 0 || size[2] <= 0) return;
        byte[] cells = new byte[Math.multiplyExact(Math.multiplyExact(size[0], size[1]), size[2])];
        Map<BlockState, Byte> kinds = new HashMap<>();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < size[2]; z++) for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) {
            pos.set(min[0] + x, min[1] + y, min[2] + z);
            if (pos.equals(emitter)) continue;
            if (skipSections != null
                    && skipSections.contains(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4))) {
                continue;
            }
            BlockState state = blocks.getBlockState(pos);
            Byte cached = kinds.get(state);
            if (cached == null) {
                if (state.isAir()) cached = 0;
                else {
                    boolean empty;
                    try { empty = state.getCollisionShape(blocks, pos, CollisionContext.empty()).isEmpty(); }
                    catch (RuntimeException error) { empty = false; }
                    float reflectivity = AcousticRaycaster.reflectivity(state);
                    cached = empty ? 0 : (byte) (reflectivity < 0.2 ? 1 : reflectivity < 0.3 ? 2 : reflectivity < 0.5 ? 3 : reflectivity < 0.8 ? 4 : 5);
                }
                kinds.put(state, cached);
            }
            cells[index(x, y, z, size)] = cached;
        }
        appendCells(cells, min, size, pose);
    }

    /** Greedy surface extraction. Indexed strides avoid three temporary coordinates per voxel. */
    void appendCells(byte[] cells, int[] min, int[] size, Pose3d pose) {
        int[] stride = {1, size[0], size[0] * size[1]};
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            int width = size[u], height = size[v];
            int[] mask = new int[width * height];
            for (int plane = 0; plane <= size[axis]; plane++) {
                for (int j = 0; j < height; j++) for (int i = 0; i < width; i++) {
                    int index = plane * stride[axis] + i * stride[u] + j * stride[v];
                    int b = plane < size[axis] ? cells[index] : 0;
                    int a = plane > 0 ? cells[index - stride[axis]] : 0;
                    mask[j * width + i] = a != 0 && b == 0 ? a : a == 0 && b != 0 ? -b : 0;
                }
                for (int j = 0; j < height; j++) for (int i = 0; i < width;) {
                    int material = mask[j * width + i];
                    if (material == 0) { i++; continue; }
                    int w = 1;
                    while (i + w < width && mask[j * width + i + w] == material) w++;
                    int h = 1;
                    outer: while (j + h < height) {
                        for (int k = 0; k < w; k++) if (mask[(j + h) * width + i + k] != material) break outer;
                        h++;
                    }
                    double[] p = {min[0], min[1], min[2]}; p[axis] += plane; p[u] += i; p[v] += j;
                    double[] du = new double[3], dv = new double[3]; du[u] = w; dv[v] = h;
                    quad(p, du, dv, material, pose);
                    for (int y = 0; y < h; y++) Arrays.fill(mask, (j + y) * width + i, (j + y) * width + i + w, 0);
                    i += w;
                }
            }
        }
    }

    /**
     * Appends decoded render-section geometry: exact model surfaces instead of voxel cells.
     * Quads owned by the emitter's own block are dropped — the same block-level exclusion as the
     * voxel path and the CPU raycaster (neighbouring faces touching the emitter are kept).
     * Section-local vertices are rebased to this mesh's origin; the section origin and the mesh
     * origin are both 16-aligned, so the rebase is exact integer arithmetic on top of the
     * quantized floats.
     */
    public void appendSections(List<SectionQuads> sections, BlockPos emitter) {
        for (SectionQuads section : sections) {
            float[] decoded = section.vertices();
            for (int q = 0; q < section.quadCount(); q++) {
                if (emitter != null && section.ownerX(q) == emitter.getX()
                        && section.ownerY(q) == emitter.getY() && section.ownerZ(q) == emitter.getZ()) {
                    continue;
                }
                int base = q * 12;
                boolean first = validTriangle(decoded, base, base + 3, base + 6);
                boolean second = validTriangle(decoded, base, base + 6, base + 9);
                if (!first && !second) continue;
                ensure(12, 6, 2);
                int baseVertex = vertexCount / 3;
                for (int corner = 0; corner < 4; corner++) {
                    vertices[vertexCount++] = (float) (decoded[base + corner * 3] + section.originX() - origin.x);
                    vertices[vertexCount++] = (float) (decoded[base + corner * 3 + 1] + section.originY() - origin.y);
                    vertices[vertexCount++] = (float) (decoded[base + corner * 3 + 2] + section.originZ() - origin.z);
                }
                if (first) appendTriangle(baseVertex, baseVertex + 1, baseVertex + 2, section.materials()[q]);
                if (second) appendTriangle(baseVertex, baseVertex + 2, baseVertex + 3, section.materials()[q]);
            }
        }
    }

    /** Validate the actual GPU triangles, including collapsed corners of triangle-shaped quads. */
    public static boolean validTriangle(float[] vertices, int a, int b, int c) {
        for (int axis = 0; axis < 3; axis++) {
            if (!Float.isFinite(vertices[a + axis]) || !Float.isFinite(vertices[b + axis])
                    || !Float.isFinite(vertices[c + axis])) return false;
        }
        double ux = vertices[b] - (double) vertices[a], uy = vertices[b+1] - (double) vertices[a+1], uz = vertices[b+2] - (double) vertices[a+2];
        double vx = vertices[c] - (double) vertices[a], vy = vertices[c+1] - (double) vertices[a+1], vz = vertices[c+2] - (double) vertices[a+2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return nx * nx + ny * ny + nz * nz >= 1e-6;
    }

    private void appendTriangle(int a, int b, int c, int material) {
        materials[triangleCount / 3] = material;
        triangles[triangleCount++] = a;
        triangles[triangleCount++] = b;
        triangles[triangleCount++] = c;
    }

    private static int index(int x, int y, int z, int[] size) { return (z * size[1] + y) * size[0] + x; }
    private void quad(double[] p, double[] u, double[] v, int material, Pose3d pose) {
        ensure(12, 6, 2);
        int base = vertexCount / 3;
        for (int corner = 0; corner < 4; corner++) {
            double a = corner == 1 || corner == 2 ? 1 : 0, b = corner >= 2 ? 1 : 0;
            Vec3 point = new Vec3(p[0] + a * u[0] + b * v[0], p[1] + a * u[1] + b * v[1], p[2] + a * u[2] + b * v[2]);
            if (pose != null) point = pose.transformPosition(point);
            point = point.subtract(origin);
            vertices[vertexCount++] = (float) point.x; vertices[vertexCount++] = (float) point.y; vertices[vertexCount++] = (float) point.z;
        }
        int[] indices = material > 0 ? new int[] {0, 1, 2, 0, 2, 3} : new int[] {0, 2, 1, 0, 3, 2};
        for (int index : indices) triangles[triangleCount++] = base + index;
        materials[triangleCount / 3 - 2] = materials[triangleCount / 3 - 1] = Math.abs(material) - 1;
    }
    private void ensure(int v, int t, int m) {
        if (vertexCount + v > vertices.length) vertices = Arrays.copyOf(vertices, vertices.length * 2);
        if (triangleCount + t > triangles.length) triangles = Arrays.copyOf(triangles, triangles.length * 2);
        if (triangleCount / 3 + m > materials.length) materials = Arrays.copyOf(materials, materials.length * 2);
    }
    public Data data() { return new Data(Arrays.copyOf(vertices, vertexCount), Arrays.copyOf(triangles, triangleCount), Arrays.copyOf(materials, triangleCount / 3), origin); }
}
