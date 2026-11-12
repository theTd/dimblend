package dimblend.experience.compat.create;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Sable-only raycast helpers isolated from {@link SlidingDoorOverhangPick} so JVM bytecode
 * verification never resolves {@code dev.ryanhcode.sable.*} when {@code create} is installed
 * without {@code sable}.
 */
final class SlidingDoorSableOverhangPick {
    private static final double SUBLEVEL_QUERY_MARGIN = 2.0;

    private SlidingDoorSableOverhangPick() {
    }

    static Vec3 eyePosition(Entity camera, float partialTick) {
        return Sable.HELPER.getEyePositionInterpolated(camera, partialTick);
    }

    static Vec3 plotToWorld(ClientLevel level, Vec3 pos, float partialTick) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (subLevel instanceof ClientSubLevel clientSubLevel) {
            Vector3d world = clientSubLevel.renderPose(partialTick)
                    .transformPosition(new Vector3d(pos.x, pos.y, pos.z));
            return new Vec3(world.x, world.y, world.z);
        }
        return Sable.HELPER.projectOutOfSubLevel(level, pos);
    }

    static void clipPairedDoorsInSubLevels(
            ClientLevel level,
            Vec3 worldStart,
            Vec3 worldEnd,
            float partialTick,
            SlidingDoorOverhangPick.BestHit best) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.hasSubLevels()) {
            return;
        }

        Vec3 queryMin = new Vec3(
                Math.min(worldStart.x, worldEnd.x) - SUBLEVEL_QUERY_MARGIN,
                Math.min(worldStart.y, worldEnd.y) - SUBLEVEL_QUERY_MARGIN,
                Math.min(worldStart.z, worldEnd.z) - SUBLEVEL_QUERY_MARGIN);
        Vec3 queryMax = new Vec3(
                Math.max(worldStart.x, worldEnd.x) + SUBLEVEL_QUERY_MARGIN,
                Math.max(worldStart.y, worldEnd.y) + SUBLEVEL_QUERY_MARGIN,
                Math.max(worldStart.z, worldEnd.z) + SUBLEVEL_QUERY_MARGIN);
        BoundingBox3d queryBounds = new BoundingBox3d(queryMin, queryMax);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (SubLevel sub : container.queryIntersecting(queryBounds)) {
            if (!(sub instanceof ClientSubLevel clientSubLevel) || clientSubLevel.isRemoved()) {
                continue;
            }
            Pose3dc pose = clientSubLevel.renderPose(partialTick);
            Vector3d startJoml = pose.transformPositionInverse(
                    new Vector3d(worldStart.x, worldStart.y, worldStart.z));
            Vector3d endJoml = pose.transformPositionInverse(
                    new Vector3d(worldEnd.x, worldEnd.y, worldEnd.z));

            Vec3 localStart = new Vec3(startJoml.x, startJoml.y, startJoml.z);
            Vec3 localEnd = new Vec3(endJoml.x, endJoml.y, endJoml.z);

            BoundingBox3ic plotBounds = clientSubLevel.getPlot().getBoundingBox();
            int minX = Math.max(
                    plotBounds.minX(),
                    Mth.floor(Math.min(localStart.x, localEnd.x) - SlidingDoorOverhangPick.SEARCH_HORIZONTAL_RADIUS));
            int maxX = Math.min(
                    plotBounds.maxX(),
                    Mth.floor(Math.max(localStart.x, localEnd.x) + SlidingDoorOverhangPick.SEARCH_HORIZONTAL_RADIUS));
            int minY = Math.max(plotBounds.minY(), Mth.floor(Math.min(localStart.y, localEnd.y) - 0.5));
            int maxY = Math.min(plotBounds.maxY(), Mth.floor(Math.max(localStart.y, localEnd.y) + 0.5));
            int minZ = Math.max(
                    plotBounds.minZ(),
                    Mth.floor(Math.min(localStart.z, localEnd.z) - SlidingDoorOverhangPick.SEARCH_HORIZONTAL_RADIUS));
            int maxZ = Math.min(
                    plotBounds.maxZ(),
                    Mth.floor(Math.max(localStart.z, localEnd.z) + SlidingDoorOverhangPick.SEARCH_HORIZONTAL_RADIUS));
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                continue;
            }

            for (int bx = minX; bx <= maxX; bx++) {
                int cx = SectionPos.blockToSectionCoord(bx);
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (SlidingDoorOverhangPick.distSqPointToSegment2d(
                            bx + 0.5, bz + 0.5,
                            localStart.x, localStart.z,
                            localEnd.x, localEnd.z) > SlidingDoorOverhangPick.MAX_HORIZONTAL_SEGMENT_DIST_SQ) {
                        continue;
                    }
                    int cz = SectionPos.blockToSectionCoord(bz);
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    for (int by = minY; by <= maxY; by++) {
                        cursor.set(bx, by, bz);
                        BlockState state = level.getBlockState(cursor);
                        if (!SlidingDoorPairing.isPaired(state)) {
                            continue;
                        }
                        BlockPos doorPos = cursor.immutable();
                        BlockHitResult hit = state.getShape(level, doorPos).clip(localStart, localEnd, doorPos);
                        if (hit == null || hit.getType() == HitResult.Type.MISS) {
                            continue;
                        }
                        Vec3 hitLocal = hit.getLocation();
                        Vector3d hitWorld = pose.transformPosition(
                                new Vector3d(hitLocal.x, hitLocal.y, hitLocal.z));
                        double worldDistSq = hitWorld.distanceSquared(worldStart.x, worldStart.y, worldStart.z);
                        if (worldDistSq < best.bestDistSq) {
                            Vec3 clampedLocal = SlidingDoorPairing.clampHitLocationToBlockCenter(hitLocal, doorPos);
                            best.bestDistSq = worldDistSq;
                            best.result = new BlockHitResult(
                                    clampedLocal, hit.getDirection(), doorPos, hit.isInside());
                        }
                    }
                }
            }
        }
    }
}
