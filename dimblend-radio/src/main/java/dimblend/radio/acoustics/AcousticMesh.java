package dimblend.radio.acoustics;

import java.util.Arrays;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Coarse one-metre surfaces, with coplanar faces merged before upload. */
public final class AcousticMesh {
    public record Data(float[] vertices, int[] triangles, int[] materials, Vec3 origin) {
        public static Data empty(Vec3 origin) { return new Data(new float[0], new int[0], new int[0], origin); }

        public int triangleCount() { return triangles.length / 3; }

        /** {@code first} followed by {@code second} (same origin; may be null) as one mesh. */
        public static Data concat(Data first, Data second) {
            if (second == null || second.triangles.length == 0) return first;
            if (!second.origin.equals(first.origin)) throw new IllegalArgumentException("Meshes must share one origin");
            if (first.triangles.length == 0) return second;
            float[] vertices = Arrays.copyOf(first.vertices, first.vertices.length + second.vertices.length);
            System.arraycopy(second.vertices, 0, vertices, first.vertices.length, second.vertices.length);
            int[] triangles = Arrays.copyOf(first.triangles, first.triangles.length + second.triangles.length);
            int baseVertex = first.vertices.length / 3;
            for (int i = 0; i < second.triangles.length; i++) triangles[first.triangles.length + i] = baseVertex + second.triangles[i];
            int[] materials = Arrays.copyOf(first.materials, first.materials.length + second.materials.length);
            System.arraycopy(second.materials, 0, materials, first.materials.length, second.materials.length);
            return new Data(vertices, triangles, materials, first.origin);
        }
    }

    /** Blocks addressable a section at a time: a voxel fill then reads each palette once. */
    public interface SectionSource extends BlockGetter {
        /** States of section {@code (x, y, z)}; {@code null} when it holds only air or was not captured. */
        PalettedContainer<BlockState> section(int x, int y, int z);
    }

    /**
     * Scratch arrays reused across the mesh builds of one owner (one thread at a time), so terrain
     * rebuilds stop allocating a cell volume and a face mask each time.
     */
    public static final class Workspace {
        private byte[] cells = new byte[0];
        private int[] mask = new int[0];

        /** Cleared cells for a volume of {@code length}. */
        byte[] cells(int length) {
            if (cells.length < length) cells = new byte[length];
            else Arrays.fill(cells, 0, length, (byte) 0);
            return cells;
        }

        /** The greedy pass overwrites every mask entry of a plane before reading it. */
        int[] mask(int length) {
            if (mask.length < length) mask = new int[length];
            return mask;
        }
    }

    private static final int[] FRONT = {0, 1, 2, 0, 2, 3}, BACK = {0, 2, 1, 0, 3, 2};
    private float[] vertices = new float[4096];
    private int[] triangles = new int[4096], materials = new int[1024];
    private int vertexCount, triangleCount;
    private final Vec3 origin;
    private final Workspace workspace;
    private final double[] corner = new double[3];

    public AcousticMesh(Vec3 origin) { this(origin, new Workspace()); }

    public AcousticMesh(Vec3 origin, Workspace workspace) {
        this.origin = origin;
        this.workspace = workspace;
    }

    /** Voxel surfaces of {@code bounds}; the {@code emitter} cell (may be null) reads as air. */
    public void append(BlockGetter blocks, AABB bounds, BlockPos emitter) {
        int[] min = {(int) Math.floor(bounds.minX), Math.max(blocks.getMinBuildHeight(), (int) Math.floor(bounds.minY)), (int) Math.floor(bounds.minZ)};
        int[] size = {(int) Math.ceil(bounds.maxX) - min[0], Math.min(blocks.getMaxBuildHeight(), (int) Math.ceil(bounds.maxY)) - min[1], (int) Math.ceil(bounds.maxZ) - min[2]};
        if (size[0] <= 0 || size[1] <= 0 || size[2] <= 0) return;
        byte[] cells = workspace.cells(Math.multiplyExact(Math.multiplyExact(size[0], size[1]), size[2]));
        if (blocks instanceof SectionSource sections) fillSections(sections, cells, min, size);
        else fillCells(blocks, cells, min, size);
        if (emitter != null) {
            int x = emitter.getX() - min[0], y = emitter.getY() - min[1], z = emitter.getZ() - min[2];
            if (x >= 0 && y >= 0 && z >= 0 && x < size[0] && y < size[1] && z < size[2]) cells[index(x, y, z, size)] = 0;
        }
        appendCells(cells, min, size);
    }

