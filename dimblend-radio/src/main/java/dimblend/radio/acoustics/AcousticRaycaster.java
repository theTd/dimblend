package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Loaded-only geometry queries. Call on the level's owning thread. */
public final class AcousticRaycaster {
    private final Level level;
    private final BlockPos emitter;

    public AcousticRaycaster(Level level, BlockPos emitter) {
        this.level = level;
        this.emitter = emitter;
    }

    public AcousticRay cast(Vec3 from, Vec3 to) {
        AcousticRay nearest = castLocal(from, to, null);
        double distance = from.distanceToSqr(nearest.position());
        BoundingBox3d box = new BoundingBox3d(from, to).expand(0.001);
        for (SubLevelAccess structure : SableCompanion.INSTANCE.getAllIntersecting(level, box)) {
            // Crop to the structure's world bounds before entering its distant plot.
            double[] range = clipRange(from, to, structure.boundingBox().toMojang().inflate(0.001));
            if (range == null) {
                continue;
            }
            Pose3dc pose = structure.logicalPose();
            Vec3 delta = to.subtract(from);
            Vec3 localFrom = pose.transformPositionInverse(from.add(delta.scale(range[0])));
            Vec3 localTo = pose.transformPositionInverse(from.add(delta.scale(range[1])));
            AcousticRay local = castLocal(localFrom, localTo, structure);
            if (local.kind() == AcousticRay.Kind.MISS) {
                continue;
            }
            Vec3 worldHit = pose.transformPosition(local.position());
            double hitDistance = from.distanceToSqr(worldHit);
            if (hitDistance < distance || nearest.kind() == AcousticRay.Kind.MISS) {
                nearest = projectHit(local, pose);
                distance = hitDistance;
            }
        }
        return nearest;
    }

    public static AcousticRay projectHit(AcousticRay local, Pose3dc pose) {
        return new AcousticRay(local.kind(), pose.transformPosition(local.position()),
                pose.transformNormal(local.normal()).normalize(), local.reflectivity(), local.thickness());
    }

    private AcousticRay castLocal(Vec3 from, Vec3 to, SubLevelAccess structure) {
        return AcousticVoxelTrace.cast(from, to, pos -> {
            if (pos.equals(emitter) || level.isOutsideBuildHeight(pos)) {
                return null;
            }
            if (structure != null && SableCompanion.INSTANCE.getContaining(level, pos) != structure) {
                return null; // Never enter a neighboring plot while tracing this structure.
            }
            var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                    ChunkStatus.FULL, false);
            if (chunk == null || chunk instanceof EmptyLevelChunk) {
                // Sable represents unallocated sparse plot chunks with its empty chunk.
                return structure != null ? null : AcousticVoxelTrace.Cell.UNKNOWN;
            }
            BlockState state = chunk.getBlockState(pos);
            return state.isAir() ? null : AcousticVoxelTrace.cell(state, level, pos);
        });
    }

    public static float reflectivity(BlockState state) {
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
            return 0.15f;
        }
        if (state.is(BlockTags.LEAVES)) {
            return 0.25f;
        }
        SoundType type = state.getSoundType();
        if (type == SoundType.SNOW || type == SoundType.SAND || type == SoundType.GRASS
                || type == SoundType.GRAVEL) {
            return 0.45f;
        }
        if (type == SoundType.WOOD) {
            return 0.65f;
        }
        return 0.9f;
    }

    /** Slab intersection includes rays starting inside the box. */
    public static double[] clipRange(Vec3 from, Vec3 to, AABB box) {
        double[] start = {from.x, from.y, from.z};
        double[] delta = {to.x - from.x, to.y - from.y, to.z - from.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        double near = 0;
        double far = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < 1e-10) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) {
                    return null;
                }
                continue;
            }
            double a = (min[axis] - start[axis]) / delta[axis];
            double b = (max[axis] - start[axis]) / delta[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) {
                return null;
            }
        }
        return new double[] {near, far};
    }
}
