package dimblend.radio.acoustics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Voxel ray traversal shared by the live raycaster and the frozen snapshot.
 * <p>
 * Steam Audio's transmission solver re-casts from 1 cm past every hit, alternating between the
 * listener and the source, and takes the square root of the product because hits come in pairs
 * (both faces of a wall). Vanilla {@link VoxelShape#clip} reports an immediate hit when a ray starts
 * inside a shape, so every re-cast from inside a wall hit that wall again until all transmission
 * rays were spent: any obstruction became T^4 regardless of its thickness, count or material.
 * Here only open-to-solid entries count. A ray that starts inside a solid run skips it, so each
 * wall is hit once from each side, and every hit carries the path through the run it entered: its
 * length and its transmission, layer by layer when the run mixes materials.
 */
public final class AcousticVoxelTrace {
    /** A block as the ray sees it. */
    public record Cell(VoxelShape shape, int material) {
        /** Unloaded terrain: neither open nor solid. */
        public static final Cell UNKNOWN = new Cell(Shapes.empty(), AcousticMaterials.STONE);
    }

    /** Resolves a block position; {@code null} means open (air, the excluded emitter, a foreign plot). */
    @FunctionalInterface
    public interface Lookup {
        Cell at(BlockPos pos);
    }

    /** Distance, in blocks, used to probe just inside a voxel or a shape box. */
    private static final double PROBE = 1e-4;
    private static final AABB FULL = new AABB(0, 0, 0, 1, 1, 1);

    public static Cell cell(BlockState state, BlockGetter getter, BlockPos pos) {
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(getter, pos, CollisionContext.empty());
        } catch (RuntimeException unsupportedShape) {
            // A modded shape that requires a live Level falls back to its solid voxel.
            shape = Shapes.block();
        }
        return new Cell(shape, AcousticBlockMaterials.of(state));
    }

    public static AcousticRay cast(Vec3 from, Vec3 to, Lookup lookup) {
        double length = from.distanceTo(to);
        if (length < 1e-7) {
            return AcousticRay.miss(to);
        }
        Vec3 direction = to.subtract(from).scale(1 / length);
        SolidRun run = new SolidRun(from, to);
        return BlockGetter.traverseBlocks(from, to, lookup, (cells, pos) -> {
            Cell cell = cells.at(pos);
            if (cell == Cell.UNKNOWN) {
                return AcousticRay.unknown(run.entry(pos));
            }
            if (cell == null || cell.shape().isEmpty()) {
                run.open();
                return null;
            }
            if (run.skip(pos, cell.shape())) {
                return null;
            }
            // Exact box entry; VoxelShape#clip would also report a hit 0.1% of the ray ahead.
            BlockHitResult hit = AABB.clip(cell.shape().toAabbs(), from, to, pos);
            if (hit == null) {
                return null;
            }
            Vec3 entry = hit.getLocation();
            AcousticMaterials.Path path = path(entry, direction, lookup, cell.material());
            return new AcousticRay(AcousticRay.Kind.HIT, entry, Vec3.atLowerCornerOf(hit.getDirection().getNormal()),
                    cell.material(), (float) path.length(), path.transmission());
        }, cells -> AcousticRay.miss(to));
    }

    /**
     * The layers of the solid run entered at {@code entry} (of {@code material}), up to the
     * material model's range: each voxel adds the length the ray spends in it.
     */
    static AcousticMaterials.Path path(Vec3 entry, Vec3 direction, Lookup lookup, int material) {
        double reach = AcousticMaterials.MAX_THICKNESS;
        Vec3 start = entry.add(direction.scale(PROBE));
        Vec3 end = start.add(direction.scale(reach));
        AcousticMaterials.Path path = new AcousticMaterials.Path();
        path.add(material, PROBE);
        double[] exit = {0};
        BlockGetter.traverseBlocks(start, end, lookup, (cells, pos) -> {
            Cell cell = cells.at(pos);
            if (cell == null || cell == Cell.UNKNOWN) {
                return Boolean.TRUE;
            }
            double[] span = AcousticRaycaster.clipRange(start, end, new AABB(pos));
            if (span == null) {
                return null;
            }
            AABB box = containing(cell.shape(), pos, at(start, end, span[0] + Math.min(PROBE / reach, (span[1] - span[0]) / 2)));
            if (box == null) {
                return Boolean.TRUE;
            }
            double[] inside = AcousticRaycaster.clipRange(start, end, box.move(pos));
            double far = inside == null ? span[1] : inside[1];
            path.add(cell.material(), (far - exit[0]) * reach);
            exit[0] = far;
            // Leaving the box before the voxel boundary ends the run inside this voxel.
            return far < span[1] - 1e-9 ? Boolean.TRUE : null;
        }, cells -> Boolean.TRUE);
        return path;
    }

    /** The shape box (in block-local coordinates) containing {@code point}, or {@code null}. */
    private static AABB containing(VoxelShape shape, BlockPos pos, Vec3 point) {
        if (shape == Shapes.block()) {
            return FULL;
        }
        double x = point.x - pos.getX(), y = point.y - pos.getY(), z = point.z - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY && z >= box.minZ && z <= box.maxZ) {
                return box;
            }
        }
        return null;
    }

    private static Vec3 at(Vec3 from, Vec3 to, double t) {
        return new Vec3(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
    }

    /** Tracks whether the ray is still inside the solid run containing its origin. */
    private static final class SolidRun {
        private final Vec3 from, to;
        private final double probe;
        private boolean first = true, inside;

        SolidRun(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            probe = PROBE / from.distanceTo(to);
        }

        void open() {
            first = false;
            inside = false;
        }

        /** True while the voxel continues the solid the ray started in. */
        boolean skip(BlockPos pos, VoxelShape shape) {
            if (!first && !inside) {
                return false;
            }
            first = false;
            double[] span = AcousticRaycaster.clipRange(from, to, new AABB(pos));
            double t = span == null ? 0 : span[0] + Math.min(probe, (span[1] - span[0]) / 2);
            inside = containing(shape, pos, at(from, to, t)) != null;
            return inside;
        }

        Vec3 entry(BlockPos pos) {
            double[] span = AcousticRaycaster.clipRange(from, to, new AABB(pos));
            return span == null ? from : at(from, to, span[0]);
        }
    }

    private AcousticVoxelTrace() { }
}