    /** Live blocks (any getter): one lookup per cell. */
    private static void fillCells(BlockGetter blocks, byte[] cells, int[] min, int[] size) {
        var pos = new BlockPos.MutableBlockPos();
        BlockState last = null;
        byte kind = 0;
        for (int z = 0; z < size[2]; z++) for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) {
            pos.set(min[0] + x, min[1] + y, min[2] + z);
            BlockState state = blocks.getBlockState(pos);
            if (state != last) {
                last = state;
                kind = AcousticSurfaceKinds.of(state, blocks, pos);
            }
            cells[index(x, y, z, size)] = kind;
        }
    }

    /** Frozen palettes: each section is looked up once, then read cell by cell; air sections are skipped. */
    private static void fillSections(SectionSource blocks, byte[] cells, int[] min, int[] size) {
        var pos = new BlockPos.MutableBlockPos();
        int maxX = min[0] + size[0] - 1, maxY = min[1] + size[1] - 1, maxZ = min[2] + size[2] - 1;
        BlockState last = null;
        byte kind = 0;
        for (int sz = min[2] >> 4; sz <= maxZ >> 4; sz++) for (int sy = min[1] >> 4; sy <= maxY >> 4; sy++) {
            for (int sx = min[0] >> 4; sx <= maxX >> 4; sx++) {
                PalettedContainer<BlockState> states = blocks.section(sx, sy, sz);
                if (states == null) continue;
                int x0 = Math.max(min[0], sx << 4), x1 = Math.min(maxX, (sx << 4) + 15);
                int y0 = Math.max(min[1], sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                int z0 = Math.max(min[2], sz << 4), z1 = Math.min(maxZ, (sz << 4) + 15);
                for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
                    int row = index(x0 - min[0], y - min[1], z - min[2], size) - x0;
                    for (int x = x0; x <= x1; x++) {
                        BlockState state = states.get(x & 15, y & 15, z & 15);
                        if (state != last) {
                            last = state;
                            kind = AcousticSurfaceKinds.of(state, blocks, pos.set(x, y, z));
                        }
                        cells[row + x] = kind;
                    }
                }
            }
        }
    }

    /** Greedy surface extraction. Indexed strides avoid three temporary coordinates per voxel. */
    void appendCells(byte[] cells, int[] min, int[] size) {
        int[] stride = {1, size[0], size[0] * size[1]};
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            int width = size[u], height = size[v];
            int[] mask = workspace.mask(width * height);
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
                    quad(min, axis, plane, u, i, w, v, j, h, material);
                    for (int y = 0; y < h; y++) Arrays.fill(mask, (j + y) * width + i, (j + y) * width + i + w, 0);
                    i += w;
                }
            }
        }
    }

    /**
     * Appends a mesh built in a moving structure's own frame where {@code pose} places it now, so
     * a structure is voxelized once and only its vertices follow its motion.
     */
    public void appendPlaced(Data local, Pose3dc pose) {
        float[] source = local.vertices();
        ensure(source.length, local.triangles().length, local.materials().length);
        int baseVertex = vertexCount / 3;
        Vec3 from = local.origin();
        var point = new Vector3d();
        for (int i = 0; i < source.length; i += 3) {
            point.set(from.x + source[i], from.y + source[i + 1], from.z + source[i + 2]);
            pose.transformPosition(point);
            vertices[vertexCount++] = (float) (point.x - origin.x);
            vertices[vertexCount++] = (float) (point.y - origin.y);
            vertices[vertexCount++] = (float) (point.z - origin.z);
        }
        System.arraycopy(local.materials(), 0, materials, triangleCount / 3, local.materials().length);
        for (int index : local.triangles()) triangles[triangleCount++] = baseVertex + index;
    }

    private static int index(int x, int y, int z, int[] size) { return (z * size[1] + y) * size[0] + x; }

    /** One merged face: {@code w} cells along axis {@code u} from cell {@code i}, {@code h} along {@code v} from {@code j}. */
    private void quad(int[] min, int axis, int plane, int u, int i, int w, int v, int j, int h, int material) {
        ensure(12, 6, 2);
        int base = vertexCount / 3;
        for (int c = 0; c < 4; c++) {
            corner[0] = min[0];
            corner[1] = min[1];
            corner[2] = min[2];
            corner[axis] += plane;
            corner[u] += i + (c == 1 || c == 2 ? w : 0);
            corner[v] += j + (c >= 2 ? h : 0);
            vertices[vertexCount++] = (float) (corner[0] - origin.x);
            vertices[vertexCount++] = (float) (corner[1] - origin.y);
            vertices[vertexCount++] = (float) (corner[2] - origin.z);
        }
        for (int index : material > 0 ? FRONT : BACK) triangles[triangleCount++] = base + index;
        materials[triangleCount / 3 - 2] = materials[triangleCount / 3 - 1] = Math.abs(material) - 1;
    }

    private void ensure(int v, int t, int m) {
        if (vertexCount + v > vertices.length) vertices = Arrays.copyOf(vertices, Math.max(vertices.length * 2, vertexCount + v));
        if (triangleCount + t > triangles.length) triangles = Arrays.copyOf(triangles, Math.max(triangles.length * 2, triangleCount + t));
        if (triangleCount / 3 + m > materials.length) materials = Arrays.copyOf(materials, Math.max(materials.length * 2, triangleCount / 3 + m));
    }

    public Data data() { return new Data(Arrays.copyOf(vertices, vertexCount), Arrays.copyOf(triangles, triangleCount), Arrays.copyOf(materials, triangleCount / 3), origin); }
}
